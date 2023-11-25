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

final case class BspState(
    workspaceRoot: Option[SafeUri]
) derives Empty

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
          "--output_groups=bsp_info"
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

  def buildTargetFile[A: Decoder](uri: SafeUri)(f: BuildTargetFiles => Path): IO[List[(String, A)]] =
    buildTargetFiles(uri).flatMap { bts =>
      bts.traverse { bt =>
        Files[IO].readAll(f(bt)).through(fs2.text.utf8.decode).compile.string.flatMap { str =>
          IO.fromEither(_root_.io.circe.parser.decode[A](str)) tupleLeft bt.label
        }
      }
    }

  def buildTargets(uri: SafeUri): IO[List[(String, AspectTypes.BuildTarget)]] =
    buildTargetFile[AspectTypes.BuildTarget](uri)(_.buildTarget)

  def scalacOptions(uri: SafeUri): IO[List[(String, AspectTypes.ScalacOptions)]] =
    buildTargetFile[AspectTypes.ScalacOptions](uri)(_.scalacOptions)

  def sources(uri: SafeUri): IO[List[(String, AspectTypes.Sources)]] =
    buildTargetFile[AspectTypes.Sources](uri)(_.sources)

  def dependencySources(uri: SafeUri): IO[List[(String, AspectTypes.DependencySources)]] =
    buildTargetFile[AspectTypes.DependencySources](uri)(_.dependencySources)

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
  val empty: BspState = Empty[BspState].empty
}

class BspServerOps(state: SignallingRef[IO, BspState])(implicit R: Raise[IO, BspResponseError]) {
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

