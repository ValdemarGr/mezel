package mezel

import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
import _root_.io.circe.syntax.*
import cats.implicits.*
import io.circe.parser.*
import io.circe.*
import fs2.*
import cats.effect.*
import fs2.io.file.*
import cats.parse.Parser as P
import cats.parse.Parser0 as P0
import cats.parse.Rfc5234 as Rfc
import cats.parse.Numbers as Num
import scala.concurrent.duration.*
import _root_.io.circe.Json
import cats.data.*
import fs2.concurrent.SignallingRef
import catcheffect.*
import fs2.concurrent.Channel
import cats.effect.std.Supervisor

enum ParserState:
  case NoState
  case ContentLength(len: Int)

final case class ParserContent(
    state: ParserState,
    content: String
)

def jsonRpcRequests: Pipe[IO, String, Request] = _.through(jsonRpcParser)
  .map(_.as[Request])
  .rethrow

def jsonRpcParser: Pipe[IO, String, Json] = { stream =>
  final case class Output(
      data: Option[Json],
      newContent: ParserContent
  )
  type Effect[A] = OptionT[Either[String, *], A]
  def produce(pc: ParserContent): Effect[Output] = {
    def p[A](p: P[A]): Effect[(String, A)] =
      OptionT.fromOption(p.parse(pc.content).toOption)

    val nlParser = Rfc.crlf | Rfc.lf | Rfc.cr

    val nl = p(nlParser)

    val cl = p(P.string("Content-Length:") *> Rfc.wsp.rep0 *> Num.bigInt <* nlParser)

    val ct = p((P.string("Content-Type:") *> Rfc.wsp.rep0 <* nlParser).void)

    val headers: Effect[ParserContent] =
      nl.map { case (x, _) => pc.copy(content = x) } orElse
        cl.semiflatMap { case (x, cl) =>
          pc.state match {
            case ParserState.ContentLength(_) => Left("Content-Length after Content-Length")
            case ParserState.NoState          => Right(ParserContent(ParserState.ContentLength(cl.toInt), x))
          }
        } orElse
        ct.map { case (x, _) => pc.copy(content = x) }

    headers.map(Output(none, _)).orElse {
      if pc.content.isEmpty then OptionT.none
      else if pc.content.startsWith("{") || pc.content.startsWith("[") then
        pc.state match {
          case ParserState.ContentLength(len) if pc.content.length >= len =>
            val (content, rest) = pc.content.splitAt(len)
            val json: Either[ParsingFailure, Json] = _root_.io.circe.parser.parse(content)
            OptionT.liftF {
              json.leftMap(_.getMessage).map(x => Output(Some(x), ParserContent(ParserState.NoState, rest)))
            }
          case ParserState.ContentLength(_) | ParserState.NoState => OptionT.none
        }
      else OptionT.liftF(Left(s"Unknown content, state is $pc"))
    }
  }

  def unroll(pc: ParserContent): IO[(List[Json], ParserContent)] = {
    val io: IO[Option[Output]] = IO.fromEither(produce(pc).value.leftMap(x => new RuntimeException(x)))
    io.flatMap {
      case Some(o) => unroll(o.newContent).map { case (j2, pc2) => (o.data.toList ++ j2.toList, pc2) }
      case None    => IO.pure((Nil, pc))
    }
  }

  val parsedStream =
    stream.evalMapAccumulate(ParserContent(ParserState.NoState, "")) { case (z, x) =>
      unroll(z.copy(content = z.content + x)).map(_.swap)
    }

  parsedStream.flatMap { case (_, xs) => Stream.emits(xs) }
}

