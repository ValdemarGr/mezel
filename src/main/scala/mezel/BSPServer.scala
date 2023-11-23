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

final case class QueryTarget(
    rule: build.Rule,
    path: Path
) {
  lazy val deps = rule.attribute.filter(_.name === "deps").flatMap(_.stringListValue)

  lazy val sources = rule.attribute.filter(_.name === "srcs").flatMap(_.stringListValue)
}

def pathToUri(p: Path): SafeUri = SafeUri(s"file://${p.absolute.toString}")

def uriToPath(suri: SafeUri): Path = Path.fromNioPath(Paths.get(new URI(suri.value)))

final case class QueryOutput(
    qr: build.QueryResult,
    aqr: analysis_v2.ActionGraphContainer
) {
  val excludeLabels = Set("@io_bazel_rules_scala")

  lazy val rules = qr.target.mapFilter(_.rule)

  lazy val labelToPath = rules.mapFilter(x => x.location.map(loc => (x.name, Path(loc).parent.get))).toMap
  lazy val pathToLabel = labelToPath.map(_.swap)

  lazy val scalaQueryTargets: Map[String, QueryTarget] =
    rules.mapFilter(x => labelToPath.get(x.name).map(p => x.name -> QueryTarget(x, p))).toMap

  lazy val targetIdToLabel = aqr.targets.map(x => x.id -> x.label).toMap

  lazy val compileJarActions = aqr.actions
    .filter(_.mnemonic === "CreateCompileJar")
    .map(x => targetIdToLabel(x.targetId) -> x)
    .toMap

  lazy val scalacActions = aqr.actions.filter(_.mnemonic === "Scalac")

  lazy val scalacActionsMap = scalacActions.map(x => targetIdToLabel(x.targetId) -> x).toMap

  lazy val scalacTargets = scalacActions
    .map(x => targetIdToLabel(x.targetId))
    .toSet
    .filterNot(x => excludeLabels.exists(x.startsWith))

  lazy val semanticsDbArg = scalacActions.mapFilter { x =>
    val l = targetIdToLabel(x.targetId)
    if excludeLabels.exists(l.startsWith) then None
    else {
      x.arguments.find(_.startsWith("-P:semanticdb:targetroot")) match {
        case None      => None // throw new RuntimeException(s"no semanticdb arg for ${l}, args: ${x.arguments}")
        case Some(arg) => Some(l -> arg)
      }
    }
  }.toMap

  lazy val missingFromQuery = scalacTargets -- scalaQueryTargets.keySet
  lazy val missingFromAquery = scalaQueryTargets.keySet -- scalacTargets
  lazy val missingSemanticsDb = scalacTargets -- semanticsDbArg.keySet

  lazy val sourceTargets = scalacTargets.toList.mapFilter { x =>
    scalaQueryTargets.get(x).flatMap { qt =>
      semanticsDbArg.get(x).map { s =>
        // -P:semanticdb:targetroot:bazel-out/k8-fastbuild/bin/src/main/scala/casehub/models/_semanticdb/models
        val sdb = s.stripPrefix("-P:semanticdb:targetroot:")
        x -> ((qt, sdb))
      }
    }
  }.toMap
}

object QueryOutput {
  def make(uri: SafeUri) = {
    val api = BazelAPI(uriToPath(uri))

    import dsl._
    val q = kind("scala_library")(deps("..."))

    val aq = let("everything")(deps("...")) { everything =>
      val sc = kind("scala_library")(mnemonic("Scalac")(everything))
      val jvm = kind("jvm_import")(everything)
      val gen = kind("genrule")(everything)
      val proto = kind("proto_library")(everything)
      union(sc) {
        union(jvm) {
          union(gen) {
            proto
          }
        }
      }
    }

    (api.query(q), api.aquery(aq)).mapN(QueryOutput(_, _))
  }
}

