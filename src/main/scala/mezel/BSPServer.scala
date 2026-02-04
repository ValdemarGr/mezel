package mezel

import com.google.devtools.build.lib.analysis.analysis_v2
import cats.implicits.*
import io.circe.parser.*
import io.circe.*
import fs2.*
import cats.effect.{Trace => _, *}
import fs2.io.file.*
import _root_.io.circe.Json
import cats.data.*
import fs2.concurrent.SignallingRef
import catcheffect.*
import cats.*
import java.nio.file.Paths
import java.net.URI
import com.google.devtools.build.lib.analysis.analysis_v2.PathFragment
import fs2.concurrent.Channel
import _root_.io.bazel.rules_scala.diagnostics.diagnostics
import cats.effect.std.Supervisor
import cats.effect.std.UUIDGen
import scala.reflect.ClassTag
import scala.collection.mutable

enum BspResponseError(val code: Int, val message: String, val data: Option[Json] = None) {
  case NotInitialized extends BspResponseError(-32002, "Server not initialized")

  def responseError: ResponseError = ResponseError(code, message, data)
}

final case class BuildTargetBundle(
    // bt: AspectTypes.BuildTarget,
    scalacOptions: AspectTypes.ScalacOptions,
    sources: AspectTypes.Sources
    // dependencySources: AspectTypes.DependencySources
)

final case class BuildTargetBundleCache(targets: collection.Map[Label, BuildTargetBundle])

final case class BuildTargetCache(
    buildTargets: List[(Label, BuildTargetFiles)]
) {
  def read[A: Decoder](f: BuildTargetFiles => Path): Stream[IO, (Label, A)] =
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
    label: Label,
    scalacOptions: Path,
    sources: Path,
    dependencySources: Path,
    buildTarget: Path
)

final case class ActionQueryResultExtensions(
    aq: analysis_v2.ActionGraphContainer
) {
  val inputMap = mutable.HashMap.from(aq.depSetOfFiles.map(x => x.id -> x.directArtifactIds))
  val targetMap = mutable.HashMap.from(aq.targets.map(x => x.id -> x.label))
  val pathFrags = mutable.HashMap.from(aq.pathFragments.map(p => p.id -> p))
  val arts = mutable.HashMap.from(aq.artifacts.map(x => x.id -> x.pathFragmentId))

  def buildPath(x: analysis_v2.PathFragment): Path = {
    def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
      pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
    }

    go(x).value
  }
}

final case class LabelId(id: Int) extends AnyVal
final case class LabelMap[A](xs: Array[A]) extends AnyVal {
  inline def get(l: LabelId): A = xs(l.id)
  def map[B: ClassTag](f: A => B): LabelMap[B] = LabelMap(xs.map(f))
  inline def set(l: LabelId, a: A): Unit = {
    xs(l.id) = a
  }
  def size: Int = xs.length
  def foreachI(f: (LabelId, A) => Unit): Unit = {
    var i = 0
    while (i < xs.length) {
      f(LabelId(i), xs(i))
      i += 1
    }
  }
  def mapI[B: ClassTag](f: (LabelId, A) => B): LabelMap[B] = {
    val out = LabelMap.fill[B](xs.length, null.asInstanceOf[B])
    var i = 0
    while (i < xs.length) {
      out.set(LabelId(i), f(LabelId(i), xs(i)))
      i += 1
    }
    out
  }
  def keys: Array[LabelId] = xs.indices.map(LabelId(_)).toArray
  def pairs: Array[(LabelId, A)] = mapI[(LabelId, A)]((aid, a) => (aid, a)).xs
}
object LabelMap {
  def fill[B: ClassTag](
      size: Int,
      element: B
  ): LabelMap[B] = LabelMap(Array.fill[B](size)(element))
}

