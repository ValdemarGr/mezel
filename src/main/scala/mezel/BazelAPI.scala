package mezel

import scala.concurrent.duration.*
import cats.implicits.*
import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
import fs2.*
import cats.effect.{Trace => _, *}
import fs2.io.file.*
import cats.*
import fs2.io.process.*
import scalapb._
import com.zaxxer.nuprocess.internal.LibC
import cats.effect.kernel.Resource.ExitCase

class BazelAPI(
    rootDir: Path,
    buildArgs: List[String],
    aqueryArgs: List[String],
    logger: Logger,
    trace: Trace,
    outputBase: Option[Path]
) {
  case class Builder(
      cmd: String,
      args: List[String],
      cwd: Option[Path] = None
  )

  def pipe: Pipe[IO, Byte, String] = _.through(fs2.text.utf8.decode).through(fs2.text.lines)

  def run(b: Builder): Resource[IO, CatsProcess[IO]] =
    trace.traceResource(s"bazel command ${(b.cmd :: b.args).map(x => s"'$x'").mkString(" ")}") {
      CatsProcess.spawnFull[IO](b.cmd :: b.args, b.cwd).flatTap { cp =>
        Resource.makeCase(cp.pid) {
          case (pid, ExitCase.Canceled) =>
            outputBase match {
              case None => trace.logger.logWarn(s"No output base for task, cannot interrupt server")
              case Some(p) =>
                trace.logger.logInfo(s"Bazel task cancelled, reading the server's pid from ${p}") *>
                  Files[IO]
                    .readAll(p / "server" / "server.pid.txt")
                    .through(pipe)
                    .compile
                    .string
                    .map(_.toInt)
                    .flatMap { pid =>
                      trace.logger.logInfo(s"Found server pid $pid, sending SIGINTs until the client exists") *>
                        IO.race(
                          Stream
                            .repeatEval {
                              CatsProcess.spawn[IO]("kill", "-2", pid.toString).use(_.statusCode)
                            }
                            .meteredStartImmediately(100.millis)
                            .compile
                            .drain,
                          cp.statusCode.map(_.code).flatMap { code =>
                            trace.logger.logInfo(s"Server process $pid exited with code $code")
                          }
                        ).void
                    }
                    // In case of file not found, the server is already dead
                    .attempt
                    .flatMap {
                      case Left(e)  => trace.logger.logWarn(s"Error while trying to interrupt server: $e")
                      case Right(_) => IO.unit
                    }
            }
          case _ => IO.unit
        }
      }
    }

  def builder(cmd: String, printLogs: Boolean, args: String*) = {
    val ob = outputBase.map(x => s"--output_base=${x.toString}").toList
    Builder(
      "bazel",
      ob ++ (cmd :: List("--noshow_loading_progress", "--noshow_progress").filter(_ => !printLogs)) ++ args.toList,
      Some(rootDir)
    )
  }

  def runAndParse[A <: GeneratedMessage](cmd: String, args: String*)(implicit ev: GeneratedMessageCompanion[A]): IO[A] = {
    run(builder(cmd, printLogs = false, args*))
      .use { proc =>
        val fg = fs2.io
          .toInputStreamResource(proc.stdout)
          .use(is => IO.interruptibleMany(ev.parseFrom(is)))

        fg <& proc.stderr.through(pipe).evalMap(logger.printStdErr).compile.drain <* proc.statusCode.map(_.code).flatMap {
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
          line.split(": ").toList match {
            case k :: v :: Nil => IO.pure(Some(k -> v))
            case _ =>
              val msg = s"Unexpected output from bazel info: $line"
              logger.logWarn(msg).as(None)
          }
        }
        .unNone
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
        .eval(p.statusCode.map(_.code))
        .concurrently(p.stdout.through(pipe).evalMap(logger.printStdOut))
        .concurrently(p.stderr.through(pipe).evalMap(logger.printStdErr))
        .compile
        .lastOrError
    }

  def runBuild(cmds: String*): IO[Int] = {
    runUnitTask(
      "build",
      (cmds.toList ++ buildArgs.toList ++ List(
        "--curses=no",
        "--isatty=true",
        "--color=yes",
        "--build_manual_tests=true"
      ))*
    )
  }

  def runFetch(cmds: String*): IO[Int] =
    runUnitTask("fetch", cmds*)
}