object Tasks {
  def buildConfig(uri: SafeUri, targets: String*) = {
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

  def buildLabels(uri: SafeUri) = {
    val api = BazelAPI(uriToPath(uri))

    import dsl._

    api.runBuild("//src:mezel_config").void *>
      api
        .aquery(Query.Word("//src:mezel_config"))
        .map { x =>
          assert(x.actions.size === 1)
          val pathFrags = x.pathFragments.map(p => p.id -> p).toMap
          val arts = x.artifacts.map(x => x.id -> x.pathFragmentId).toMap
          def buildPath(x: analysis_v2.PathFragment): Path = {
            def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
              pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
            }

            go(x).value
          }

          val a = x.actions.head
          val outputPath = buildPath(pathFrags(arts(a.primaryOutputId)))
          Files[IO].readAll(outputPath).through(fs2.text.utf8.decode[IO]).compile.string.flatMap{ str =>
            // _root_.io.circe.parser.decode
            ???
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

  def regen: IO[QueryOutput] = workspaceRoot.flatMap(QueryOutput.make(_))

  // todo optimize
  def dependencySources(targets: List[SafeUri]): IO[Option[Json]] = {
    regen.flatMap { qo =>
      workspaceRoot.map(uriToPath).flatMap { root =>
        val argSrcs =
          qo.aqr.actions.flatMap(_.arguments.drop(1).filter(x => x.endsWith("-src.jar") || x.endsWith("-sources.jar"))).distinct.toList

        val pathFrags = qo.aqr.pathFragments.map(p => p.id -> p).toMap
        val arts = qo.aqr.artifacts.map(x => x.id -> x.pathFragmentId)
        def buildPath(x: analysis_v2.PathFragment): Path = {
          def go(x: analysis_v2.PathFragment): Eval[Path] = Eval.defer {
            pathFrags.get(x.parentId).traverse(go(_)).map(_.map(_ / x.label).getOrElse(Path(x.label)))
          }

          go(x).value
        }

        val uniqueArtifacts = qo.aqr.artifacts.map(_.id)

        val artifactToPath: Map[Int, PathFragment] = arts.map { case (id, p) => id -> pathFrags(p) }.toMap
        val relevantPathRoots = uniqueArtifacts.reverse.zipWithIndex.mapFilter { case (x, i) =>
          if (i % 50 == 0) println(s"artifact ${i}/ ${uniqueArtifacts.size}")
          val r = artifactToPath(x)
          if (r.label.endsWith("-src.jar") || r.label.endsWith("-sources.jar")) {
            Some(r)
          } else None
        }

        val outputSrcs = relevantPathRoots.map(buildPath).distinct.map(root / _)

        val paths = (argSrcs.map(x => root / Path(x)) ++ outputSrcs).distinct

        paths.filterA(Files[IO].exists).map { existing =>
          val out = Some {
            DependencySourcesResult {
              targets.map { t =>
                DependencySourcesItem(
                  BuildTargetIdentifier(t),
                  existing.map(pathToUri)
                )
              }
            }.asJson
          }

          println("done with dependency sources")

          out
        }
      }
    }
  }

  def compile(targets: List[SafeUri]): IO[Option[Json]] =
    regen.flatMap { qo =>
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
    }

  def sources(targets: List[SafeUri]): IO[Option[Json]] =
    regen.flatMap { qo =>
      workspaceRoot.map(uriToPath).map { root =>
        Some {
          SourcesResult {
            targets.map { uri =>
              val label = qo.pathToLabel(uriToPath(uri))
              val (qt, _) = qo.sourceTargets(label)
              val act = qo.scalacActionsMap(label)
              val srcs = act.arguments.dropWhile(_ =!= "--Files").tail.takeWhile(x => !x.startsWith("--"))
              SourcesItem(
                BuildTargetIdentifier(uri),
                srcs.map(x => root / Path(x)).map(pathToUri).map(SourceItem(_, SourceItemKind.File, false)).toList,
                Nil
              )
            }
          }.asJson
        }
      }
    }

  def scalacOptions(targets: List[SafeUri]) = {
    regen.flatMap { qo =>
      workspaceRoot.map(uriToPath).map { root =>
        Some {
          ScalacOptionsResult {
            targets.map { uri =>
              val label = qo.pathToLabel(uriToPath(uri))
              val (qt, sdb) = qo.sourceTargets(label)
              val act = qo.scalacActionsMap(label)
              val args = act.arguments.dropWhile(_ =!= "--ScalacOpts").tail.takeWhile(x => !x.startsWith("--")) ++ List(
                s"-P:semanticdb:sourceroot:${sdb}"
              )
              // consider getting this from a query(deps) -> aquery(output jars)
              val classpath = act.arguments.dropWhile(_ =!= "--Classpath").tail.takeWhile(x => !x.startsWith("--"))
              ScalacOptionsItem(
                BuildTargetIdentifier(uri),
                (args.toList ++ List(
                  "-Xplugin-require:semanticdb",
                  "-Xplugin:/home/valde/.cache/bloop/semanticdb/org.scalameta.semanticdb-scalac_2.13.12.4.8.3/semanticdb-scalac_2.13.12-4.8.3.jar"
                )).distinct,
                classpath.toList.map(x => pathToUri(root / x)),
                sdb
              )
            }
          }.asJson
        }
      }
    }
  }

  def buildTargets: IO[Option[Json]] =
    regen.map { qo =>
      println(s"missing from query: ${qo.missingFromQuery}\nmissing from aquery: ${qo.missingFromAquery}")
      println(s"missing semanticsdb: ${qo.missingSemanticsDb}")

      val targets = qo.sourceTargets.toList.map { case (k, (qt, _)) =>
        val scalaDeps: Seq[SafeUri] =
          qt.deps.mapFilter(qo.scalaQueryTargets.get).map(x => pathToUri(x.path))
        BuildTarget(
          id = BuildTargetIdentifier(pathToUri(qt.path)),
          displayName = Some(qt.path.fileName.toString),
          baseDirectory = Some(pathToUri(qt.path)),
          tags = List("library"),
          languageIds = List("scala"),
          dependencies = scalaDeps.map(BuildTargetIdentifier(_)).toList,
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
              jars = List(
                "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_library/io_bazel_rules_scala_scala_library.stamp/scala-library-2.13.12.jar",
                "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_reflect/io_bazel_rules_scala_scala_reflect.stamp/scala-reflect-2.13.12.jar",
                "file:///home/valde/git/mezel-example/bazel-out/k8-fastbuild/bin/external/io_bazel_rules_scala_scala_compiler/io_bazel_rules_scala_scala_compiler.stamp/scala-compiler-2.13.12.jar"
              ).map(SafeUri(_)),
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
