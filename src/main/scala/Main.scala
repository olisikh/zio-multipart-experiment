import zio._
import zio.stream._
import java.nio.charset.StandardCharsets

object App extends ZIOAppDefault:

  override def run = {
    (
      for {
        body <- ZIO.attempt(getClass.getResourceAsStream("/multipart_body.txt"))

        sink = ZSink.collectAll[String]

        boundary = "boundary"
        numParts = 2

        // read the stream tokenize it into "events"
        bodyStream = ZStream.fromInputStream(body, 16) >>>
          ZPipeline.decodeStringWith(StandardCharsets.UTF_8) >>>
          ZPipeline.splitLines >>>
          tokenize(boundary)

        _ <- bodyStream.broadcast(numParts + 1, 16).flatMap { streams =>
          val jsonStream = streams(0) >>> takePart
          val fileStream = streams(1) >>> skipParts(1) >>> takePart

          val json = jsonStream.peel(headersSink).flatMap { case (headers, bodyStream) =>
            Console.printLine(s"json-headers: $headers") *>
              (bodyStream >>> collectBody).runCollect
          }

          val file = fileStream.peel(headersSink).flatMap { case (headers, bodyStream) =>
            Console.printLine(s"file-headers: $headers") *>
              (bodyStream >>> collectBody).runCollect
          }

          val rest = streams(2).runDrain

          (json.debug("json") <&> file.debug("file") <&> rest)
        }
      } yield ()
    ).exitCode
  }

  def printEvents = ZPipeline.tap[Any, Throwable, Event](e => Console.printLine(e))

  def tokenize(boundary: String): ZPipeline[Any, Throwable, String, Event] =
    ZPipeline
      .mapAccumZIO[Any, Throwable, String, Event, Event](Event.Start) {
        case (Event.Start, line @ s"--$boundary") =>
          val next = Event.Boundary(line)
          ZIO.succeed((next, next))

        case (Event.Start, line) =>
          ZIO.fail(new IllegalArgumentException(s"unexpected content: '$line', expected boundary"))

        case (_: Event.Boundary, line) =>
          val next = Event.Header.parse(line)
          ZIO.succeed((next, next))

        case (event: Event.Header, line) => // line can be a header again or split line
          val next =
            if line.isEmpty then Event.Separator
            else Event.Header.parse(line)
          ZIO.succeed((next, next))

        case (Event.Separator, line) => // this must be content
          val next = Event.Content(line)
          ZIO.succeed((next, next))

        case (event: Event.Content, line) => // this is either content continues or boundary or end of multipart content
          val next =
            if line == s"--$boundary" then Event.Boundary(line)
            else if line == s"--$boundary--" then Event.End
            else Event.Content(line)
          ZIO.succeed(next, next)

        case (Event.End, line) =>
          ZIO.fail(IllegalArgumentException(s"no more content is expected, but got '$line'"))
      }

  def headersSink: ZSink[Any, Nothing, Event, Event, Map[String, String]] = ZSink
    .collectAllWhile[Event] {
      case _: Event.Header => true
      case _               => false
    }
    .map(
      _.toList
        .map {
          case (header: Event.Header) => header.name -> header.value
          case _                      => ("", "")
        }
        .toMap
    )

  def collectBody: ZPipeline[Any, Nothing, Event, String] = ZPipeline.collect { case event: Event.Content =>
    event.value
  }

  def skipParts(n: Int): ZPipeline[Any, Throwable, Event, Event] = {
    val pipe = ZPipeline.identity[Event]
    (1 to n).foldLeft(pipe) { (pipe, _) =>
      pipe.dropUntil {
        case _: Event.Boundary => true
        case _                 => false
      }
    }
  }

  def takePart: ZPipeline[Any, Throwable, Event, Event] = {
    val pipe = ZPipeline.identity[Event]
    pipe
      .dropUntil {
        case _: Event.Boundary => true
        case _                 => false
      }
      .filter {
        case Event.Separator => false
        case _               => true
      }
      .takeWhile {
        case (_: Event.Boundary | Event.End) => false
        case _                               => true
      }
  }

sealed trait Event

object Event:
  case object Start extends Event
  case class Boundary(boundary: String) extends Event

  case class Header(name: String, value: String) extends Event
  object Header:
    def parse(line: String) =
      val name = line.takeWhile(_ != ':').trim.toLowerCase
      val value = line.dropWhile(_ != ':').drop(1).trim
      Header(name, value)

  case object Separator extends Event
  case class Content(value: String) extends Event
  case object End extends Event