final case class DependencyGraph(
    labelLookup: LabelMap[Label],
    reverse: collection.Map[Label, LabelId],
    children: LabelMap[Array[LabelId]]
) {
  val parents: LabelMap[Array[LabelId]] = {
    val builder = LabelMap.fill[Option[mutable.ArrayBuilder[LabelId]]](
      labelLookup.size,
      None
    )
    children.foreachI { (lid, cs) =>
      cs.foreach { c =>
        val ab = builder.get(c).getOrElse {
          val out = mutable.ArrayBuilder.make[LabelId]
          out.sizeHint(8)
          builder.set(c, Some(out))
          out
        }
        ab.addOne(lid)
      }
    }
    builder.map(_.map(_.result()).getOrElse(Array.empty))
  }

  def ancestors_(label: LabelId): collection.Set[LabelId] = {
    val visited = mutable.Set.empty[LabelId]
    visited.sizeHint(16)
    val parents0 = parents.get(label)
    val s = mutable.Stack.from(parents0)
    while s.nonEmpty do {
      val x = s.pop()
      if !visited.contains(x) then {
        visited += x
        s.addAll(parents.get(x))
      }
    }
    visited
  }

  def ancestors(label: Label): Iterator[Label] = {
    reverse.get(label) match {
      case Some(lid) => ancestors_(lid).iterator.map(labelLookup.get(_))
      case None      => Iterator.empty
    }
  }
}
object DependencyGraph {
  def make(children: collection.Map[Label, List[Label]]): DependencyGraph = {
    val lm = LabelMap[Label](children.keys.toArray)
    val reverse = mutable.HashMap.from(lm.pairs.map(_.swap))
    val cs = lm.mapI { (_, label) =>
      val childLabels = children.getOrElse(label, Nil)
      childLabels.map(cl => reverse(cl)).toArray
    }
    DependencyGraph(lm, reverse, cs)
  }
}

// final case class DependencyGraph(
//     children: Map[Label, List[Label]]
// ) {
//   val nonRoots = children.values.flatten.toSet

//   val roots = children.keySet -- nonRoots

//   val leaves: Set[Label] = children.toList.collect {
//     // if all the children of node k do not occur in the tree
//     // then k is a leaf
//     // we can do this because we know that all targetable labels
//     // occur in the tree children
//     case (k, vs) if vs.forall(v => !children.contains(v)) => k
//   }.toSet

//   val parents =
//     children.toList
//       .flatMap { case (p, cs) => cs.map(c => c -> p) }
//       .groupMap { case (c, _) => c } { case (_, p) => p } ++ roots.map(_ -> Nil).toMap

//   val ancestors: Map[Label, Set[Label]] = {
//     def go(label: Label): State[Map[Label, Set[Label]], Set[Label]] =
//       State.get[Map[Label, Set[Label]]].flatMap { m =>
//         m.get(label) match {
//           case Some(x) => State.pure(x)
//           case None =>
//             val ps = parents(label)
//             ps.foldMapA(go).flatMap { transitives =>
//               val comb = transitives ++ ps
//               State.modify[Map[Label, Set[Label]]](_ + (label -> comb)).as(comb + label)
//             }
//         }
//       }

//     leaves.toList.foldMapA(go).runS(Map.empty).value
//   }
// }

final case class BspState(
    initReq: Option[InitializeBuildParams],
    sourceEnv: Option[Map[String, String]],
    prevDiagnostics: Map[BuildTargetIdentifier, List[TextDocumentIdentifier]] = Map.empty
) {
  def workspaceRoot: Option[SafeUri] = initReq.map(_.rootUri)
}

def tmpRoot = IO.delay(System.getProperty("java.io.tmpdir")).map(Path(_))

def pathToUri(p: Path): SafeUri =
  SafeUri(p.toNioPath.toUri().toString())

def pathFullToUri(root: SafeUri, p: Path): SafeUri =
  SafeUri((uriToPath(root) / p).toNioPath.toUri().toString())

def buildIdent(label: Label): BuildTargetIdentifier =
  BuildTargetIdentifier(SafeUri(label.value))

def uriToPath(suri: SafeUri): Path = Path.fromNioPath(Paths.get(new URI(suri.value)))

object BspState {
  val empty: BspState = BspState(None, None)
}

final case class BspCacheKeys(
    derivedEnv: CacheKey[Map[String, String]],
    buildTargetCache: CacheKey[BuildTargetCache],
    diagnosticsFiles: CacheKey[Map[Label, Path]],
    dependencyGraph: CacheKey[DependencyGraph],
    newRules: CacheKey[Boolean],
    buildTargetBundleCache: CacheKey[BuildTargetBundleCache]
)

object BspCacheKeys {
  def make: IO[BspCacheKeys] = (
    CacheKey.make[Map[String, String]],
    CacheKey.make[BuildTargetCache],
    CacheKey.make[Map[Label, Path]],
    CacheKey.make[DependencyGraph],
    CacheKey.make[Boolean],
    CacheKey.make[BuildTargetBundleCache]
  ).mapN(BspCacheKeys.apply)
}