  // todo optimize
  def dependencySources(targets: List[SafeUri]): IO[Option[Json]] = {
    workspaceRoot.flatMap { wsr =>
      Tasks.dependencySources(wsr).map { ds =>
        val r = uriToPath(wsr)
        Some {
          DependencySourcesResult {
            ds.map { case (label, ds) =>
              DependencySourcesItem(
                BuildTargetIdentifier(pathToUri(Path(label))),
                ds.sourcejars.map(x => pathToUri(r / Path(x))).toList
              )
            }
          }.asJson
        }
      }
    }
    // regen.flatMap { qo =>
    //   workspaceRoot.map(uriToPath).flatMap { root =>
    //     val argSrcs =
    //       qo.aqr.actions.flatMap(_.arguments.drop(1).filter(x => x.endsWith("-src.jar") || x.endsWith("-sources.jar"))).distinct.toList

    //     val pathFrags = qo.aqr.pathFragments.map(p => p.id -> p).toMap
    //     val arts = qo.aqr.artifacts.map(x => x.id -> x.pathFragmentId)
    //     def buildPath(x: analysis_v2.PathFragment): Path = {
    //       def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
    //         pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
    //       }

    //       go(x).value
    //     }

    //     val uniqueArtifacts = qo.aqr.artifacts.map(_.id)

    //     val artifactToPath: Map[Int, PathFragment] = arts.map { case (id, p) => id -> pathFrags(p) }.toMap
    //     val relevantPathRoots = uniqueArtifacts.reverse.zipWithIndex.mapFilter { case (x, i) =>
    //       if (i % 50 == 0) println(s"artifact ${i}/ ${uniqueArtifacts.size}")
    //       val r = artifactToPath(x)
    //       if (r.label.endsWith("-src.jar") || r.label.endsWith("-sources.jar")) {
    //         Some(r)
    //       } else None
    //     }

    //     val outputSrcs = relevantPathRoots.map(buildPath).distinct.map(root / _)

    //     val paths = (argSrcs.map(x => root / Path(x)) ++ outputSrcs).distinct

    //     paths.filterA(Files[IO].exists).map { existing =>
    //       val out = Some {
    //         DependencySourcesResult {
    //           targets.map { t =>
    //             DependencySourcesItem(
    //               BuildTargetIdentifier(t),
    //               existing.map(pathToUri)
    //             )
    //           }
    //         }.asJson
    //       }

    //       println("done with dependency sources")

    //       out
    //     }
    //   }
    // }
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
      Tasks.sources(wsr).map { srcs =>
        val r = uriToPath(wsr)
        Some {
          SourcesResult {
            srcs.map { case (label, src) =>
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
    // regen.flatMap { qo =>
    //   workspaceRoot.map(uriToPath).map { root =>
    //     Some {
    //       SourcesResult {
    //         targets.map { uri =>
    //           val label = qo.pathToLabel(uriToPath(uri))
    //           val (qt, _) = qo.sourceTargets(label)
    //           val act = qo.scalacActionsMap(label)
    //           val srcs = act.arguments.dropWhile(_ =!= "--Files").tail.takeWhile(x => !x.startsWith("--"))
    //           SourcesItem(
    //             BuildTargetIdentifier(uri),
    //             srcs.map(x => root / Path(x)).map(pathToUri).map(SourceItem(_, SourceItemKind.File, false)).toList,
    //             Nil
    //           )
    //         }
    //       }.asJson
    //     }
    //   }
    // }

  def scalacOptions(targets: List[SafeUri]) = {
    workspaceRoot.flatMap { wsr =>
      Tasks.scalacOptions(wsr).map { scos =>
        val r = uriToPath(wsr)
        Some {
          ScalacOptionsResult {
            scos.map { case (label, sco) =>
              ScalacOptionsItem(
                BuildTargetIdentifier(pathToUri(Path(label))),
                sco.scalacopts ++ List(
                  s"-P:semanticdb:targetroot:${sco.targetroot}",
                  "-Xplugin-require:semanticdb",
                  s"-Xplugin:${r / "bazel-gateway" / Path(sco.semanticdbPlugin)}"
                ),
                sco.classpath.map(x => pathToUri(r / Path(x))),
                sco.semanticdbPlugin
              )
            }
          }.asJson
        }
      }
    }
    // regen.flatMap { qo =>
    //   workspaceRoot.map(uriToPath).map { root =>
    //     Some {
    //       ScalacOptionsResult {
    //         targets.map { uri =>
    //           val label = qo.pathToLabel(uriToPath(uri))
    //           val (qt, sdb) = qo.sourceTargets(label)
    //           val act = qo.scalacActionsMap(label)
    //           val args = act.arguments.dropWhile(_ =!= "--ScalacOpts").tail.takeWhile(x => !x.startsWith("--")) ++ List(
    //             s"-P:semanticdb:sourceroot:${sdb}"
    //           )
    //           // consider getting this from a query(deps) -> aquery(output jars)
    //           val classpath = act.arguments.dropWhile(_ =!= "--Classpath").tail.takeWhile(x => !x.startsWith("--"))
    //           ScalacOptionsItem(
    //             BuildTargetIdentifier(uri),
    //             (args.toList ++ List(
    //               "-Xplugin-require:semanticdb",
    //               "-Xplugin:/home/valde/.cache/bloop/semanticdb/org.scalameta.semanticdb-scalac_2.13.12.4.8.3/semanticdb-scalac_2.13.12-4.8.3.jar"
    //             )).distinct,
    //             classpath.toList.map(x => pathToUri(root / x)),
    //             sdb
    //           )
    //         }
    //       }.asJson
    //     }
    //   }
    // }
  }

  def buildTargets: IO[Option[Json]] = {
    workspaceRoot.flatMap(Tasks.buildTargets).map { bts =>
      val targets = bts.map { case (label, bt) =>
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

      // regen.map { qo =>
      //   println(s"missing from query: ${qo.missingFromQuery}\nmissing from aquery: ${qo.missingFromAquery}")
      //   println(s"missing semanticsdb: ${qo.missingSemanticsDb}")

      //   val targets = qo.sourceTargets.toList.map { case (k, (qt, _)) =>
      //     val scalaDeps: Seq[SafeUri] =
      //       qt.deps.mapFilter(qo.scalaQueryTargets.get).map(x => pathToUri(x.path))
      //     BuildTarget(
      //       id = BuildTargetIdentifier(pathToUri(qt.path)),
      //       displayName = Some(qt.path.fileName.toString),
      //       baseDirectory = Some(pathToUri(qt.path)),
      //       tags = List("library"),
      //       languageIds = List("scala"),
      //       dependencies = scalaDeps.map(BuildTargetIdentifier(_)).toList,
      //       capabilities = BuildTargetCapabilities(
      //         canCompile = Some(true),
      //         canTest = Some(false),
      //         canRun = Some(false),
      //         canDebug = Some(false)
      //       ),
      //       dataKind = Some("scala"),
      //       data = Some {
      //         ScalaBuildTarget(
      //           scalaOrganization = "org.scala-lang",
      //           scalaVersion = "2.13.12",
      //           scalaBinaryVersion = "2.13",
      //           platform = 1,
      //           jars = List(
      //             "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_library/io_bazel_rules_scala_scala_library.stamp/scala-library-2.13.12.jar",
      //             "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_reflect/io_bazel_rules_scala_scala_reflect.stamp/scala-reflect-2.13.12.jar",
      //             "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_compiler/io_bazel_rules_scala_scala_compiler.stamp/scala-compiler-2.13.12.jar"
      //           ).map(SafeUri(_)),
      //           jvmBuildTarget = Some {
      //             JvmBuildTarget(
      //               javaHome = Some(SafeUri("file:///nix/store/7c2ksq340xg06jmym46fzd0rbxphlzm3-openjdk-19.0.2+7/lib/openjdk/")),
      //               javaVersion = None
      //             )
      //           }
      //         )
      //       }
      //     )
      //   }

      //   Some {
      //     WorkspaceBuildTargetsResult(targets = targets).asJson
      //   }
      // }
    }
  }
}
