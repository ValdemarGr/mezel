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
    trace: Trace
) {
  val aspect = "@mezel//aspects:aspect.bzl%mezel_aspect"

  def api = BazelAPI(uriToPath(root), buildArgs, aqueryArgs, logger, trace)

  def buildTargetCache: IO[BuildTargetCache] =
    buildTargetFiles.map(xs => xs.map(x => x.label -> x)).map(BuildTargetCache(_)) // .flatMap(fromTargets)

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
    api.runBuild(("..." :: "--keep_going" :: extraFlags.toList)*).void
  }

  def buildTargetFiles: IO[List[BuildTargetFiles]] = trace.trace("buildTargetFiles") {
    import dsl._
    buildAll() *>
      buildConfig("...") *>
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
            val r = uriToPath(root)
            BuildTargetFiles(
              ext.targetMap(act.targetId),
              r / ext.buildPath(so),
              r / ext.buildPath(s),
              r / ext.buildPath(ds),
              r / ext.buildPath(bt)
            )
          }
        }
  }

  def diagnosticsFiles: IO[Seq[(String, Path)]] = trace.trace("diagnosticsFiles") {
    import dsl._

    val r = uriToPath(root)
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
          .map(r / _)
        res tupleLeft label
      }
    }
  }
}
