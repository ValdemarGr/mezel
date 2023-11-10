package mezel

import io.circe.parser.*
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

final case class SafeUri(value: String)

final case class InitializeBuildParams(
  rootUri: SafeUri,
  displayName: String,
  version: String,
  bspVersion: String,
  capabilities: BuildClientCapabilities
)

final case class BuildClientCapabilities(languageIds: List[String])

final case class InitializeBuildResult(
  displayName: String,
  version: String,
  bspVersion: String,
  capabilities: BuildServerCapabilities
)

final case class AnyProvider(languageIds: List[String])

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

final case class BuildTargetIdentifier(
  uri: SafeUri
)

final case class BuildTargetCapabilities(
  canCompile: Option[Boolean],
  canTest: Option[Boolean],
  canRun: Option[Boolean],
  canDebug: Option[Boolean]
)

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

final case class WorkspaceBuildTargetsResult(
  targets: List[BuildTarget]
)
