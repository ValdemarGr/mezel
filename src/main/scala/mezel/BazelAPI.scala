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
import fs2.io.process.*
import scalapb._

class BazelAPI(
    rootDir: Path,
    buildArgs: List[String],
    aqueryArgs: List[String],
    logger: Logger,
    trace: Trace,
    outputBase: Path
) {
  def pipe: Pipe[IO, Byte, String] = _.through(fs2.text.utf8.decode).through(fs2.text.lines)

  def run(pb: ProcessBuilder): Resource[IO, Process[IO]] =
    trace.traceResource(s"bazel command ${(pb.command :: pb.args).map(x => s"'$x'").mkString(" ")}") {
      pb.spawn[IO]
    }

  def builder(cmd: String, printLogs: Boolean, args: String*) = {
    ProcessBuilder(
      "bazel",
      s"--output_base=${outputBase}" :: cmd :: List("--noshow_loading_progress", "--noshow_progress").filter(_ => !printLogs) ++ args.toList
    )
      .withWorkingDirectory(rootDir)
  }

  def runAndParse[A <: GeneratedMessage](cmd: String, args: String*)(implicit ev: GeneratedMessageCompanion[A]): IO[A] = {
    run(builder(cmd, printLogs = false, args*))
      .use { proc =>
        val fg = fs2.io
          .toInputStreamResource(proc.stdout)
          .use(is => IO.interruptibleMany(ev.parseFrom(is)))

        fg <& proc.stderr.through(pipe).evalMap(logger.printStdErr).compile.drain <* proc.exitValue.flatMap {
          case 0 => IO.unit
          case n => IO.raiseError(new Exception(s"Bazel command failed with exit code $n"))
        }
      }
  }

  def info: IO[Map[String, String]] =
    run(builder("info", printLogs = false)).use { p =>
      p.stdout
        .through(pipe)
        .evalMap { line =>
          line.split(" ").toList match {
            case k :: v :: Nil => IO.pure(k -> v)
            case _ =>
              val msg = s"Unexpected output from bazel info: $line"
              logger.logError(msg) *> IO.raiseError(new Exception(msg))
          }
        }
        .concurrently(p.stderr.through(pipe).evalMap(logger.printStdErr))
        .compile
        .to(Map)
    }

  def query(q: Query, extra: String*): IO[build.QueryResult] =
    runAndParse[build.QueryResult]("query", q.render :: "--output=proto" :: extra.toList: _*)

  def aquery(q: AnyQuery, extra: String*): IO[analysis_v2.ActionGraphContainer] = {
    val fa = runAndParse[analysis_v2.ActionGraphContainer](
      "aquery",
      (q.render :: "--output=proto" :: extra.toList ++ aqueryArgs)*
    )
    // https://github.com/bazelbuild/bazel/issues/15716
    fa.handleErrorWith { _ =>
      logger.logWarn {
        "Bazel aquery failed, this might be because of 'https://github.com/bazelbuild/bazel/issues/15716', retrying once"
      } *> fa
    }
  }

  def runUnitTask(op: String, cmds: String*): IO[Int] =
    run(builder(op, printLogs = true, cmds*)).use { p =>
      Stream
        .eval(p.exitValue)
        .concurrently(p.stdout.through(pipe).evalMap(logger.printStdOut))
        .concurrently(p.stderr.through(pipe).evalMap(logger.printStdErr))
        .compile
        .lastOrError
    }

  def runBuild(cmds: String*): IO[Int] =
    runUnitTask("build", (cmds.toList ++ buildArgs.toList)*)

  def runFetch(cmds: String*): IO[Int] =
    runUnitTask("fetch", cmds*)
}
