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
import com.google.devtools.build.lib.analysis.analysis_v2.PathFragment

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None):
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

  def responseError: ResponseError = ResponseError(code, message, data)

final case class BuildTargetCache(
    buildTargets: List[(String, Tasks.BuildTargetFiles)],
    memoBuildTargets: IO[Map[String, AspectTypes.BuildTarget]],
    memoScalacOptions: IO[Map[String, AspectTypes.ScalacOptions]],
    memoSources: IO[Map[String, AspectTypes.Sources]],
    memoDependencySources: IO[Map[String, AspectTypes.DependencySources]]
)

object BuildTargetCache {
  def fromTargets(buildTargets: List[(String, Tasks.BuildTargetFiles)]): IO[BuildTargetCache] = {
    def mk[A: Decoder](f: Tasks.BuildTargetFiles => Path) =
      buildTargets
        .parTraverse { case (label, bt) =>
          Files[IO].readAll(f(bt)).through(fs2.text.utf8.decode).compile.string.flatMap { str =>
            IO.fromEither(_root_.io.circe.parser.decode[A](str)) tupleLeft label
          }
        }
        .map(_.toMap)
        .memoize
    (
      mk[AspectTypes.BuildTarget](_.buildTarget),
      mk[AspectTypes.ScalacOptions](_.scalacOptions),
      mk[AspectTypes.Sources](_.sources),
      mk[AspectTypes.DependencySources](_.dependencySources)
    ).mapN(BuildTargetCache(buildTargets, _, _, _, _))
  }

  def build(root: SafeUri): IO[BuildTargetCache] =
    Tasks.buildTargetFiles(root).map(xs => xs.map(x => x.label -> x)).flatMap(fromTargets)
}

final case class BspState(
    workspaceRoot: Option[SafeUri],
    buildTargetCache: Option[BuildTargetCache]
)

def pathToUri(p: Path): SafeUri = SafeUri(s"file://${p.absolute.toString}")

def uriToPath(suri: SafeUri): Path = Path.fromNioPath(Paths.get(new URI(suri.value)))

object Tasks {
  def buildConfig(uri: SafeUri, targets: String*): IO[Unit] = {
    val api = BazelAPI(uriToPath(uri))

    api
      .runBuild(
        (targets.toList ++ List(
          "--aspects",
          "//aspects:aspect.bzl%mezel_aspect",
          "--output_groups=bsp_info,bsp_info_deps"
        )): _*
      )
      .void
  }

  final case class BuildTargetFiles(
      label: String,
      scalacOptions: Path,
      sources: Path,
      dependencySources: Path,
      buildTarget: Path
  )

  def buildTargetFiles(uri: SafeUri): IO[List[BuildTargetFiles]] = {
    val api = BazelAPI(uriToPath(uri))

    import dsl._
    api.runBuild("...").void *>
      buildConfig(uri, "//src/main/scala/casehub") *>
      api
        .aquery(mnemonic("MezelAspect")(deps("//src/main/scala/casehub")), "--aspects //aspects:aspect.bzl%mezel_aspect")
        .map { x =>
          val inputMap = x.depSetOfFiles.map(x => x.id -> x.directArtifactIds).toMap
          val targetMap = x.targets.map(x => x.id -> x.label).toMap
          val pathFrags = x.pathFragments.map(p => p.id -> p).toMap
          val arts = x.artifacts.map(x => x.id -> x.pathFragmentId).toMap
          def buildPath(x: analysis_v2.PathFragment): Path = {
            def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
              pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
            }

            go(x).value
          }
          x.actions.toList.map { act =>
            val artifacts = act.inputDepSetIds.flatMap(id => inputMap.get(id).getOrElse(Nil))
            val pfs = artifacts.map(arts).map(pathFrags)
            val so = pfs.find(_.label.endsWith("scalac_options.json")).get
            val s = pfs.find(_.label.endsWith("bsp_sources.json")).get
            val ds = pfs.find(_.label.endsWith("bsp_dependency_sources.json")).get
            val bt = pfs.find(_.label.endsWith("build_target.json")).get
            val root = uriToPath(uri)
            BuildTargetFiles(
              targetMap(act.targetId),
              root / buildPath(so),
              root / buildPath(s),
              root / buildPath(ds),
              root / buildPath(bt)
            )
          }
        }
  }
}

object BspState {
  val empty: BspState = BspState(None, None)
}

class BspServerOps(state: SignallingRef[IO, BspState])(implicit R: Raise[IO, BspResponseError]) {
  import _root_.io.circe.syntax.*

