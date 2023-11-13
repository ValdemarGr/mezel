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
import java.nio.file.Paths
import java.net.URI

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None):
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

  def responseError: ResponseError = ResponseError(code, message, data)

final case class BspState(
    workspaceRoot: Option[SafeUri]
) derives Empty

object BspState:
  val empty: BspState = Empty[BspState].empty

class BspServerOps(state: SignallingRef[IO, BspState])(implicit R: Raise[IO, BspResponseError]):
  import _root_.io.circe.syntax.*

  def get[A](f: BspState => Option[A])(err: BspState => BspResponseError): IO[A] =
    state.get.flatMap(s => R.fromOption(err(s))(f(s)))

  def workspaceRoot: IO[SafeUri] =
    get(_.workspaceRoot)(_ => BspResponseError.NotInitialized)

  def initalize(msg: InitializeBuildParams): IO[Option[Json]] =
    state
      .update(_.copy(workspaceRoot = Some(msg.rootUri)))
      .as:
        Some:
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

  def buildTargets: IO[Option[Json]] =
    workspaceRoot.flatMap { uri =>
      import dsl._

      def q = kind("scala_library")("...")

      def aq = let("everything")(deps("...")) { everything =>
        val sc = kind("scala_library")(mnemonic("Scalac")(everything))
        val jvm = kind("jvm_import")(everything)
        val gen = kind("genrule")(everything)
        union(sc) {
          union(jvm) {
            gen
          }
        }
      }

      val api = BazelAPI(Path.fromNioPath(Paths.get(new URI(uri.value))))
      // use query to get targets & deps & plugins
      // use aquery to get output jars (target -> outputs):
      //   for aquery, scala_library produces semanticdb
      //   for aquery, jvm_import produces jar + srcjar
      //
      // we can also get jar inputs from aquery, but prefer deps + aquery since it's more consistent and required to get sources anyway

      // also, steal the -P semanticdb flag from Scalac's args

      (api.query(q), api.aquery(aq)).mapN { (qr, aqr) =>
        final case class QueryTarget(
            path: Path,
            deps: Seq[String]
            // plugins: Seq[String]
        )

        val scalaQueryTargets: Map[String, QueryTarget] = qr.target
          .mapFilter(_.rule)
          .mapFilter { x =>
            x.location.map { loc =>
              x.name -> QueryTarget(
                Path(loc).parent.get,
                x.attribute.filter(_.name === "deps").flatMap(_.stringListValue)
                // x.attribute.filter(_.name === "plugins").flatMap(_.stringListValue)
              )
            }
          }
          .toMap

        val targetIdToLabel = aqr.targets.map(x => x.id -> x.label).toMap

        val scalacActions = aqr.actions.filter(_.mnemonic === "Scalac")

        val scalacTargets = scalacActions.map(x => targetIdToLabel(x.targetId)).toSet

        val semanticsDbArg = scalacActions
          .map(x => targetIdToLabel(x.targetId) -> x.arguments.find(_.startsWith("-P:semanticdb:targetroot")).get)
          .toMap

        val d = (scalacTargets -- scalaQueryTargets.keySet) ++ (scalaQueryTargets.keySet -- scalacTargets)
        assert(scalacTargets == scalaQueryTargets.keySet, s"should find the same build targets in query and aquery, difference was ${d}")
        assert(scalacTargets == semanticsDbArg.keySet, s"every scala target should have a semanticdb arg")

        // lazy val scalacLabelToArtifacts = scalacActions
        //   .mapFilter(x => targetIdToLabel.get(x.targetId).map(label => label -> x.outputIds))

        // val pathMap = aqr.pathFragments.map(x => x.id -> x).toMap

        // def fullPath(x: analysis_v2.PathFragment): Path = {
        //   def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
        //     pathMap.get(x.parentId) match {
        //       case None         => Eval.now(Path(x.label))
        //       case Some(parent) => go(parent).map(_ / x.label)
        //     }
        //   }

        //   go(x).value
        // }

        // lazy val semanticsDbFiles = scalacLabelToArtifacts
        //   .map { case (k, xs) => k -> xs.mapFilter(pathMap.get).filter(_.label.endsWith(".semanticdb")).map(fullPath) }

        def pathToUri(p: Path): SafeUri = SafeUri(s"file:${p.absolute.toString}")

        val targets = scalacTargets.toList.map { target =>
          val qt = scalaQueryTargets(target)
          val scalaDeps: Seq[SafeUri] =
            qt.deps.mapFilter(scalaQueryTargets.get).map(x => pathToUri(x.path))
          BuildTarget(
            id = BuildTargetIdentifier(pathToUri(qt.path)),
            displayName = Some(qt.path.fileName.toString),
            baseDirectory = Some(pathToUri(qt.path)),
            tags = Nil,
            languageIds = List("scala"),
            dependencies = scalaDeps.map(BuildTargetIdentifier(_)).toList,
            capabilities = BuildTargetCapabilities(
              canCompile = Some(true),
              canTest = None,
              canRun = None,
              canDebug = None
            ),
            dataKind = Some("scala"),
            data = None
          )
        }

        Some {
          WorkspaceBuildTargetsResult(targets = targets).asJson
        }
      }
    }
