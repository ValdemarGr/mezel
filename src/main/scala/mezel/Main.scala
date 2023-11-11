package mezel

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
import _root_.io.circe.Json
import cats.Eval

object Main extends IOApp.Simple {

  def run: IO[Unit] = {
    val content = _root_.io.circe.parser
      .parse("""{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "displayName": "Mezel",
    "version": "1.0.0",
    "bspVersion": "2.0.0",
    "capabilities": {
      "compileProvider": {
        "languageIds": ["scala"]
      }
    }
  }
}""").fold(throw _, identity)
      .noSpaces
    val msg =
      s"""|Content-Length: ${content.length}
              |""".stripMargin + "\r\n" + content

    Stream(msg)
      .through(fs2.text.utf8.encode)
      .through(Files[IO].writeAll(Path("/tmp/to-metals")))
      .compile
      .drain

    // Files[IO]
    //   .tail(Path("/tmp/metals"))
    //   .through(text.utf8.decode)
    //   .evalMap { x =>
    //   }
    //   .compile
    //   .drain
  }
}

enum ParserState:
  case NoState
  case ContentLength(len: Int)

final case class ParserContent(
    state: ParserState,
    content: String
)

def jsonRpcParser: Pipe[IO, String, Json] = { stream =>
  def produce(pc: ParserContent): Either[String, Option[(Option[Json], ParserContent)]] = {
    def p[A](p: P[A]) = p.parse(pc.content).toOption

    val nlParser = Rfc.crlf | Rfc.lf | Rfc.cr

    val nl = p(nlParser)

    val cl = p(
      P.string(
        "Content-Length:"
      ) *> Rfc.wsp.rep0 *> Num.bigInt
    )

    val ct = p((P.string("Content-Type:") *> Rfc.wsp.rep0).void)

    val headers =
      nl.map { case (x, _) => Right(pc.copy(content = x)) } orElse
        cl.map { case (x, cl) =>
          pc.state match {
            case ParserState.ContentLength(_) => Left("Content-Length after Content-Length")
            case ParserState.NoState          => Right(ParserContent(ParserState.ContentLength(cl.toInt), x))
          }
        } orElse
        ct.map { case (x, _) => Right(pc.copy(content = x)) }

    val parsedHeaders: Either[String, Option[ParserContent]] = headers.sequence

    val out: Either[String, Option[(Option[Json], ParserContent)]] = parsedHeaders.flatMap {
      case Some(x) => Right(Some((None, x)))
      case None =>
        pc.state match {
          case ParserState.ContentLength(len) if pc.content.length >= len =>
            val (content, rest) = pc.content.splitAt(len)
            val json: Either[ParsingFailure, Json] = _root_.io.circe.parser.parse(content)
            json.leftMap(_.getMessage).map(x => Some((Some(x), ParserContent(ParserState.NoState, rest))))
          case ParserState.ContentLength(_) | ParserState.NoState => Right(None)
        }
    }

    out
  }

  def unroll(pc: ParserContent): Eval[Either[String, (List[Json], ParserContent)]] = Eval.defer {
    produce(pc).flatTraverse {
      case Some((j, pc)) => unroll(pc).map(_.map { case (j2, pc2) => (j.toList ++ j2.toList, pc2) })
      case None          => Eval.now(Right((Nil, pc)))
    }
  }

  val parsedStream =
    stream.evalMapAccumulate(ParserContent(ParserState.NoState, "")) { case (z, x) =>
      val pc = z.copy(content = z.content + x)

      val io = IO.fromEither(unroll(pc).value.leftMap(x => new RuntimeException(x)))
      io.map(_.swap)
    }

  parsedStream.flatMap { case (_, xs) => Stream.emits(xs) }
}

final case class RpcId(value: String | Int) extends AnyVal
object RpcId:
  given Decoder[RpcId] = (Decoder[String] or Decoder[String]).map(RpcId(_))
  given Encoder[RpcId] = Encoder.instance[RpcId] {
    case RpcId(value: String) => Encoder[String].apply(value)
    case RpcId(value: Int)    => Encoder[Int].apply(value)
  }

final case class Request(
    jsonrpc: String,
    id: Option[RpcId],
    method: String,
    params: Option[Json]
) derives Codec.AsObject

final case class Response(
    jsonrpc: String,
    id: Option[RpcId],
    result: Option[Json],
    error: Option[ResponseError]
) derives Codec.AsObject

final case class ResponseError(
    code: Int,
    message: String,
    data: Option[Json]
) derives Codec.AsObject

final case class SafeUri(value: String) extends AnyVal
object SafeUri:
  given Decoder[SafeUri] =
    Decoder[String].map(SafeUri(_))
  given Encoder[SafeUri] =
    Encoder[String].contramap(_.value)

import _root_.io.circe.generic.semiauto.*
final case class InitializeBuildParams(
    rootUri: SafeUri,
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildClientCapabilities
) derives Codec.AsObject

