package mezel

import io.circe.parser.*
import io.circe.*
import fs2.*
import cats.effect.*
import fs2.io.file.*
import cats.parse.Parser as P
import cats.parse.Parser0 as P0
import _root_.io.circe.Json

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

final case class SafeUri(value: String) extends AnyVal
object SafeUri {
  implicit lazy val safeUriDecoder: Decoder[SafeUri] =
    Decoder[String].map(SafeUri(_))
  implicit lazy val safeUriEncoder: Encoder[SafeUri] =
    Encoder[String].contramap(_.value)
}

import _root_.io.circe.generic.semiauto.*
final case class InitializeBuildParams(
    rootUri: SafeUri,
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildClientCapabilities
)
object InitializeBuildParams {
  implicit lazy val dec: Decoder[InitializeBuildParams] =
    deriveDecoder[InitializeBuildParams]
  implicit lazy val enc: Encoder[InitializeBuildParams] =
    deriveEncoder[InitializeBuildParams]
}

final case class BuildClientCapabilities(languageIds: List[String])
object BuildClientCapabilities {
  implicit lazy val dec: Decoder[BuildClientCapabilities] =
    deriveDecoder[BuildClientCapabilities]
  implicit lazy val enc: Encoder[BuildClientCapabilities] =
    deriveEncoder[BuildClientCapabilities]
}

final case class InitializeBuildResult(
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildServerCapabilities
)
object InitializeBuildResult {
  implicit lazy val dec: Decoder[InitializeBuildResult] =
    deriveDecoder[InitializeBuildResult]
  implicit lazy val enc: Encoder[InitializeBuildResult] =
    deriveEncoder[InitializeBuildResult]
}

final case class AnyProvider(languageIds: List[String])
object AnyProvider {
  implicit lazy val dec: Decoder[AnyProvider] = deriveDecoder[AnyProvider]
  implicit lazy val enc: Encoder[AnyProvider] = deriveEncoder[AnyProvider]
}

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
)
object BuildServerCapabilities {
  implicit lazy val dec: Decoder[BuildServerCapabilities] =
    deriveDecoder[BuildServerCapabilities]
  implicit lazy val enc: Encoder[BuildServerCapabilities] =
    deriveEncoder[BuildServerCapabilities]
}

final case class BuildTargetIdentifier(
    uri: SafeUri
)
object BuildTargetIdentifier {
  implicit lazy val dec: Decoder[BuildTargetIdentifier] =
    deriveDecoder[BuildTargetIdentifier]
  implicit lazy val enc: Encoder[BuildTargetIdentifier] =
    deriveEncoder[BuildTargetIdentifier]
}

final case class BuildTargetCapabilities(
    canCompile: Option[Boolean],
    canTest: Option[Boolean],
    canRun: Option[Boolean],
    canDebug: Option[Boolean]
)
object BuildTargetCapabilities {
  implicit lazy val dec: Decoder[BuildTargetCapabilities] =
    deriveDecoder[BuildTargetCapabilities]
  implicit lazy val enc: Encoder[BuildTargetCapabilities] =
    deriveEncoder[BuildTargetCapabilities]
}

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
)
object BuildTarget {
  implicit lazy val dec: Decoder[BuildTarget] = deriveDecoder[BuildTarget]
  implicit lazy val enc: Encoder[BuildTarget] = deriveEncoder[BuildTarget]
}

final case class WorkspaceBuildTargetsResult(
    targets: List[BuildTarget]
)
object WorkspaceBuildTargetsResult {
  implicit lazy val dec: Decoder[WorkspaceBuildTargetsResult] =
    deriveDecoder[WorkspaceBuildTargetsResult]
  implicit lazy val enc: Encoder[WorkspaceBuildTargetsResult] =
    deriveEncoder[WorkspaceBuildTargetsResult]
}

final case class ScalacOptionsParams(
    targets: List[BuildTargetIdentifier]
)
object ScalacOptionsParams {
  implicit lazy val dec: Decoder[ScalacOptionsParams] =
    deriveDecoder[ScalacOptionsParams]
  implicit lazy val enc: Encoder[ScalacOptionsParams] =
    deriveEncoder[ScalacOptionsParams]
}

final case class ScalacOptionsResult(
    items: List[ScalacOptionsItem]
)
object ScalacOptionsResult {
  implicit lazy val dec: Decoder[ScalacOptionsResult] =
    deriveDecoder[ScalacOptionsResult]
  implicit lazy val enc: Encoder[ScalacOptionsResult] =
    deriveEncoder[ScalacOptionsResult]
}

