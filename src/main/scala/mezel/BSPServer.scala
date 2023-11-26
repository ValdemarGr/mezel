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
import fs2.concurrent.Channel
import _root_.io.bazel.rules_scala.diagnostics.diagnostics

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None):
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

  def responseError: ResponseError = ResponseError(code, message, data)

final case class BuildTargetCache(
    buildTargets: List[(String, Tasks.BuildTargetFiles)]
) {
  def read[A: Decoder](f: Tasks.BuildTargetFiles => Path): Stream[IO, (String, A)] =
    Stream.emits(buildTargets).parEvalMapUnordered(16) { case (label, bt) =>
      Files[IO].readAll(f(bt)).through(fs2.text.utf8.decode).compile.string.flatMap { str =>
        IO.fromEither(_root_.io.circe.parser.decode[A](str)) tupleLeft label
      }
    }

  val readBuildTargets =
    read[AspectTypes.BuildTarget](_.buildTarget)

  val readScalacOptions =
    read[AspectTypes.ScalacOptions](_.scalacOptions)

  val readSources =
    read[AspectTypes.Sources](_.sources)

  val readDependencySources =
    read[AspectTypes.DependencySources](_.dependencySources)
}

// final case class BuildQueryCache(
// )

object BuildTargetCache {
  def build(root: SafeUri): IO[BuildTargetCache] =
    Tasks.buildTargetFiles(root).map(xs => xs.map(x => x.label -> x)).map(BuildTargetCache(_)) // .flatMap(fromTargets)
}

final case class BspState(
    workspaceRoot: Option[SafeUri],
    buildTargetCache: Option[BuildTargetCache]
)

def pathToUri(p: Path): SafeUri = SafeUri(s"file://${p.absolute.toString}")

def uriToPath(suri: SafeUri): Path = Path.fromNioPath(Paths.get(new URI(suri.value)))

object Tasks {
  // def allScalaPaths(uri: SafeUri, targets: String*)

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

  final case class ActionQueryResultExtensions(
      aq: analysis_v2.ActionGraphContainer
  ) {
    lazy val inputMap = aq.depSetOfFiles.map(x => x.id -> x.directArtifactIds).toMap
    lazy val targetMap = aq.targets.map(x => x.id -> x.label).toMap
    lazy val pathFrags = aq.pathFragments.map(p => p.id -> p).toMap
    lazy val arts = aq.artifacts.map(x => x.id -> x.pathFragmentId).toMap

    def buildPath(x: analysis_v2.PathFragment): Path = {
      def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
        pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
      }

      go(x).value
    }
  }

  def buildTargetFiles(uri: SafeUri): IO[List[BuildTargetFiles]] = {
    val api = BazelAPI(uriToPath(uri))

    import dsl._
    api.runBuild("...").void *>
      buildConfig(uri, "//src/main/scala/casehub") *>
      api
        .aquery(mnemonic("MezelAspect")(deps("//src/main/scala/casehub")), "--aspects //aspects:aspect.bzl%mezel_aspect")
        .map { x =>
          val ext = ActionQueryResultExtensions(x)
          x.actions.toList.map { act =>
            val artifacts = act.inputDepSetIds.flatMap(id => ext.inputMap.get(id).getOrElse(Nil))
            val pfs = artifacts.map(ext.arts).map(ext.pathFrags)
            val so = pfs.find(_.label.endsWith("scalac_options.json")).get
            val s = pfs.find(_.label.endsWith("bsp_sources.json")).get
            val ds = pfs.find(_.label.endsWith("bsp_dependency_sources.json")).get
            val bt = pfs.find(_.label.endsWith("build_target.json")).get
            val root = uriToPath(uri)
            BuildTargetFiles(
              ext.targetMap(act.targetId),
              root / ext.buildPath(so),
              root / ext.buildPath(s),
              root / ext.buildPath(ds),
              root / ext.buildPath(bt)
            )
          }
        }
  }

  def diagnosticsProtos(uri: SafeUri): IO[Seq[(String, diagnostics.TargetDiagnostics)]] = {
    val api = BazelAPI(uriToPath(uri))

    import dsl._

    api.aquery(mnemonic("Scalac")("...")).flatMap { aq =>
      val ext = ActionQueryResultExtensions(aq)
      val outputs = aq.actions.mapFilter { a =>
        val label = ext.targetMap(a.targetId)
        val outputs = a.primaryOutputId :: a.outputIds.toList
        val res = outputs.collectFirstSome { id =>
          val p = ext.pathFrags(ext.arts(id))
          if (p.label.endsWith(".diagnosticsproto")) Some(ext.buildPath(p))
          else None
        }
        res tupleLeft label
      }

      val r = uriToPath(uri)
      outputs.traverse { case (label, p) =>
        val fp = r / p
        Files[IO]
          .readAll(fp)
          .through(fs2.io.toInputStream[IO])
          .evalMap(is => IO.blocking(diagnostics.TargetDiagnostics.parseFrom(is)))
          .compile
          .lastOrError tupleLeft label
      }
    }
  }
}

