package mezel

import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
import _root_.io.circe.syntax.*
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
import scala.concurrent.duration.*
import _root_.io.circe.Json
import cats.data.*
import fs2.concurrent.SignallingRef
import catcheffect.*
import fs2.concurrent.Channel
import cats.effect.std.Supervisor
import com.google.devtools.build.lib.buildeventstream.{build_event_stream => bes}

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    if (args.contains("--filesystem")) {
      runWithIO(
        Files[IO].tail(Path("/tmp/from-metals")),
        Files[IO].writeAll(Path("/tmp/to-metals"))
      )
    } else {
      runWithIO(
        fs2.io.stdin[IO](4096),
        fs2.io.stdout[IO]
      )
    }
  }.as(ExitCode.Success)

  def runWithIO(
      read: Stream[IO, Byte],
      write: Pipe[IO, Byte, Unit]
  ): IO[Unit] =
    SignallingRef.of[IO, BspState](BspState.empty).flatMap { state =>
      Files[IO].tempDirectory(None, "mezel-semanticdb-cache", None).use { tmp =>
        Catch.ioCatch.flatMap { implicit C =>
          C.use[Unit] { Exit =>
            Channel.bounded[IO, Json](64).flatMap { output =>
              val ioStream: Stream[IO, Unit] = {
                read
                  .through(fs2.text.utf8.decode)
                  // .evalTap(x => IO.println(s"Received: data of size ${x.size}"))
                  .through(jsonRpcRequests)
                  // .evalTap(x => IO.println(s"Request: ${x.method}"))
                  .evalMap { x =>
                    Supervisor[IO](await = true).use { sup =>
                      IO.deferred[Unit].flatMap { done =>
                        def expect[A: Decoder]: IO[A] =
                          IO.fromOption(x.params)(new RuntimeException(s"No params for method ${x.method}"))
                            .map(_.as[A])
                            .rethrow

                        val runRequest: IO[Either[BspResponseError, Option[Json]]] = C
                          .use[BspResponseError] { implicit R =>
                            val ops: BspServerOps = new BspServerOps(state, done, sup, output, tmp)

                            x.method match {
                              case "build/initialize"       => expect[InitializeBuildParams].flatMap(ops.initalize)
                              case "build/initialized"      => IO.pure(None)
                              case "workspace/buildTargets" => ops.buildTargets
                              case "buildTarget/scalacOptions" =>
                                expect[ScalacOptionsParams].flatMap(p => ops.scalacOptions(p.targets.map(_.uri)))
                              case "buildTarget/javacOptions" => IO.pure(Some(ScalacOptionsResult(Nil).asJson))
                              case "buildTarget/sources" =>
                                expect[SourcesParams].flatMap(sps => ops.sources(sps.targets.map(_.uri)))
                              case "buildTarget/dependencySources" =>
                                expect[DependencySourcesParams].flatMap(dsp => ops.dependencySources(dsp.targets.map(_.uri)))
                              case "buildTarget/scalaMainClasses" =>
                                IO.pure(Some(ScalaMainClassesResult(Nil, None).asJson))
                              case "buildTarget/jvmRunEnvironment" =>
                                IO.pure(Some(JvmRunEnvironmentResult(Nil).asJson))
                              case "buildTarget/scalaTestClasses" =>
                                IO.pure(Some(ScalaTestClassesResult(Nil).asJson))
                              case "buildTarget/compile" =>
                                expect[CompileParams].flatMap(p => ops.compile(p.targets.map(_.uri)))
                              case "build/exit" | "build/shutdown" => Exit.raise(())
                              case m                               => IO.raiseError(new RuntimeException(s"Unknown method: $m"))
                            }
                          }


                        val handleError: IO[Option[Response]] = runRequest.map {
                          case Left(err)    => Some(Response("2.0", x.id, None, Some(err.responseError)))
                          case Right(value) =>
                            // if id is defined always respond
                            // if id is not defined only respond if value is defined
                            x.id match {
                              case Some(id) => Some(Response("2.0", Some(id), value, None))
                              case None     => value.map(j => Response("2.0", None, Some(j), None))
                            }
                        }

                        handleError.flatMap { msg =>
                          msg.map(_.asJson).traverse_(output.send).flatMap(_ => done.complete(())).void
                        }
                      }
                    }
                  }
              }

              output.stream
                .concurrently(ioStream)
                .map(_.spaces2)
                .map(data => s"Content-Length: ${data.length}\r\n\r\n$data")
                .through(fs2.text.utf8.encode)
                .through(write)
                .compile
                .drain
            }
          }
        }.void
      }
    }
}