final case class ScalacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
)
object ScalacOptionsItem {
  implicit lazy val dec: Decoder[ScalacOptionsItem] =
    deriveDecoder[ScalacOptionsItem]
  implicit lazy val enc: Encoder[ScalacOptionsItem] =
    deriveEncoder[ScalacOptionsItem]
}

final case class JavacOptionsParams(
    targets: List[BuildTargetIdentifier]
)
object JavacOptionsParams {
  implicit lazy val dec: Decoder[JavacOptionsParams] =
    deriveDecoder[JavacOptionsParams]
  implicit lazy val enc: Encoder[JavacOptionsParams] =
    deriveEncoder[JavacOptionsParams]
}

final case class JavacOptionsResult(
    items: List[JavacOptionsItem]
)
object JavacOptionsResult {
  implicit lazy val dec: Decoder[JavacOptionsResult] =
    deriveDecoder[JavacOptionsResult]
  implicit lazy val enc: Encoder[JavacOptionsResult] =
    deriveEncoder[JavacOptionsResult]
}

final case class JavacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
)
object JavacOptionsItem {
  implicit lazy val dec: Decoder[JavacOptionsItem] =
    deriveDecoder[JavacOptionsItem]
  implicit lazy val enc: Encoder[JavacOptionsItem] =
    deriveEncoder[JavacOptionsItem]
}

final case class SourcesParams(
    targets: List[BuildTargetIdentifier]
)
object SourcesParams {
  implicit lazy val dec: Decoder[SourcesParams] = deriveDecoder[SourcesParams]
  implicit lazy val enc: Encoder[SourcesParams] = deriveEncoder[SourcesParams]
}

final case class SourcesResult(
    items: List[SourcesItem]
)
object SourcesResult {
  implicit lazy val dec: Decoder[SourcesResult] = deriveDecoder[SourcesResult]
  implicit lazy val enc: Encoder[SourcesResult] = deriveEncoder[SourcesResult]
}

final case class SourcesItem(
    target: BuildTargetIdentifier,
    sources: List[SourceItem],
    roots: List[SafeUri]
)
object SourcesItem {
  implicit lazy val dec: Decoder[SourcesItem] = deriveDecoder[SourcesItem]
  implicit lazy val enc: Encoder[SourcesItem] = deriveEncoder[SourcesItem]
}

final case class SourceItem(
    uri: SafeUri,
    kind: SourceItemKind,
    generated: Boolean
)
object SourceItem {
  implicit lazy val dec: Decoder[SourceItem] = deriveDecoder[SourceItem]
  implicit lazy val enc: Encoder[SourceItem] = deriveEncoder[SourceItem]
}

sealed trait SourceItemKind
object SourceItemKind {
  case object File extends SourceItemKind
  case object Directory extends SourceItemKind

  implicit lazy val dec: Decoder[SourceItemKind] = Decoder[String].emap {
    case "file"      => Right(File)
    case "directory" => Right(Directory)
    case other       => Left(s"Unknown SourceItemKind: $other")
  }
  implicit lazy val enc: Encoder[SourceItemKind] = Encoder[String].contramap {
    case File      => "file"
    case Directory => "directory"
  }
}

final case class DependencySourcesParams(
    targets: List[BuildTargetIdentifier]
)
object DependencySourcesParams {
  implicit lazy val dec: Decoder[DependencySourcesParams] =
    deriveDecoder[DependencySourcesParams]
  implicit lazy val enc: Encoder[DependencySourcesParams] =
    deriveEncoder[DependencySourcesParams]
}

final case class DependencySourcesResult(
    items: List[DependencySourcesItem]
)
object DependencySourcesResult {
  implicit lazy val dec: Decoder[DependencySourcesResult] =
    deriveDecoder[DependencySourcesResult]
  implicit lazy val enc: Encoder[DependencySourcesResult] =
    deriveEncoder[DependencySourcesResult]
}

final case class DependencySourcesItem(
    target: BuildTargetIdentifier,
    sources: List[SafeUri]
)
object DependencySourcesItem {
  implicit lazy val dec: Decoder[DependencySourcesItem] =
    deriveDecoder[DependencySourcesItem]
  implicit lazy val enc: Encoder[DependencySourcesItem] =
    deriveEncoder[DependencySourcesItem]
}

