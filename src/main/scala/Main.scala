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

        // read the stream till the separator, making a Part of headers and a stream body

        bodyStream = ZStream.fromInputStream(body, 16) >>>
          stringify >>>
          ZPipeline.splitLines >>>
          tokenize(boundary) >>>
          ZPipeline.mapZIO { (part: Part) =>
            part.content.runCollect.debug
          }

        _ <- bodyStream.runDrain

        // streams <- bodyStream.broadcast(numParts + 1, 16)

        // jsonStream = streams(0) >>> takePart >>> ZSink.collectAll[Event]
        // fileStream = streams(1) >>> skipParts(1) >>> takePart >>> ZSink.collectAll[Event]

        // drain the stream till the end to handle request
        // restStream = streams(2) >>> ZSink.drain

        // _ <- jsonStream.debug("JSON") <&> fileStream.debug("FILE") <&> restStream
      } yield ()
    ).exitCode
  }

  def printEvents = ZPipeline.tap[Any, Throwable, Event](e => Console.printLine(e))

  def stringify = ZPipeline.mapChunks[Byte, String](chunk => Chunk(new String(chunk.toArray, StandardCharsets.UTF_8)))

  def tokenize(boundary: String): ZPipeline[Any, Throwable, String, Part] =
    ZPipeline.mapAccumZIO[Any, Throwable, String, (Event, Part), Part]((Event.Start, Part(Map.empty, ZStream.empty))) {
      case ((Event.Start, part), line @ s"--$boundary") =>
        val next = Event.Boundary(line)
        val part = Part.empty
        ZIO.succeed(((next, part), part))

      case ((Event.Start, _), line) =>
        ZIO.fail(new IllegalArgumentException(s"unexpected content: '$line', expected boundary"))

      case ((_: Event.Boundary, part), line) =>
        val header = Event.Header.parse(line)
        val newPart = part.addHeader(header)
        ZIO.succeed(((header, newPart), newPart))

      case ((event: Event.Header, part), line) => // line can be a header again or split line
        if line.isEmpty then ZIO.succeed(((Event.Separator, part), part))
        else
          val header = Event.Header.parse(line)
          val newPart = part.addHeader(header)
          ZIO.succeed(((header, newPart), newPart))

      case ((Event.Separator, part), line) => // this must be content
        val next = Event.Content(line)
        val newPart = part.addContent(line)

        ZIO.succeed(((next, newPart), newPart))

      case ((event: Event.Content, part), line) =>
        // this is either content continues or boundary or end of multipart content
        if line == s"--$boundary" then
          val newPart = Part.empty
          ZIO.succeed(((Event.Boundary(line), part), part))
        else if line == s"--$boundary--" then ZIO.succeed(((Event.End, part), part))
        else
          val newPart = part.addContent(line)
          ZIO.succeed(((Event.Content(line), newPart), newPart))

      case ((Event.End, part), line) =>
        ZIO.fail(IllegalArgumentException(s"no more content is expected, but got '$line'"))
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

// how?
case class Part(headers: Map[String, String], content: ZStream[Any, Throwable, String]):
  def addHeader(event: Event.Header): Part = this.copy(headers = headers ++ Map(event.name -> event.value))
  def addContent(content: String): Part = this.copy(content = this.content ++ ZStream(content))

object Part:
  def empty: Part = Part(Map.empty, ZStream.empty)

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

// sealed trait Event:
// def parse(line: String): Event = this match
//   case headers: Event.Headers =>
//     headers.next(line)
//   case content: Event.Content =>
//     content.next(line)

// object Event:
// def start: Event = Headers(Map.empty)

// case object StartPart extends Event

// case class Headers(headers: Map[String, String]) extends Event:
//   def next(line: String): Event =
//     if (line == "") then Event.Content(headers, "")
//     else
//       val name = line.takeWhile(_ != ':').trim.toLowerCase
//       val value = line.dropWhile(_ != ':').drop(1).trim
//       Event.Headers(headers ++ Map(name -> value))
//
// case class Content(headers: Map[String, String], content: String) extends Event:
//   def next(line: String): Event =
//     Content(headers, content = content + line)

// case object EndPart extends Event
