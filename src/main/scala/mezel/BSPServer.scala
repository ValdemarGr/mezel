package mezel

import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
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
import cats.*
import cats.derived.*
import alleycats.*

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None):
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

final case class BspState(
    workspaceUri: Option[SafeUri]
) derives Empty

object BspState:
  val empty: BspState = Empty[BspState].empty

class BspServerOps(state: SignallingRef[IO, BspState])(implicit R: Raise[IO, BspResponseError]):
  import _root_.io.circe.syntax.*

  def get[A](f: BspState => Option[A])(err: BspState => BspResponseError): IO[A] =
    state.get.flatMap(s => R.fromOption(err(s))(f(s)))

  def workspaceUri: IO[SafeUri] =
    get(_.workspaceUri)(_ => BspResponseError.NotInitialized)

  def initalize(msg: InitializeBuildParams): IO[Option[Json]] =
    state.update(_.copy(workspaceUri = Some(msg.rootUri))) as {
      Some {
        InitializeBuildResult(
          displayName = "Mezel",
          version = "1.0.0",
          bspVersion = "2.1.0",
          capabilities = BuildServerCapabilities(
            compileProvider = Some(AnyProvider(List("scala"))),
            testProvider = None,
            runProvider = None,
            debugProvider = None,
            inverseSourcesProvider = Some(true),
            dependencySourcesProvider = Some(true),
            dependencyModulesProvider = None,
            resourcesProvider = Some(true),
            outputPathsProvider = None,
            buildTargetChangedProvider = Some(false), // can probably be true
            jvmRunEnvironmentProvider = Some(true),
            jvmTestEnvironmentProvider = Some(true),
            canReload = Some(false) // what does this mean?
          )
        ).asJson
      }
    }

  def buldTargets: IO[Option[Json]] =
    workspaceUri.map { uri =>
      Some {
        WorkspaceBuildTargetsResult(
          targets = List(
            BuildTarget(
              id = BuildTargetIdentifier(uri),
              displayName = None,
              baseDirectory = None,
              tags = Nil,
              languageIds = List("scala"),
              dependencies = Nil,
              capabilities = BuildTargetCapabilities(
                canCompile = Some(true),
                canTest = None,
                canRun = None,
                canDebug = None
              ),
              dataKind = None,
              data = None
            )
          )
        ).asJson
      }
    }