final case class BuildClientCapabilities(languageIds: List[String]) derives Codec.AsObject

final case class InitializeBuildResult(
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildServerCapabilities
) derives Codec.AsObject

final case class AnyProvider(languageIds: List[String]) derives Codec.AsObject

final case class BuildServerCapabilities(
    compileProvider: Option[AnyProvider],
    testProvider: Option[AnyProvider],
    runProvider: Option[AnyProvider],
    debugProvider: Option[AnyProvider],
    inverseSourcesProvider: Option[Boolean],
    dependencySourcesProvider: Option[Boolean],
    dependencyModulesProvider: Option[Boolean],
    resourcesProvider: Option[Boolean],
    outputPathsProvider: Option[Boolean],
    buildTargetChangedProvider: Option[Boolean],
    jvmRunEnvironmentProvider: Option[Boolean],
    jvmTestEnvironmentProvider: Option[Boolean],
    canReload: Option[Boolean]
) derives Codec.AsObject

final case class BuildTargetIdentifier(
    uri: SafeUri
) derives Codec.AsObject

final case class BuildTargetCapabilities(
    canCompile: Option[Boolean],
    canTest: Option[Boolean],
    canRun: Option[Boolean],
    canDebug: Option[Boolean]
) derives Codec.AsObject

final case class BuildTarget(
    id: BuildTargetIdentifier,
    displayName: Option[String],
    baseDirectory: Option[SafeUri],
    tags: List[String],
    languageIds: List[String],
    dependencies: List[BuildTargetIdentifier],
    capabilities: BuildTargetCapabilities,
    dataKind: Option[String],
    data: Option[Json]
) derives Codec.AsObject

final case class WorkspaceBuildTargetsResult(
    targets: List[BuildTarget]
) derives Codec.AsObject

final case class ScalacOptionsParams(
    targets: List[BuildTargetIdentifier]
) derives Codec.AsObject

final case class ScalacOptionsResult(
    items: List[ScalacOptionsItem]
) derives Codec.AsObject

final case class ScalacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
) derives Codec.AsObject

final case class JavacOptionsParams(
    targets: List[BuildTargetIdentifier]
) derives Codec.AsObject

final case class JavacOptionsResult(
    items: List[JavacOptionsItem]
) derives Codec.AsObject

final case class JavacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
) derives Codec.AsObject

final case class SourcesParams(
    targets: List[BuildTargetIdentifier]
) derives Codec.AsObject

final case class SourcesResult(
    items: List[SourcesItem]
) derives Codec.AsObject

final case class SourcesItem(
    target: BuildTargetIdentifier,
    sources: List[SourceItem],
    roots: List[SafeUri]
) derives Codec.AsObject

final case class SourceItem(
    uri: SafeUri,
    kind: SourceItemKind,
    generated: Boolean
) derives Codec.AsObject

enum SourceItemKind:
  case File
  case Directory

object SourceItemKind:
  given Decoder[SourceItemKind] = Decoder[String].emap:
    case "file"      => Right(File)
    case "directory" => Right(Directory)
    case other       => Left(s"Unknown SourceItemKind: $other")

  given Encoder[SourceItemKind] = Encoder[String].contramap:
    case File      => "file"
    case Directory => "directory"

final case class DependencySourcesParams(
    targets: List[BuildTargetIdentifier]
) derives Codec.AsObject

final case class DependencySourcesResult(
    items: List[DependencySourcesItem]
) derives Codec.AsObject

final case class DependencySourcesItem(
    target: BuildTargetIdentifier,
    sources: List[SafeUri]
) derives Codec.AsObject

final case class ScalaMainClassesParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
) derives Codec.AsObject

final case class ScalaMainClassesResult(
    items: List[ScalaMainClassesItem],
    originId: Option[String]
) derives Codec.AsObject

final case class ScalaMainClassesItem(
    target: BuildTargetIdentifier,
    classes: List[ScalaMainClass]
) derives Codec.AsObject

final case class ScalaMainClass(
    className: String,
    arguments: List[String],
    jvmOptions: List[String],
    environmentVariables: Option[List[String]]
) derives Codec.AsObject

final case class ScalaTestClassesParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
) derives Codec.AsObject

final case class ScalaTestClassesResult(
    items: List[ScalaTestClassesItem]
) derives Codec.AsObject

final case class ScalaTestClassesItem(
    target: BuildTargetIdentifier,
    framework: Option[String],
    classes: List[String]
) derives Codec.AsObject

final case class JvmRunEnvironmentParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
) derives Codec.AsObject

final case class JvmRunEnvironmentResult(
    items: List[JvmEnvironmentItem]
) derives Codec.AsObject

final case class JvmEnvironmentItem(
    target: BuildTargetIdentifier,
    classpath: List[String],
    jvmOptions: List[String],
    workingDirectory: String,
    environmentVariables: Map[String, String],
    mainClasses: Option[List[JvmMainClass]]
) derives Codec.AsObject

