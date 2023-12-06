package mezel

import scala.concurrent.duration.*
import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
import cats.implicits.*
import io.circe.parser.*
import io.circe.*
import fs2.*
import cats.effect.{Trace => _, *}
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
    sourceEnv: Option[Map[String, String]],
    derivedEnvCache: Option[Map[String, String]],
    buildTargetCache: Option[BuildTargetCache],
    diagnosticsFilesCache: Option[Map[String, Path]],
    prevDiagnostics: Map[BuildTargetIdentifier, List[TextDocumentIdentifier]] = Map.empty
)

def tmpRoot = IO.delay(System.getProperty("java.io.tmpdir")).map(Path(_))

def pathToUri(p: Path): SafeUri =
  SafeUri(p.toNioPath.toUri().toString())

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
  val empty: BspState = BspState(None, None, None, None, None)
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
    buildArgs: List[String],
    aqueryArgs: List[String],
    logger: Logger,
    trace: Trace,
    watchDirectories: NonEmptyList[Path]
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

  def readBuildTargets =
    trace.trace("readBuildTargets") {
      readBuildTargetCache(_.readBuildTargets)
    }

  def readScalacOptions =
    trace.trace("readScalacOptions") {
      readBuildTargetCache(_.readScalacOptions)
    }

  def readSources =
    trace.trace("readSources") {
      readBuildTargetCache(_.readSources)
    }

  def readDependencySources =
    trace.trace("readDependencySources") {
      readBuildTargetCache(_.readDependencySources)
    }

  def prevDiagnostics: IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] =
    state.modify(s => s.copy(prevDiagnostics = Map.empty) -> s.prevDiagnostics)

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

  def sourceEnv: IO[Map[String, String]] =
    get(_.sourceEnv)(_ => BspResponseError.NotInitialized)

  def outputBaseFromSource =
    sourceEnv.map(_.apply("output_base")).map(Path(_)).map { p =>
      val fn = p.fileName.toString()
      p.parent.get / s"mezel-$fn"
    }

  def derivedEnv: IO[Map[String, String]] =
    state.get.map(_.derivedEnvCache).flatMap {
      case Some(cached) => IO.pure(cached)
      case None         => 
        workspaceRoot
          .flatMap(mkTasks).flatMap(_.api.info)
          .flatTap(x => state.update(_.copy(derivedEnvCache = Some(x))))
    }

  def derivedExecRoot: IO[Path] =
    derivedEnv.map(_.apply("execution_root")).map(Path(_))

  def diagnosticsFiles =
    state.get.map(_.diagnosticsFilesCache).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        tasks
          .flatMap(_.diagnosticsFiles.map(_.toMap))
          .flatTap(x => state.update(_.copy(diagnosticsFilesCache = Some(x))))
    }

  def cacheFolder: IO[Path] =
    workspaceRoot.flatMap { wsr =>
      tmpRoot.map(_ / ("mezel-cache-" + uriToPath(wsr).fileName.toString))
    }

  def sendNotification[A: Encoder](method: String, params: A): IO[Unit] =
    output.send(Notification("2.0", method, Some(params.asJson)).asJson).void

  def cacheSemanticdbPath(execRoot: Path, cache: Path, targetroot: String): IO[Unit] = {
    val actualDir = execRoot / Path(targetroot)
    val cacheDir = cache / targetroot

    val ls = Files[IO].walk(actualDir).evalFilterNot(Files[IO].isDirectory)
    // Files[IO].list(actualDir, "*.semanticdb")

    Files[IO].exists(cacheDir).flatMap {
      case true  => Files[IO].deleteRecursively(cacheDir)
      case false => IO.unit
    } *>
      ls.evalMap { p =>
        val fileRelativeToTargetRoot = actualDir.absolute.relativize(p.absolute)
        val targetFile = cacheDir / fileRelativeToTargetRoot
        targetFile.parent.traverse_(Files[IO].createDirectories) *>
          Files[IO].copy(p.absolute, targetFile, CopyFlags(CopyFlag.ReplaceExisting))
      }.compile
        .drain
  }

  def mkTasks(rootUri: SafeUri) = {
    outputBaseFromSource.map { ob =>
      Tasks(rootUri, buildArgs, aqueryArgs, logger, trace, ob)
    }
  }

  def tasks = workspaceRoot.flatMap(mkTasks)

  def initalize(msg: InitializeBuildParams): IO[Option[Json]] =
    state.get
      .flatMap { s =>
        if s.workspaceRoot.isDefined then IO.raiseError(new Exception("already initialized"))
        else
          BazelAPI(uriToPath(msg.rootUri), buildArgs, aqueryArgs, logger, trace, None).info.flatMap { env =>
            trace.logger.logInfo(s"running with bazel env $env") >>
              state.update(_.copy(workspaceRoot = Some(msg.rootUri), sourceEnv = Some(env))) <*
              derivedEnv.flatMap(env => trace.logger.logInfo(s"derived env which mezel will run in $env"))
          }
      } >> {
      val gw = GraphWatcher( /*msg.rootUri, mkTasks(msg.rootUri), */ trace.nested("watcher"))
      // TODO find roots automatically
      sup
        .supervise {
          // Files[IO]
          //   .list(uriToPath(msg.rootUri))
          //   .filter(p => !p.fileName.startsWith("bazel-"))
          //   .compile
          //   .toList
          // .map{ p =>
          //   p
          // }
          gw.startWatcher(NonEmptyList.one(uriToPath(msg.rootUri).parent.get) /*watchDirectories*/ ) >>
            sendNotification(
              "buildTarget/didChange",
              DidChangeBuildTarget(
                List(
                  BuildTargetEvent(BuildTargetIdentifier(msg.rootUri), BuildTargetEventKind.Changed)
                )
              )
            )
          // eventStream
          //   .evalMap { wr =>
          //     val combined = (
          //       wr.modified.toList.tupleRight(BuildTargetEventKind.Changed) ++
          //         wr.deleted.toList.tupleRight(BuildTargetEventKind.Deleted) ++
          //         wr.created.toList.tupleRight(BuildTargetEventKind.Created)
          //     ).map { case (label, kind) => BuildTargetEvent(buildIdent(label), kind) }

          //     logger.logInfo(s"producing ${combined} change events") >>
          //       combined.toNel.traverse_ { events =>
          //         sendNotification("buildTarget/didChange", DidChangeBuildTarget(events.toList))
          //       }
          //   }
          //   .compile
          //   .drain
          // }
        }
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
                inverseSourcesProvider = Some(false),
                dependencySourcesProvider = Some(true),
                dependencyModulesProvider = None,
                resourcesProvider = Some(true),
                outputPathsProvider = None,
                buildTargetChangedProvider = Some(true),
                jvmRunEnvironmentProvider = Some(true),
                jvmTestEnvironmentProvider = Some(true),
                canReload = Some(false) // what does this mean?
              )
            ).asJson
          }
        }
    }

  def dependencySources(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      // sup.supervise {
      //   val r = uriToPath(wsr) / "src"
      //   logger.logInfo(s"watching ${r.absolute.toString()}") >>
      //     Files[IO].watch(r).evalMap { e => logger.logInfo(e.toString()) }.compile.drain
      // } *>
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
          cacheFolder.flatMap { cacheDir =>
            derivedExecRoot.flatMap { execRoot =>
              diagnosticsFiles.flatMap { diagnosticsFiles =>
                Files[IO].tempFile
                  .use { tmp =>
                    val writeFiles = Stream.eval(readId).flatMap { xs =>
                      Stream.eval(readScalacOptions).flatMap { scalacOptions =>
                        val scalacOptionsMap = scalacOptions.toMap
                        val bspLabels = xs.map { case (id, _) => id }.toSet

                        val labelStream =
                          trace
                            .traceStream("parse bazel BEP") {
                              parseBEP(tmp)
                            }
                            .mapFilter { x =>
                              for {
                                comp <- x.payload.completed
                                id <- x.id
                                targetCompletedId <- id.id.targetCompleted
                                label <- Some(targetCompletedId.label).filter(bspLabels.contains)
                              } yield (label, comp.success)
                            }

                        labelStream.parEvalMapUnorderedUnbounded { case (label, success) =>
                          val readDiagnostics: IO[diagnostics.TargetDiagnostics] =
                            Files[IO]
                              .readAll(execRoot / diagnosticsFiles(label))
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
                          val writeSemanticDB =
                            if (success) {
                              scalacOptionsMap.get(label).traverse_ { sco =>
                                cacheSemanticdbPath(execRoot, cacheDir, sco.targetroot)
                              }
                            } else IO.unit

                          publishDiag *> writeSemanticDB
                        }
                      }
                    }

                    writeFiles
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
    }
  }

  def sources(targets: List[SafeUri]): IO[Option[Json]] =
    workspaceRoot.flatMap { wsr =>
      derivedExecRoot.flatMap { execRoot =>
        readSources.flatMap { srcs =>
          srcs.toList
            .traverse { case (label, src) =>
              src.sources
                .traverse(x => IO(Path.fromNioPath((execRoot / x).toNioPath.toRealPath())).map(pathToUri))
                .map { xs =>
                  SourcesItem(
                    buildIdent(label),
                    xs.map(SourceItem(_, SourceItemKind.File, false)),
                    Nil
                  )
                }
            }
            .map(ss => Some(SourcesResult(ss).asJson))
        }
      }
    }

  def scalacOptions(targets: List[SafeUri]) =
    workspaceRoot.flatMap { wsr =>
      readScalacOptions.flatMap { scos =>
        derivedExecRoot.flatMap { execRoot =>
          cacheFolder.map { cacheDir =>
            Some {
              ScalacOptionsResult {
                scos.toList.map { case (label, sco) =>
                  val semanticdbDir = cacheDir / sco.targetroot
                  ScalacOptionsItem(
                    buildIdent(label),
                    (sco.scalacopts ++ List(
                      s"-P:semanticdb:targetroot:${semanticdbDir.toString /*cachedSemanticdbPath(sco.targetroot)*/}",
                      s"-P:semanticdb:sourceroot:${semanticdbDir.toString /*cachedSemanticdbPath(sco.targetroot)*/}",
                      "-Xplugin-require:semanticdb",
                      s"-Xplugin:${(execRoot / Path(sco.semanticdbPlugin))}"
                    ) ++ sco.plugins.map(x => s"-Xplugin:${execRoot / x}")).distinct,
                    sco.classpath.map(x => pathToUri(execRoot / x)),
                    (execRoot / sco.outputClassJar).toNioPath.toUri().toString()
                    // semanticdbDir.absolute.toNioPath.toUri().toString // cachedSemanticdbPath(sco.targetroot).absolute.toString()
                  )
                }
              }.asJson
            }
          }
        }
      }
    }

  // only fetch first time
  def buildTargets: IO[Option[Json]] =
    state.get.map(_.buildTargetCache).flatMap {
      case Some(_) => IO.pure(None)
      case None =>
        workspaceRoot.flatMap { wsr =>
          tasks.flatMap { t =>
            derivedExecRoot.flatMap { execRoot =>
              t.buildTargetCache(execRoot).flatMap(btc => state.update(_.copy(buildTargetCache = Some(btc)))) >>
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
                          jars = bt.scalaCompilerClasspath.map(x => pathToUri(execRoot / x)),
                          jvmBuildTarget = Some {
                            JvmBuildTarget(
                              javaHome = Some(pathToUri(execRoot / bt.javaHome)),
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
    }
}
