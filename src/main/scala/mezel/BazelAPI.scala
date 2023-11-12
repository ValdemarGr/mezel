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
import fs2.io.process.*
import scalapb._

class BazelAPI(rootDir: Path):
  def builder(cmd: String, args: String*) = {
    ProcessBuilder("bazel", cmd :: "--noshow_loading_progress" :: "--noshow_progress" :: args.toList)
      .withWorkingDirectory(rootDir)
  }

  def runAndParse[A <: GeneratedMessage](cmd: String, args: String*)(implicit ev: GeneratedMessageCompanion[A]): IO[A] = {
    builder(cmd, args*)
      .spawn[IO]
      .use: proc =>
        fs2.io
          .toInputStreamResource(proc.stdout)
          .use: is =>
            IO.interruptibleMany(ev.parseFrom(is))
  }

  def query(q: Query): IO[build.QueryResult] =
    runAndParse[build.QueryResult]("query", q.render, "--output=proto")

  def aquery(q: Query): IO[analysis_v2.ActionGraphContainer] =
    runAndParse[analysis_v2.ActionGraphContainer]("aquery", q.render, "--output=proto")

  def runBuild(targets: String*): IO[Int] =
    builder("build", targets*).spawn[IO].use(_.exitValue)
