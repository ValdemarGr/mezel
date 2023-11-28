package mezel

import scala.concurrent.duration.*
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
import cats.effect.std.Supervisor
import com.google.devtools.build.lib.buildeventstream.{build_event_stream => bes}

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None):
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

  def responseError: ResponseError = ResponseError(code, message, data)

final case class BuildTargetCache(
    buildTargets: List[(String, BuildTargetFiles)]
) {
  def read[A: Decoder](f: BuildTargetFiles => Path): Stream[IO, (String, A)] =
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

final case class BspState(
    workspaceRoot: Option[SafeUri],
    buildTargetCache: Option[BuildTargetCache],
    prevDiagnostics: Map[BuildTargetIdentifier, List[TextDocumentIdentifier]] = Map.empty
)

def pathFullToUri(root: SafeUri, p: Path): SafeUri =
  SafeUri((uriToPath(root) / p).toNioPath.toUri().toString())

def buildIdent(label: String): BuildTargetIdentifier = {
  // canonical labels should start with @
  // such that //blah becomes @//blah
  val fixedLabel = if label.startsWith("@") then label else s"@$label"
  BuildTargetIdentifier(SafeUri(fixedLabel))
}

def uriToPath(suri: SafeUri): Path = Path.fromNioPath(Paths.get(new URI(suri.value)))

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

    val fixedTextDocumentRef = p.replace("workspace-root://", "")
    PublishDiagnosticsParams(
      textDocument = TextDocumentIdentifier(pathFullToUri(root, Path(fixedTextDocumentRef))),
      buildTarget = target,
      originId = None,
      diagnostics = ds,
      reset = true
    )
  }
}