object BspState {
  val empty: BspState = BspState(None, None)
}

def convertDiagnostic(
    root: SafeUri,
    target: BuildTargetIdentifier,
    tds: diagnostics.TargetDiagnostics
): List[PublishDiagnosticsParams] = {
  tds.diagnostics.groupBy(_.path).toList.map { case (p, fds) =>
    val ds = fds.flatMap(_.diagnostics).toList.map { d =>
      val rng = d.range.get
      val start = rng.start.get
      val e = rng.end.get
      Diagnostic(
        range = Range(
          Position(start.line, start.character),
          Position(e.line, e.character)
        ),
        severity = d.severity match {
          case diagnostics.Severity.ERROR       => Some(DiagnosticSeverity.Error)
          case diagnostics.Severity.WARNING     => Some(DiagnosticSeverity.Warning)
          case diagnostics.Severity.INFORMATION => Some(DiagnosticSeverity.Information)
          case diagnostics.Severity.HINT        => Some(DiagnosticSeverity.Hint)
          case _                                => None
        },
        code = Some(Code(d.code.toInt)),
        codeDestription = None,
        source = None,
        message = d.message,
        tags = None,
        relatedInformation = Some {
          d.relatedInformation.toList.map { ri =>
            val rng = ri.location.get.range.get
            val start = rng.start.get
            val e = rng.end.get
            DiagnosticRelatedInformation(
              location = Location(
                uri = SafeUri(ri.location.get.path),
                range = Range(
                  Position(start.line, start.character),
                  Position(e.line, e.character)
                )
              ),
              message = ri.message
            )
          }
        },
        None,
        None
      )
    }

    val r = uriToPath(root)
    val fixedTextDocumentRef = p.replace("workspace-root://", "")
    PublishDiagnosticsParams(
      textDocument = TextDocumentIdentifier(pathToUri(r / Path(fixedTextDocumentRef))),
      buildTarget = target,
      originId = None,
      diagnostics = ds,
      reset = true
    )
  }
}

class BspServerOps(
    state: SignallingRef[IO, BspState],
    outChan: Channel[IO, (String, Json)]
)(implicit R: Raise[IO, BspResponseError]) {
  import _root_.io.circe.syntax.*

  // for now we just compile
  // TODO, we can stream the results out into json
  def readBuildTargetCache[A](f: BuildTargetCache => Stream[IO, (String, A)]): IO[List[(String, A)]] = {
    val fa = for {
      curr <- Stream.eval(state.get)
      btc <- Stream.eval(R.fromOption(BspResponseError.NotInitialized)(curr.buildTargetCache))
      res <- f(btc)
    } yield res

    fa.compile.toList
  }

  def readId = readBuildTargetCache(x => Stream.emits(x.buildTargets))

  def readBuildTargets = readBuildTargetCache(_.readBuildTargets)

  def readScalacOptions = readBuildTargetCache(_.readScalacOptions)

  def readSources = readBuildTargetCache(_.readSources)

  def readDependencySources = readBuildTargetCache(_.readDependencySources)

  def get[A](f: BspState => Option[A])(err: BspState => BspResponseError): IO[A] =
    state.get.flatMap(s => R.fromOption(err(s))(f(s)))

  def workspaceRoot: IO[SafeUri] =
    get(_.workspaceRoot)(_ => BspResponseError.NotInitialized)

  def initalize(msg: InitializeBuildParams): IO[Option[Json]] =
    state
      .update(_.copy(workspaceRoot = Some(msg.rootUri)))
      .as {
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
    workspaceRoot.flatMap { wsr =>
      val api = BazelAPI(uriToPath(wsr))
      api.runBuild("...").void.as {
        Some {
          CompileResult(
            None,
            StatusCode.Ok,
            None,
            None
          ).asJson
        }
      } <* readId.flatMap { xs =>
        Tasks.diagnosticsProtos(wsr).flatMap { ys =>
          val labels = xs.map { case (id, _) => id }.toSet
          val interesting = ys.filter { case (label, _) => labels.contains(label) }
          interesting.traverse_ { case (l, td) =>
            val ds = convertDiagnostic(wsr, BuildTargetIdentifier(pathToUri(Path(l))), td)
            ds.traverse_(pd => outChan.send("build/publishDiagnostics" -> pd.asJson).void)
          }
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
    workspaceRoot.flatMap { wsr =>
      val r = uriToPath(wsr)
      BuildTargetCache.build(wsr).flatMap(btc => state.update(_.copy(buildTargetCache = Some(btc)))) >>
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
                  scalaVersion = bt.compilerVersion.toVersion,
                  scalaBinaryVersion = s"${bt.compilerVersion.major}.${bt.compilerVersion.minor}",
                  platform = 1,
                  jars = bt.scalaCompilerClasspath.map(Path(_)).map(pathToUri),
                  jvmBuildTarget = Some {
                    JvmBuildTarget(
                      javaHome = Some(pathToUri(r / "bazel-gateway" / Path(bt.javaHome))),
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
}