final case class ScalaMainClassesParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
)
object ScalaMainClassesParams {
  implicit lazy val dec: Decoder[ScalaMainClassesParams] =
    deriveDecoder[ScalaMainClassesParams]
  implicit lazy val enc: Encoder[ScalaMainClassesParams] =
    deriveEncoder[ScalaMainClassesParams]
}

final case class ScalaMainClassesResult(
    items: List[ScalaMainClassesItem],
    originId: Option[String]
)
object ScalaMainClassesResult {
  implicit lazy val dec: Decoder[ScalaMainClassesResult] =
    deriveDecoder[ScalaMainClassesResult]
  implicit lazy val enc: Encoder[ScalaMainClassesResult] =
    deriveEncoder[ScalaMainClassesResult]
}

final case class ScalaMainClassesItem(
    target: BuildTargetIdentifier,
    classes: List[ScalaMainClass]
)
object ScalaMainClassesItem {
  implicit lazy val dec: Decoder[ScalaMainClassesItem] =
    deriveDecoder[ScalaMainClassesItem]
  implicit lazy val enc: Encoder[ScalaMainClassesItem] =
    deriveEncoder[ScalaMainClassesItem]
}

final case class ScalaMainClass(
    className: String,
    arguments: List[String],
    jvmOptions: List[String],
    environmentVariables: Option[List[String]]
)
object ScalaMainClass {
  implicit lazy val dec: Decoder[ScalaMainClass] =
    deriveDecoder[ScalaMainClass]
  implicit lazy val enc: Encoder[ScalaMainClass] =
    deriveEncoder[ScalaMainClass]
}

final case class ScalaTestClassesParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
)
object ScalaTestClassesParams {
  implicit lazy val dec: Decoder[ScalaTestClassesParams] =
    deriveDecoder[ScalaTestClassesParams]
  implicit lazy val enc: Encoder[ScalaTestClassesParams] =
    deriveEncoder[ScalaTestClassesParams]
}

final case class ScalaTestClassesResult(
    items: List[ScalaTestClassesItem]
)
object ScalaTestClassesResult {
  implicit lazy val dec: Decoder[ScalaTestClassesResult] =
    deriveDecoder[ScalaTestClassesResult]
  implicit lazy val enc: Encoder[ScalaTestClassesResult] =
    deriveEncoder[ScalaTestClassesResult]
}

final case class ScalaTestClassesItem(
    target: BuildTargetIdentifier,
    framework: Option[String],
    classes: List[String]
)
object ScalaTestClassesItem {
  implicit lazy val dec: Decoder[ScalaTestClassesItem] =
    deriveDecoder[ScalaTestClassesItem]
  implicit lazy val enc: Encoder[ScalaTestClassesItem] =
    deriveEncoder[ScalaTestClassesItem]
}

final case class JvmRunEnvironmentParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String]
)
object JvmRunEnvironmentParams {
  implicit lazy val dec: Decoder[JvmRunEnvironmentParams] =
    deriveDecoder[JvmRunEnvironmentParams]
  implicit lazy val enc: Encoder[JvmRunEnvironmentParams] =
    deriveEncoder[JvmRunEnvironmentParams]
}

final case class JvmRunEnvironmentResult(
    items: List[JvmEnvironmentItem]
)
object JvmRunEnvironmentResult {
  implicit lazy val dec: Decoder[JvmRunEnvironmentResult] =
    deriveDecoder[JvmRunEnvironmentResult]
  implicit lazy val enc: Encoder[JvmRunEnvironmentResult] =
    deriveEncoder[JvmRunEnvironmentResult]
}

final case class JvmEnvironmentItem(
    target: BuildTargetIdentifier,
    classpath: List[String],
    jvmOptions: List[String],
    workingDirectory: String,
    environmentVariables: Map[String, String],
    mainClasses: Option[List[JvmMainClass]]
)
object JvmEnvironmentItem {
  implicit lazy val dec: Decoder[JvmEnvironmentItem] =
    deriveDecoder[JvmEnvironmentItem]
  implicit lazy val enc: Encoder[JvmEnvironmentItem] =
    deriveEncoder[JvmEnvironmentItem]
}

final case class JvmMainClass(
    className: String,
    arguments: List[String]
)
object JvmMainClass {
  implicit lazy val dec: Decoder[JvmMainClass] = deriveDecoder[JvmMainClass]
  implicit lazy val enc: Encoder[JvmMainClass] = deriveEncoder[JvmMainClass]
}