class BspServerOps(
    state: SignallingRef[IO, BspState],
    requestDone: Deferred[IO, Unit],
    sup: Supervisor[IO],
    output: Channel[IO, Json],
    cache: Path
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

  def prevDiagnostics: IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] = state.modify { s =>
    s.copy(prevDiagnostics = Map.empty) -> s.prevDiagnostics
  }

  def cacheDiagnostic(buildTarget: BuildTargetIdentifier, td: TextDocumentIdentifier): IO[Unit] =
    state.update { s =>
      val newState = Map(buildTarget -> List(td)) |+| s.prevDiagnostics
      s.copy(prevDiagnostics = newState)
    }

  def publishDiagnostics(x: PublishDiagnosticsParams): IO[Unit] =
    sendNotification("build/publishDiagnostics", x.asJson)

  def get[A](f: BspState => Option[A])(err: BspState => BspResponseError): IO[A] =
    state.get.flatMap(s => R.fromOption(err(s))(f(s)))

  def workspaceRoot: IO[SafeUri] =
    get(_.workspaceRoot)(_ => BspResponseError.NotInitialized)

  def sendNotification[A: Encoder](method: String, params: A): IO[Unit] =
    output.send(Notification("2.0", method, Some(params.asJson)).asJson).void

  def cachedSemanticdbPath(targetroot: String) = cache / targetroot

  def tasks = workspaceRoot.map { wsr =>
    Tasks(
      wsr,
      _.evalMap { x =>
        sendNotification("build/logMessage", LogMessageParams(MessageType.Info, None, None, x))
      }
    )
  }

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
        Some {
          DependencySourcesResult {
            ds.toList.map { case (label, ds) =>
              DependencySourcesItem(
                buildIdent(label),
                ds.sourcejars.map(x => pathFullToUri(wsr, Path(x))).toList
              )
            }
          }.asJson
        }
      }
    }

  def compile(targets: List[SafeUri]): IO[Option[Json]] = {
    workspaceRoot.flatMap { wsr =>
      tasks.flatMap { t =>
        prevDiagnostics.flatMap { prevDiagnostics =>
          t.diagnosticsFiles.map(_.toMap).flatMap { diagnosticsFiles =>
            Files[IO].tempFile
              .use { tmp =>
                val labelStream = Stream.eval(readId).flatMap { xs =>
                  val bspLabels = xs.map { case (id, _) => id }.toSet
                  parseBEP(tmp).mapFilter { x =>
                    for {
                      comp <- x.payload.completed
                      id <- x.id
                      targetCompletedId <- id.id.targetCompleted
                      label <- Some(targetCompletedId.label).filter(bspLabels.contains)
                    } yield (label, comp.success)
                  }
                }

                val diagnosticsPublications = labelStream.parEvalMapUnorderedUnbounded { case (label, success) =>
                  val readDiagnostics: IO[diagnostics.TargetDiagnostics] =
                    Files[IO]
                      .readAll(diagnosticsFiles(label))
                      .through(fs2.io.toInputStream[IO])
                      .evalMap(is => IO.blocking(diagnostics.TargetDiagnostics.parseFrom(is)))
                      .compile
                      .lastOrError

                  // if there are no diagnostics for a target, clear the diagnostics
                  // if there are diagnostics for a target, publish them
                  val publishDiag: IO[Unit] = readDiagnostics.flatMap { td =>
                    val bti = buildIdent(label)
                    val xs = convertDiagnostic(wsr, bti, td)
                    val prevHere = prevDiagnostics.get(bti).toList.flatten
                    val toClear = prevHere.toSet -- xs.map(_.textDocument)

                    xs.traverse(x => publishDiagnostics(x) *> cacheDiagnostic(bti, x.textDocument)) *>
                      toClear.toList
                        .map(PublishDiagnosticsParams(_, bti, None, Nil, true))
                        .traverse_(publishDiagnostics)
                  }

                  // if success then there are semanticdb files to publish
                  // if !success then use cached semanticdb files

                  publishDiag
                }

                diagnosticsPublications
                  .merge(Stream.eval(t.buildAll(s"--build_event_binary_file=${tmp.absolute.toString()}")))
                  .compile
                  .drain
              }
              .void
              .as {
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
        }
      }
    }
  }

  def sources(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      readSources.map { srcs =>
        Some {
          SourcesResult {
            srcs.toList.map { case (label, src) =>
              SourcesItem(
                buildIdent(label),
                src.sources.map(x => pathFullToUri(wsr, Path(x))).map(SourceItem(_, SourceItemKind.File, false)).toList,
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
        val folderName = r.fileName.toString()
        val execRoot = Path(s"bazel-${folderName}")
        Some {
          ScalacOptionsResult {
            scos.toList.map { case (label, sco) =>
              ScalacOptionsItem(
                buildIdent(label),
                sco.scalacopts ++ List(
                  s"-P:semanticdb:targetroot:${(r / sco.targetroot).toString /*cachedSemanticdbPath(sco.targetroot)*/}",
                  "-Xplugin-require:semanticdb",
                  s"-Xplugin:${(r / execRoot / Path(sco.semanticdbPlugin))}"
                ) ++ sco.plugins.map(x => s"-Xplugin:${r / Path(x)}"),
                sco.classpath.map(x => pathFullToUri(wsr, Path(x))),
                (r / execRoot / sco.targetroot).toString // cachedSemanticdbPath(sco.targetroot).absolute.toString()
              )
            }
          }.asJson
        }
      }
    }

  def buildTargets: IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      tasks.flatMap { t =>
        val r = uriToPath(wsr)
        val folderName = r.fileName.toString()
        val execRoot = s"bazel-${folderName}"
        t.buildTargetCache.flatMap(btc => state.update(_.copy(buildTargetCache = Some(btc)))) >>
          readBuildTargets.map { bts =>
            val targets = bts.toList.map { case (label, bt) =>
              BuildTarget(
                id = buildIdent(label),
                displayName = Some(label),
                baseDirectory = Some(pathFullToUri(wsr, Path(bt.directory))),
                tags = List("library"),
                languageIds = List("scala"),
                dependencies = bt.deps.map(buildIdent),
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
                    jars = bt.scalaCompilerClasspath.map(Path(_)).map(pathFullToUri(wsr, _)),
                    jvmBuildTarget = Some {
                      JvmBuildTarget(
                        javaHome = Some(pathFullToUri(wsr, Path(execRoot) / Path(bt.javaHome))),
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
}