class BspServerOps(
    state: SignallingRef[IO, BspState],
    sup: Supervisor[IO],
    output: Channel[IO, Json],
    buildArgs: List[String],
    aqueryArgs: List[String],
    logger: Logger,
    trace: Trace,
    cache: Cache,
    cacheKeys: BspCacheKeys,
    ct: CancellableTask,
    verbose: Verbose,
    inMemory: Boolean
)(implicit R: Raise[IO, BspResponseError]) {
  import _root_.io.circe.syntax.*

  def cached[A](name: String)(key: BspCacheKeys => CacheKey[A])(run: => IO[A]): IO[A] =
    cache.cached(key(cacheKeys))(trace.logger.logInfo(s"cache miss for $name, running action") *> run)

  def derivedEnv: IO[Map[String, String]] =
    cached("derivedEnv")(_.derivedEnv) {
      bazelAPI.flatMap(_.info)
    }

  def derivedExecRoot: IO[Path] =
    derivedEnv.map(_.apply("execution_root")).map(Path(_))

  def buildTargetCache: IO[BuildTargetCache] =
    cached("buildTargetCache")(_.buildTargetCache) {
      derivedExecRoot.flatMap(execRoot => tasks.flatMap(_.buildTargetCache(execRoot)))
    }

  def compileBuildTargets: IO[BuildTargetBundleCache] = {
    val make = for {
      btc <- buildTargetCache
      scalacOptions <- btc.readScalacOptions.compile.to(Array)
      sources <- btc.readSources.compile.to(Array)
    } yield {
      val scalacOptionsMap = mutable.HashMap.from(scalacOptions)
      val sourcesMap = mutable.HashMap.from(sources)
      val res = btc.buildTargets.view.map { case (label, _) =>
        val bundle = BuildTargetBundle(
          scalacOptions = scalacOptionsMap(label),
          sources = sourcesMap(label)
        )
        label -> bundle
      }
      BuildTargetBundleCache(mutable.HashMap.from(res))
    }

    if inMemory then {
      cached("compileBuildTargets")(_.buildTargetBundleCache) {
        make
      }
    } else {
      make
    }
  }

  // for now we just compile
  // TODO, we can stream the results out into json
  def readBuildTargetCache[A](f: BuildTargetCache => Stream[IO, (Label, A)]): IO[List[(Label, A)]] = {
    val fa = for {
      curr <- Stream.eval(state.get)
      btc <- Stream.eval(buildTargetCache)
      res <- f(btc)
    } yield res

    fa.compile.toList
  }

  def readId = readBuildTargetCache(x => Stream.emits(x.buildTargets))

  def readBuildTargets =
    trace.trace("readBuildTargets") {
      readBuildTargetCache(_.readBuildTargets)
    }

  def readDependencySources =
    trace.trace("readDependencySources") {
      readBuildTargetCache(_.readDependencySources)
    }

  def dependencyGraph: IO[DependencyGraph] =
    cached("dependencyGraph")(_.dependencyGraph) {
      readBuildTargets.map(bts => DependencyGraph.make(collection.Map.from(bts.map { case (k, v) => k -> v.deps })))
    }

  def activeAspect: IO[String] =
    cached("activeRules")(_.newRules) {
      bazelAPI.flatMap(_.runUnitTask("query", "@io_bazel_rules_scala_config//:all")).flatMap {
        case 0 =>
          logger.logInfo("found io_bazel_rules_scala_config, using old aspect").as(false)
        case n =>
          logger.logInfo("did not find io_bazel_rules_scala_config, attempting `rules_scala`") *>
            bazelAPI.flatMap(_.runUnitTask("query", "@rules_scala//:all")).flatMap {
              case 0 =>
                logger.logInfo("found rules_scala, using new aspect").as(true)
              case n =>
                logger
                  .logWarn("did not find rules_scala or io_bazel_rules_scala_config, defaulting to old aspect?")
                  .as(false)
            }
      }
    }.map {
      case true  => "@mezel//aspects:aspect_new_buildrules.bzl%mezel_aspect"
      case false => "@mezel//aspects:aspect.bzl%mezel_aspect"
    }

  def bazelAPI: IO[BazelAPI] = {
    workspaceRoot.flatMap { root =>
      outputBaseFromSource.map { outputBase =>
        BazelAPI(uriToPath(root), buildArgs, aqueryArgs, logger, trace, Some(outputBase))
      }
    }
  }

  def prevDiagnostics: IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] =
    state.get.map(_.prevDiagnostics)

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

  def derivedWorkspace: IO[Path] =
    derivedEnv.map(_.apply("workspace")).map(Path(_))

  def diagnosticsFiles =
    cached("diagnosticsFiles")(_.diagnosticsFiles) {
      tasks
        .flatMap(_.diagnosticsFiles.map(_.toMap))
        .flatTap(m => verbose.trace(trace.logger.logInfo(s"diagnostics files are $m")))
    }

  def cacheFolder: IO[Path] =
    workspaceRoot.flatMap { wsr =>
      tmpRoot.map(_ / ("mezel-cache-" + uriToPath(wsr).fileName.toString))
    }

  def sendNotification[A: Encoder](method: String, params: A): IO[Unit] =
    output.send(Notification("2.0", method, Some(params.asJson)).asJson).void

  def semanticdbCachePath(targetroot: String): IO[Path] =
    derivedExecRoot.map(_ / "semanticdb" / targetroot)

  def cacheSemanticdbPath(execRoot: Path, targetroot: String): IO[Unit] =
    semanticdbCachePath(targetroot).flatMap { cacheDir =>
      val actualDir = execRoot / Path(targetroot)

      val ls = Files[IO].walk(actualDir).evalFilterNot(Files[IO].isDirectory)

      // strategy is to first list all current files in cache dir
      // then atomically move new semantic db files into the cache dir
      // Files[IO].tempDirectory.use { tmpDir =>
      // val tmpFile = tmpDir / "sem"
      ls.evalMap { p =>
        val fileRelativeToTargetRoot = actualDir.absolute.relativize(p.absolute)
        val targetFile = cacheDir / fileRelativeToTargetRoot
        val fa = targetFile.parent.traverse_(Files[IO].createDirectories) *>
          Files[IO].copy(p.absolute, targetFile, CopyFlags(CopyFlag.ReplaceExisting))
        // Files[IO].copy(p.absolute, tmpFile, CopyFlags(CopyFlag.ReplaceExisting))// *>
        // Files[IO].move(tmpFile, targetFile, CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.AtomicMove))

        fa
      }.compile
        .drain
    }
  // }

  def mkTasks(rootUri: SafeUri) = {
    activeAspect.flatMap { aspect =>
      bazelAPI.map { api =>
        Tasks(trace, api, aspect)
      }
    }
  }

  def convertDiagnostic(
      lg: Logger,
      root: SafeUri,
      target: BuildTargetIdentifier,
      tds: diagnostics.TargetDiagnostics
  ): IO[List[PublishDiagnosticsParams]] = {
    // If the path contains "no file" then we cannot convert it to a URI
    // so we just ignore and log it
    // https://github.com/ValdemarGr/mezel/issues/21
    tds.diagnostics.groupBy(_.path).toList.flatTraverse { case (p, fds) =>
      if (p == "workspace-root://virtual-file") {
        verbose
          .verbose {
            lg.logWarn("skipping virtual file")
          }
          .as(Nil)
      } else {

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
        val pdp = PublishDiagnosticsParams(
          textDocument = TextDocumentIdentifier(pathFullToUri(root, Path(fixedTextDocumentRef))),
          buildTarget = target,
          originId = None,
          diagnostics = ds,
          reset = true
        )

        import _root_.io.circe.syntax._
        if (p.contains("no file")) {
          lg.logWarn(
            s"path $p contains \"no file\", which the Scala compiler can do sometimes. Ignoring the diagnostic, here is a dump of the output: ${pdp.asJson.noSpaces}"
          ).as(Nil)
        } else IO.pure(List(pdp))
      }
    }
  }

  def tasks = workspaceRoot.flatMap(mkTasks)

  def initalize(msg: InitializeBuildParams): IO[Option[Json]] = {
    val makeEnv =
      BazelAPI(uriToPath(msg.rootUri), buildArgs, aqueryArgs, logger, trace, None).info.flatTap { env =>
        trace.logger.logInfo(s"running with bazel env $env")
      }

    state.get
      .flatMap { s =>
        if (s.workspaceRoot.isDefined) IO.raiseError(new Exception("already initialized"))
        else makeEnv.flatMap(env => state.update(_.copy(initReq = Some(msg), sourceEnv = Some(env))))
      } >> {
      trace
        .nested("watcher") {
          val gw = GraphWatcher(trace)
          sup
            .supervise {
              val didChange =
                cache.clear *>
                  sendNotification(
                    "buildTarget/didChange",
                    DidChangeBuildTarget(
                      List(
                        BuildTargetEvent(BuildTargetIdentifier(msg.rootUri), BuildTargetEventKind.Created)
                      )
                    )
                  )
              lazy val go: IO[Unit] =
                gw.startWatcher(NonEmptyList.one(uriToPath(msg.rootUri)) /*watchDirectories*/ )
                  .evalTap { _ =>
                    trace.logger.logInfo(s"something changed, clearing all the caches") *> didChange
                  }
                  .handleErrorWith { e =>
                    Stream.eval {
                      trace.logger.logWarn(s"watcher failed with $e, reloading to be consistent") *>
                        didChange *>
                        go
                    }
                  }
                  .compile
                  .drain

              go
            }
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
                canReload = Some(false),
                jvmCompileClasspathProvider = Some(true)
              )
            ).asJson
          }
        }
    }
  }

  def dependencySources(targets: List[SafeUri]): IO[Option[Json]] =
    for {
      wsr <- workspaceRoot
      execRoot <- derivedExecRoot
      ds <- readDependencySources
    } yield Some {
      DependencySourcesResult {
        DependencySourcesItem(BuildTargetIdentifier(SafeUri("workspace")), Nil) ::
          ds.toList.map { case (label, ds) =>
            DependencySourcesItem(
              buildIdent(label),
              ds.sourcejars.map(x => pathToUri(execRoot / x)).toList
            )
          }
      }.asJson
    }

  val compilationTaskKey = "compile"

  def compile(targets: List[SafeUri]): IO[Option[Json]] = {
    val doCompile: IO[Unit] = for {
      wsr <- workspaceRoot
      t <- tasks
      prevDiagnostics <- prevDiagnostics
      cacheDir <- cacheFolder
      execRoot <- derivedExecRoot
      diagnosticsFiles <- diagnosticsFiles
      xs <- readId
      dg <- dependencyGraph
      _ <- verbose.debug(trace.logger.logInfo(s"dg ${dg}"))
      _ <- verbose.debug(trace.logger.logInfo(s"ancestor map ${dg.ancestors}"))
      btbc <- compileBuildTargets
      _ <- {
        def diagnosticsProcess(diagnosticsProtoFile: Path): Stream[IO, Unit] = {
          val nonEmptySources = mutable.Set.from {
            btbc.targets.collect {
              case (k, v) if v.sources.sources.nonEmpty => k
            }
          }
          val bspLabels = mutable.Set.from(xs.map { case (id, _) => id })

          def diagnosticEffect(bti: BuildTargetIdentifier, xs: List[PublishDiagnosticsParams]): IO[Unit] =
            IO.uncancelable { _ =>
              val prevHere = prevDiagnostics.get(bti).toList.flatten
              val toClear = prevHere.toSet -- xs.map(_.textDocument)

              state.update(s => s.copy(prevDiagnostics = s.prevDiagnostics - bti)) *>
                xs.traverse(x => publishDiagnostics(x) *> cacheDiagnostic(bti, x.textDocument)) *>
                toClear.toList
                  .map(PublishDiagnosticsParams(_, bti, None, Nil, true))
                  .traverse_(publishDiagnostics)
            }

          val labelStream =
            Stream.eval(IO.ref[Set[Label]](Set.empty)).flatMap { eliminatedRef =>
              trace
                .traceStream("parse bazel BEP") {
                  parseBEP(diagnosticsProtoFile)
                }
                .mapFilter { x =>
                  for {
                    comp <- x.payload.completed
                    id <- x.id
                    targetCompletedId <- id.id.targetCompleted
                    label <- Some(Label.parse(targetCompletedId.label)).filter(bspLabels.contains)
                  } yield (label, comp.success)
                }
                .evalFilter {
                  case (label, true) => verbose.debug(trace.logger.logInfo(s"$label completed")) *> IO.pure(true)
                  case (label, false) =>
                    verbose.debug(trace.logger.logInfo(s"$label failed")) *>
                      eliminatedRef.get.flatMap { eliminated =>
                        // we have not been eliminated, but we have failed
                        // eliminate all our ancestors, but keep this target
                        if (!eliminated.contains(label)) {
                          val anc = dg.ancestors(label)
                          verbose.debug {
                            trace.logger.logInfo(s"$label is not eliminated") *>
                              trace.logger.logInfo(s"$label: eliminating all ancestors ${anc}")
                          } *> eliminatedRef.update(_ ++ anc).as(true)
                        }
                        // we have been eliminated, a child of ours has failed
                        // clear all previous diagnostics for this target
                        else {
                          verbose.debug(trace.logger.logInfo(s"$label is eliminated, skipping")) *>
                            diagnosticEffect(buildIdent(label), Nil).as(false)
                        }
                      }
                }
            }

          labelStream.parEvalMapUnorderedUnbounded { case (label, success) =>
            def readDiagnostics: IO[Option[diagnostics.TargetDiagnostics]] =
              diagnosticsFiles.get(label) match {
                case None =>
                  trace.logger.logInfo(s"no diagnostics file declared for $label, this is likely a bug").as(None)
                case Some(x) =>
                  val p = execRoot / x

                  Files[IO].exists(p).flatMap {
                    case false =>
                      // Okay, as a heuristic lets check if there is a jar
                      // for reasons unknown to me, sometimes there are no diagnostics (remote doesn't pull diagnostics?)
                      val p2 = p.toString().replace(".diagnosticsproto", ".jar")
                      Files[IO]
                        .exists(Path(p2))
                        .flatMap {
                          // There's an output but no diagnostics file, assume everything went fine
                          case true => IO.unit
                          case false =>
                            verbose.verbose {
                              trace.logger
                                .logWarn(
                                  s"no diagnostics file for $label, this can cause a degraded experience or may just be a skipped build target, looked at \"${p}\""
                                )
                            }
                        }
                        .as(None)
                    case true =>
                      Files[IO]
                        .readAll(p)
                        .through(fs2.io.toInputStream[IO])
                        .evalMap(is => IO.interruptible(diagnostics.TargetDiagnostics.parseFrom(is)))
                        .compile
                        .lastOrError
                        .map(Some(_))
                  }
              }

            // if there are no diagnostics for a target, clear the diagnostics
            // if there are diagnostics for a target, publish them
            def publishDiag: IO[Unit] = readDiagnostics.flatMap { tdOpt =>
              val bti = buildIdent(label)
              tdOpt.toList
                .flatTraverse(convertDiagnostic(trace.logger, wsr, bti, _))
                .flatMap(diagnosticEffect(bti, _))
            }

            // if success then there are semanticdb files to publish
            // if !success then use cached semanticdb files
            def writeSemanticDB =
              if (success) {
                btbc.targets.get(label).map(_.scalacOptions).traverse_ { sco =>
                  Files[IO].exists(execRoot / Path(sco.targetroot)).flatMap {
                    case false =>
                      trace.logger.logWarn(s"no semanticdb targetroot for $label, this is likely a bug")
                    case true => cacheSemanticdbPath(execRoot, sco.targetroot)
                  }
                }
              } else IO.unit

            IO.uncancelable { _ =>
              IO.whenA(nonEmptySources.contains(label)) {
                publishDiag *> writeSemanticDB
              }
            }
          }
        }

        Files[IO].tempFile.use { tmp =>
          diagnosticsProcess(tmp)
            .merge(Stream.eval(t.buildAll(s"--build_event_binary_file=${tmp.absolute.toString()}")))
            .compile
            .drain
        }
      }
    } yield ()

    val loggedProgram: IO[Unit] =
      for {
        id <- UUIDGen.randomString[IO]
        _ <- trace.logger.logInfo(s"beginning compilation task with id $id")
        prg = {
          trace.logger.logInfo(s"running compilation task with id $id") *>
            doCompile <*
            trace.logger.logInfo(s"finished compiling with id $id")
        }.onCancel(trace.logger.logInfo(s"cancelled compilation with id $id"))
        _ <- ct.start(trace, compilationTaskKey, prg)
      } yield ()

    loggedProgram.as {
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
    for {
      wsr <- workspaceRoot
      execRoot <- derivedExecRoot
      btbc <- compileBuildTargets
      out <- btbc.targets.toList
        .traverse { case (label, btb) =>
          btb.sources.sources
            .traverse { x =>
              val p = execRoot / x.path
              val t = if (x.isDirectory) SourceItemKind.Directory else SourceItemKind.File
              val generated = !x.isSource
              Files[IO]
                .exists(p)
                .flatMap {
                  case false => trace.logger.logWarn(s"source file does not exist yet, maybe it is generated? $p").as(p)
                  case true  => IO(Path.fromNioPath(p.toNioPath.toRealPath()))
                }
                .flatMap(y => IO(pathToUri(y)))
                .map(uri => SourceItem(uri, t, generated))
            }
            .map(xs => SourcesItem(buildIdent(label), xs, List(wsr)))
        }
        .map(ss =>
          Some(SourcesResult(SourcesItem(BuildTargetIdentifier(SafeUri("workspace")), Nil, List(wsr)) :: ss).asJson)
        )
    } yield out

  def scalacOptions(targets: List[SafeUri]): IO[Option[Json]] =
    for {
      wsr <- workspaceRoot
      btbc <- compileBuildTargets
      execRoot <- derivedExecRoot
      cacheDir <- cacheFolder
      workspace <- derivedWorkspace
      ob <- outputBaseFromSource
      options <- btbc.targets.toList.traverse { case (label, btb) =>
        val sco = btb.scalacOptions
        semanticdbCachePath(sco.targetroot).map { semanticdbDir =>
          val semanticDBFlags =
            if (sco.compilerVersion.major === "2") {
              List(
                s"-P:semanticdb:sourceroot:${sco.workspaceRoot.map(ob / _).getOrElse(workspace).toString}",
                s"-P:semanticdb:targetroot:${execRoot / Path(sco.targetroot)
                  // semanticdbDir.toString
                  }",
                "-Xplugin-require:semanticdb",
                s"-Xplugin:${(execRoot / Path(sco.semanticdbPlugin))}"
              )
            } else
              List(
                "-Xsemanticdb",
                // This is from the docs
                "-semanticdb-target",
                semanticdbDir.toString
              )

          val j = (execRoot / sco.outputClassJar)
          ScalacOptionsItem(
            buildIdent(label),
            (sco.scalacopts ++ semanticDBFlags ++ sco.plugins.map(x => s"-Xplugin:${execRoot / x}")).distinct,
            sco.classpath.map(x => pathToUri(execRoot / x)),
            j.toNioPath.toUri().toString()
          )
        }
      }
    } yield Some(ScalacOptionsResult(options).asJson)

  def jvmCompileCLasspath(targets: List[SafeUri]): IO[Option[Json]] =
    for {
      execRoot <- derivedExecRoot
      btbc <- compileBuildTargets
    } yield JvmCompileClasspathResult {
      btbc.targets.toList.map { case (label, btb) =>
        val sco = btb.scalacOptions
        JvmCompileClasspathItem(
          target = buildIdent(label),
          classpath = sco.classpath.map(x => pathToUri(execRoot / x))
        )
      }
    }.asJson.some

  def buildTargets: IO[Option[Json]] =
    for {
      wsr <- workspaceRoot
      execRoot <- derivedExecRoot
      bts <- readBuildTargets
      ob <- outputBaseFromSource
    } yield WorkspaceBuildTargetsResult {
      BuildTarget(
        id = BuildTargetIdentifier(SafeUri("workspace")),
        displayName = Some("workspace"),
        baseDirectory = Some(wsr),
        tags = Nil,
        languageIds = Nil,
        dependencies = Nil,
        capabilities = BuildTargetCapabilities(
          canCompile = Some(false),
          canTest = Some(false),
          canRun = Some(false),
          canDebug = Some(false)
        ),
        dataKind = None,
        data = None
      ) ::
        bts.toList.map { case (label, bt) =>
          val dir = bt.workspaceRoot match {
            case None    => pathFullToUri(wsr, Path(bt.directory))
            case Some(x) => pathToUri(ob / x / Path(bt.directory))
          }

          BuildTarget(
            id = buildIdent(label),
            displayName = Some(label.value),
            baseDirectory = Some(dir),
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
    }.asJson.some
}
