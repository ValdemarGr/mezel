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
import _root_.io.circe.Json
import cats.data.*
import fs2.concurrent.SignallingRef
import catcheffect.*

object Main extends IOApp.Simple {
  def run: IO[Unit] =
    SignallingRef.of[IO, BspState](BspState.empty).flatMap { state =>
      Catch.ioCatch.flatMap { implicit C =>
        Files[IO]
          .tail(Path("/tmp/from-metals"))
          .through(fs2.text.utf8.decode)
          .evalTap(x => IO.println(s"Received: $x"))
          .through(jsonRpcRequests)
          .evalTap(x => IO.println(s"Request: $x"))
          .evalMap { x =>
            def expect[A: Decoder]: IO[A] =
              IO.fromOption(x.params)(new RuntimeException(s"No params for method ${x.method}"))
                .map(_.as[A])
                .rethrow

            // def id: IO[RpcId] =
            //   IO.fromOption(x.id)(new RuntimeException(s"No id for method ${x.method}"))

            C.use[BspResponseError] { implicit R =>
              val ops: BspServerOps = new BspServerOps(state)

              x.method match {
                case "build/initialize"       => expect[InitializeBuildParams].flatMap(ops.initalize)
                case "build/initialized"      => IO.pure(None)
                case "workspace/buildTargets" => ops.buildTargets
                case "buildTarget/scalacOptions" =>
                  expect[ScalacOptionsParams].flatMap(p => ops.scalacOptions(p.targets.map(_.uri)))
                case "buildTarget/javacOptions" => IO.pure(Some(ScalacOptionsResult(Nil).asJson))
                case "buildTarget/sources" =>
                  expect[SourcesParams].flatMap(sps => ops.sources(sps.targets.map(_.uri)))
                // case "buildTarget/dependencySources" => ???
                // case "buildTarget/scalaMainClasses"  => ???
                // case "buildTarget/jvmRunEnvironment" => ???
                // case "buildTarget/scalaTestClasses"  => ???
                // case "buildTarget/compile"           => ???
                // case "build/taskStart"               => ???
                // case "build/taskProgress"            => ???
                case m => IO.raiseError(new RuntimeException(s"Unknown method: $m"))
              }
            }.map {
              case Left(err)    => Response("2.0", x.id, None, Some(err.responseError))
              case Right(value) => Response("2.0", x.id, value, None)
            }
          }
          .map(_.asJson.spaces2)
          .map(data => s"Content-Length: ${data.length}\r\n\r\n$data")
          .through(fs2.text.utf8.encode)
          .through(Files[IO].writeAll(Path("/tmp/to-metals")))
          .compile
          .drain
      }
    }
    // BazelAPI(Path("."))
    //   .aquery {
    //     dsl.kind("scala_library") {
    //       dsl.deps("//...")
    //     }
    //   }
    //   .flatMap { x =>
    //     IO.println(x.artifacts.take(100).mkString("\n")) *>
    //       IO.println(x.actions.take(100).mkString("\n")) *>
    //       IO.println(x.targets.take(100).mkString("\n")) *>
    //       IO.println(x.depSetOfFiles.take(100).mkString("\n")) *>
    //       IO.println(x.configuration.take(100).mkString("\n")) *>
    //       IO.println(x.aspectDescriptors.take(100).mkString("\n")) *>
    //       IO.println(x.ruleClasses.take(100).mkString("\n")) *>
    //       IO.println(x.pathFragments.take(100).mkString("\n"))
    //   }
  /*
    BazelAPI(Path("."))
      .query {
        dsl.kind("scala_library") {
          dsl.deps("//...")
        }
      }.map(_.target.take(100)).flatMap(x => IO(println(x.mkString("\n")))) */
}

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

final case class JvmBuildTarget(
    javaHome: Option[SafeUri],
    javaVersion: Option[String]
) derives Codec.AsObject

final case class ScalaBuildTarget(
    scalaOrganization: String,
    scalaVersion: String,
    scalaBinaryVersion: String,
    platform: 1,
    jars: List[SafeUri],
    jvmBuildTarget: Option[JvmBuildTarget]
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
    data: Option[ScalaBuildTarget]
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
