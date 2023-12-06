package mezel

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

class Tasks(
    root: SafeUri,
    buildArgs: List[String],
    aqueryArgs: List[String],
    logger: Logger,
    trace: Trace,
    outputBase: Path
) {
  val aspect = "@mezel//aspects:aspect.bzl%mezel_aspect"

  def api = BazelAPI(uriToPath(root), buildArgs, aqueryArgs, logger, trace, Some(outputBase))

  def buildTargetCache(execRoot: Path): IO[BuildTargetCache] =
    buildTargetFiles(execRoot).map(xs => xs.map(x => x.label -> x)).map(BuildTargetCache(_)) // .flatMap(fromTargets)

  def buildTargetRdeps(paths: String*): IO[List[String]] = {
    import dsl._
    val lst = paths.toList
    api.query(rdeps("...", set(lst))).map { qr =>
      val sourceMap = qr.target.mapFilter(_.sourceFile).mapFilter(x => x.location.tupleRight(x)).toMap
      val ruleMap = qr.target
        .mapFilter(_.rule)
        .flatMap(x => x.ruleInput.tupleRight(x))
        .groupMap { case (k, _) => k } { case (_, v) => v }

      lst.flatMap { p =>
        sourceMap.get(p.toString).toList.flatMap { s =>
          val st = s.name
          def findScalaRules(label: String): List[String] =
            ruleMap.get(label).toList.flatMap { rules =>
              rules.find(_.ruleClass.contains("scala")).map(_.name).map(List(_)).getOrElse {
                rules.map(_.name).flatMap(findScalaRules)
              }
            }
          findScalaRules(st)
        }
      }
    }
  }

  def buildConfig(targets: String*): IO[Unit] = trace.trace("buildConfig") {
    api
      .runBuild(
        (targets.toList ++ List(
          "--aspects",
          aspect,
          "--output_groups=bsp_info,bsp_info_deps"
        )): _*
      )
      .void
  }

  def buildAll(extraFlags: String*): IO[Unit] = trace.trace("buildAll") {
    api.runBuild(("//..." :: "--keep_going" :: extraFlags.toList)*).void
  }

  // def localRepositories = trace.trace("localRepositories") {
  //   import dsl._
  //   val q = kind(".*local_repository")("//external:*")
  //   // api.query("")
  //   IO.unit
  // }

  def buildTargetFiles(execRoot: Path): IO[List[BuildTargetFiles]] = trace.trace("buildTargetFiles") {
    import dsl._
    api.runFetch("//...") *>
      buildConfig("//...") *>
      api
        .aquery(mnemonic("MezelAspect")(deps("...")), "--aspects", aspect)
        .map { x =>
          val ext = ActionQueryResultExtensions(x)
          x.actions.toList.map { act =>
            val artifacts = act.inputDepSetIds.flatMap(id => ext.inputMap.get(id).getOrElse(Nil))
            val pfs = artifacts.map(ext.arts).map(ext.pathFrags)
            val so = pfs.find(_.label.endsWith("scalac_options.json")).get
            val s = pfs.find(_.label.endsWith("bsp_sources.json")).get
            val ds = pfs.find(_.label.endsWith("bsp_dependency_sources.json")).get
            val bt = pfs.find(_.label.endsWith("build_target.json")).get
            BuildTargetFiles(
              ext.targetMap(act.targetId),
              execRoot / ext.buildPath(so),
              execRoot / ext.buildPath(s),
              execRoot / ext.buildPath(ds),
              execRoot / ext.buildPath(bt)
            )
          }
        }
  }

  def diagnosticsFiles: IO[Seq[(String, Path)]] = trace.trace("diagnosticsFiles") {
    import dsl._

    api.aquery(mnemonic("Scalac")("...")).map { aq =>
      val ext = ActionQueryResultExtensions(aq)
      aq.actions.mapFilter { a =>
        val label = ext.targetMap(a.targetId)
        val outputs = a.primaryOutputId :: a.outputIds.toList
        val res = outputs
          .collectFirstSome { id =>
            val p = ext.pathFrags(ext.arts(id))
            if (p.label.endsWith(".diagnosticsproto")) Some(ext.buildPath(p))
            else None
          }
        res tupleLeft label
      }
    }
  }
}