final case class JvmMainClass(
    className: String,
    arguments: List[String]
) derives Codec.AsObject

final case class CompileParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String],
    arguments: Option[List[String]]
) derives Codec.AsObject

final case class CompileResult(
    originId: Option[String],
    statusCode: StatusCode,
    dataKind: Option[String],
    data: Option[Json]
) derives Codec.AsObject

sealed trait StatusCode
object StatusCode:
  case object Ok extends StatusCode
  case object Error extends StatusCode
  case object Cancelled extends StatusCode

  given Decoder[StatusCode] = Decoder[String].emap:
    case "Ok"        => Right(Ok)
    case "Error"     => Right(Error)
    case "Cancelled" => Right(Cancelled)
    case other       => Left(s"Unknown StatusCode: $other")

  given Encoder[StatusCode] = Encoder[String].contramap:
    case Ok        => "Ok"
    case Error     => "Error"
    case Cancelled => "Cancelled"

final case class TaskId(id: String) derives Codec.AsObject

final case class TaskStartParams(
    taskId: TaskId,
    originId: Option[String],
    eventTime: Option[Long],
    message: Option[String],
    dataKind: Option[TaskStartDataKind],
    data: Option[Json]
) derives Codec.AsObject

enum TaskStartDataKind:
  case CompileTask
  case TestStart
  case TestTask

object TaskStartDataKind:
  given Decoder[TaskStartDataKind] = Decoder[String].emap:
    case "compile-task" => Right(CompileTask)
    case "test-start"   => Right(TestStart)
    case "test-task"    => Right(TestTask)
    case other          => Left(s"Unknown TaskStartDataKind: $other")

  given Encoder[TaskStartDataKind] = Encoder[String].contramap:
    case CompileTask => "compile-task"
    case TestStart   => "test-start"
    case TestTask    => "test-task"

final case class CompileTask(
    target: BuildTargetIdentifier
) derives Codec.AsObject

final case class TestStart(
    displayName: String,
    location: Option[Location]
) derives Codec.AsObject

final case class TestTask(
    target: BuildTargetIdentifier
) derives Codec.AsObject

final case class Position(
    line: Int,
    character: Int
) derives Codec.AsObject

final case class Range(
    start: Position,
    end: Position
) derives Codec.AsObject

final case class Location(
    uri: SafeUri,
    range: Range
) derives Codec.AsObject

final case class TaskProgressParams(
    taskId: TaskId,
    originId: Option[String],
    eventTime: Option[Long],
    message: Option[String],
    total: Option[Long],
    progress: Option[Long],
    unit: Option[String],
    dataKind: Option[String],
    data: Option[Json]
) derives Codec.AsObject

final case class TaskFinishParams(
    taskId: TaskId,
    originId: Option[String],
    eventTime: Option[Long],
    message: Option[String],
    status: StatusCode,
    dataKind: Option[TaskFinishDataKind],
    data: Option[Json]
) derives Codec.AsObject

enum TaskFinishDataKind:
  case CompileReport
  case TestFinish
  case TestReport

object TaskFinishDataKind:
  given Decoder[TaskFinishDataKind] = Decoder[String].emap:
    case "compile-report" => Right(CompileReport)
    case "test-finish"    => Right(TestFinish)
    case "test-report"    => Right(TestReport)
    case other            => Left(s"Unknown TaskFinishDataKind: $other")

  given Encoder[TaskFinishDataKind] =
    Encoder[String].contramap:
      case CompileReport => "compile-report"
      case TestFinish    => "test-finish"
      case TestReport    => "test-report"

final case class CompileReport(
    target: BuildTargetIdentifier,
    originId: Option[String],
    errors: Int,
    warnings: Int,
    time: Option[Long],
    noOp: Option[Boolean]
) derives Codec.AsObject

final case class TestFinish(
    displayName: String,
    message: Option[String],
    status: TestStatus,
    location: Option[Location],
    dataKind: Option[String],
    data: Option[Json]
) derives Codec.AsObject

enum TestStatus:
  case Passed
  case Failed
  case Ignored
  case Cancelled
  case Skipped

object TestStatus:
  given Decoder[TestStatus] = Decoder[String].emap:
    case "passed"    => Right(TestStatus.Passed)
    case "failed"    => Right(TestStatus.Failed)
    case "ignored"   => Right(TestStatus.Ignored)
    case "cancelled" => Right(TestStatus.Cancelled)
    case "skipped"   => Right(TestStatus.Skipped)
    case other       => Left(s"Unknown TestStatus: $other")

  given Encoder[TestStatus] = Encoder[String].contramap:
    case Passed    => "passed"
    case Failed    => "failed"
    case Ignored   => "ignored"
    case Cancelled => "cancelled"
    case Skipped   => "skipped"

final case class TestReport(
    target: BuildTargetIdentifier,
    passed: Int,
    failed: Int,
    ignored: Int,
    cancelled: Int,
    skipped: Int,
    time: Option[Long]
) derives Codec.AsObject