  def readBuildTargetCache[A](f: BuildTargetCache => IO[Map[String, A]]): IO[Map[String, A]] =
    state.get.flatMap { s =>
      R.fromOption(BspResponseError.NotInitialized)(s.buildTargetCache).flatMap(f)
    }

  def readBuildTargets: IO[Map[String, AspectTypes.BuildTarget]] =
    readBuildTargetCache(_.memoBuildTargets)

  def readScalacOptions: IO[Map[String, AspectTypes.ScalacOptions]] =
    readBuildTargetCache(_.memoScalacOptions)

  def readSources: IO[Map[String, AspectTypes.Sources]] =
    readBuildTargetCache(_.memoSources)

  def readDependencySources: IO[Map[String, AspectTypes.DependencySources]] =
    readBuildTargetCache(_.memoDependencySources)

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

  // todo optimize
  def dependencySources(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      readDependencySources.map { ds =>
        val r = uriToPath(wsr)
        Some {
          DependencySourcesResult {
            ds.toList.map { case (label, ds) =>
              DependencySourcesItem(
                BuildTargetIdentifier(pathToUri(Path(label))),
                ds.sourcejars.map(x => pathToUri(r / Path(x))).toList
              )
            }
          }.asJson
        }
      }
    }

  def compile(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.map(d => BazelAPI(uriToPath(d))).flatMap { api =>
      api.runBuild("...").void.as {
        Some {
          CompileResult(
            None,
            StatusCode.Ok,
            None,
            None
          ).asJson
        }
      }
    }

  def sources(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      readSources.map { srcs =>
        val r = uriToPath(wsr)
        Some {
          SourcesResult {
            srcs.toList.map { case (label, src) =>
              SourcesItem(
                BuildTargetIdentifier(pathToUri(Path(label))),
                src.sources.map(x => pathToUri(r / Path(x))).map(SourceItem(_, SourceItemKind.File, false)).toList,
                Nil
              )
            }
          }.asJson
        }
      }
    }

  def scalacOptions(targets: List[SafeUri]) =
    workspaceRoot.flatMap { wsr =>
      readScalacOptions.map { scos =>
        val r = uriToPath(wsr)
        Some {
          ScalacOptionsResult {
            scos.toList.map { case (label, sco) =>
              ScalacOptionsItem(
                BuildTargetIdentifier(pathToUri(Path(label))),
                sco.scalacopts ++ List(
                  s"-P:semanticdb:targetroot:${sco.targetroot}",
                  "-Xplugin-require:semanticdb",
                  s"-Xplugin:${r / "bazel-gateway" / Path(sco.semanticdbPlugin)}"
                ) ++ sco.plugins.map(x => s"-Xplugin:${r / Path(x)}"),
                sco.classpath.map(x => pathToUri(r / Path(x))),
                sco.semanticdbPlugin
              )
            }
          }.asJson
        }
      }
    }

  def buildTargets: IO[Option[Json]] =
    workspaceRoot
      .flatMap(BuildTargetCache.build)
      .flatMap(btc => state.update(_.copy(buildTargetCache = Some(btc)))) >>
      readBuildTargets.map { bts =>
        val targets = bts.toList.map { case (label, bt) =>
          BuildTarget(
            id = BuildTargetIdentifier(pathToUri(Path(label))),
            displayName = Some(label),
            baseDirectory = Some(pathToUri(Path(bt.directory))),
            tags = List("library"),
            languageIds = List("scala"),
            dependencies = bt.deps.map(x => BuildTargetIdentifier(pathToUri(Path(x)))),
            capabilities = BuildTargetCapabilities(
              canCompile = Some(true),
              canTest = Some(false),
              canRun = Some(false),
              canDebug = Some(false)
            ),
            dataKind = Some("scala"),
            data = Some {
              ScalaBuildTarget(
                scalaOrganization = "org.scala-lang",
                scalaVersion = "2.13.12",
                scalaBinaryVersion = "2.13",
                platform = 1,
                jars = bt.scalaCompilerClasspath.map(Path(_)).map(pathToUri),
                jvmBuildTarget = Some {
                  JvmBuildTarget(
                    javaHome = Some(SafeUri("file:///nix/store/7c2ksq340xg06jmym46fzd0rbxphlzm3-openjdk-19.0.2+7/lib/openjdk/")),
                    javaVersion = None
                  )
                }
              )
            }
          )
        }
        Some {
          WorkspaceBuildTargetsResult(targets = targets).asJson
        }
      }
}