final case class CompileParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String],
    arguments: Option[List[String]]
)
object CompileParams {
  implicit lazy val dec: Decoder[CompileParams] = deriveDecoder[CompileParams]
  implicit lazy val enc: Encoder[CompileParams] = deriveEncoder[CompileParams]
}

final case class CompileResult(
    originId: Option[String],
    statusCode: StatusCode,
    dataKind: Option[String],
    data: Option[Json]
)
object CompileResult {
  implicit lazy val dec: Decoder[CompileResult] = deriveDecoder[CompileResult]
  implicit lazy val enc: Encoder[CompileResult] = deriveEncoder[CompileResult]
}

sealed trait StatusCode
object StatusCode {
  case object Ok extends StatusCode
  case object Error extends StatusCode
  case object Cancelled extends StatusCode

  implicit lazy val dec: Decoder[StatusCode] = Decoder[String].emap {
    case "Ok"        => Right(Ok)
    case "Error"     => Right(Error)
    case "Cancelled" => Right(Cancelled)
    case other       => Left(s"Unknown StatusCode: $other")
  }

  implicit lazy val enc: Encoder[StatusCode] = Encoder[String].contramap {
    case Ok        => "Ok"
    case Error     => "Error"
    case Cancelled => "Cancelled"
  }
}

final case class TaskId(id: String)
object TaskId {
  implicit lazy val dec: Decoder[TaskId] = deriveDecoder[TaskId]
  implicit lazy val enc: Encoder[TaskId] = deriveEncoder[TaskId]
}

final case class TaskStartParams(
    taskId: TaskId,
    originId: Option[String],
    eventTime: Option[Long],
    message: Option[String],
    dataKind: Option[TaskStartDataKind],
    data: Option[Json]
)
object TaskStartParams {
  implicit lazy val dec: Decoder[TaskStartParams] =
    deriveDecoder[TaskStartParams]
  implicit lazy val enc: Encoder[TaskStartParams] =
    deriveEncoder[TaskStartParams]
}

sealed trait TaskStartDataKind
object TaskStartDataKind {
  case object CompileTask extends TaskStartDataKind
  case object TestStart extends TaskStartDataKind
  case object TestTask extends TaskStartDataKind

  implicit lazy val dec: Decoder[TaskStartDataKind] = Decoder[String].emap {
    case "compile-task" => Right(CompileTask)
    case "test-start"   => Right(TestStart)
    case "test-task"    => Right(TestTask)
    case other          => Left(s"Unknown TaskStartDataKind: $other")
  }
  implicit lazy val enc: Encoder[TaskStartDataKind] =
    Encoder[String].contramap {
      case CompileTask => "compile-task"
      case TestStart   => "test-start"
      case TestTask    => "test-task"
    }
}

final case class CompileTask(
    target: BuildTargetIdentifier
)
object CompileTask {
  implicit lazy val dec: Decoder[CompileTask] = deriveDecoder[CompileTask]
  implicit lazy val enc: Encoder[CompileTask] = deriveEncoder[CompileTask]
}

final case class TestStart(
    displayName: String,
    location: Option[Location]
)
object TestStart {
  implicit lazy val dec: Decoder[TestStart] = deriveDecoder[TestStart]
  implicit lazy val enc: Encoder[TestStart] = deriveEncoder[TestStart]
}

final case class TestTask(
    target: BuildTargetIdentifier
)
object TestTask {
  implicit lazy val dec: Decoder[TestTask] = deriveDecoder[TestTask]
  implicit lazy val enc: Encoder[TestTask] = deriveEncoder[TestTask]
}

final case class Position(
    line: Int,
    character: Int
)
object Position {
  implicit lazy val dec: Decoder[Position] = deriveDecoder[Position]
  implicit lazy val enc: Encoder[Position] = deriveEncoder[Position]
}

final case class Range(
    start: Position,
    end: Position
)
object Range {
  implicit lazy val dec: Decoder[Range] = deriveDecoder[Range]
  implicit lazy val enc: Encoder[Range] = deriveEncoder[Range]
}

final case class Location(
    uri: SafeUri,
    range: Range
)
object Location {
  implicit lazy val dec: Decoder[Location] = deriveDecoder[Location]
  implicit lazy val enc: Encoder[Location] = deriveEncoder[Location]
}
