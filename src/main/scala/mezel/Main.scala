package mezel

import _root_.io.circe.syntax.*
import cats.implicits.*
import io.circe.*
import fs2.*
import cats.effect.*
import fs2.io.file.*
import _root_.io.circe.Json
import fs2.concurrent.SignallingRef
import catcheffect.*
import fs2.concurrent.Channel
import cats.effect.std.Supervisor
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.*
import java.nio.charset.StandardCharsets

object Main
    extends CommandIOApp(
      "Mezel BSP server",
      "A BSP server for Bazel"
    ) {
  val fsFlag = Opts
    .flag(
      "filesystem",
      "Filesystem mode for local testing ('/tmp/from-metals' and '/tmp/to-metals')"
    )
    .orFalse

  val buildArgsFlag = Opts
    .options[String](
      "build-arg",
      "Extra arguments to pass to bazel build, like for instance a toolchain meant for LSP"
    )
    .orEmpty

  val aqueryArgsFlag = Opts
    .options[String](
      "aquery-arg",
      "Extra arguments to pass to bazel aquery, like for instance a toolchain meant for LSP"
    )
    .orEmpty

  def main: Opts[IO[ExitCode]] = (fsFlag, buildArgsFlag, aqueryArgsFlag).mapN { case (fs, buildArgs, aqueryArgs) =>
    val (stdin, stdout) = if (fs) {
      (
        Files[IO].tail(Path("/tmp/from-metals")),
        Files[IO].writeAll(Path("/tmp/to-metals"))
      )
    } else {
      (
        fs2.io.stdin[IO](4096),
        fs2.io.stdout[IO]
      )
    }

    runWithIO(stdin, stdout, buildArgs, aqueryArgs).as(ExitCode.Success)
  }

  def runWithIO(
      read: Stream[IO, Byte],
      write: Pipe[IO, Byte, Unit],
      buildArgs: List[String],
      aqueryArgs: List[String]
  ): IO[Unit] = {
    Files[IO].createTempDirectory(None, "mezel-logs-", None).flatMap { tmpDir =>
      SignallingRef.of[IO, BspState](BspState.empty).flatMap { state =>
        Catch.ioCatch.flatMap { implicit C =>
          C.use[Unit] { Exit =>
            Cache.make.flatMap { cache =>
              BspCacheKeys.make.flatMap { cacheKeys =>
                Supervisor[IO](await = false).use { sup =>
                  Channel.bounded[IO, Json](64).flatMap { output =>
                    def logger(originId: Option[String]) = Logger.make(None, originId)(x => output.send(x.asJson).void)

                    val fromMetals = tmpDir / "metals-to-mezel"
                    val toMetals = tmpDir / "mezel-to-metals"

                    val ioStream: Stream[IO, Unit] = {
                      read
                        .observe(Files[IO].writeAll(fromMetals))
                        .through(jsonRpcRequests)
                        .evalMap { x =>
                          val originId = x.params.flatMap(_.asObject).flatMap(_.apply("originId")).flatMap(_.asString)

                          def expect[A: Decoder]: IO[A] =
                            IO.fromOption(x.params)(new RuntimeException(s"No params for method ${x.method}"))
                              .map(_.as[A])
                              .rethrow

                          val runRequest: IO[Either[BspResponseError, Option[Json]]] = C
                            .use[BspResponseError] { implicit R =>
                              val lg = logger(originId)
                              val trace = Trace.in(x.method, lg)
                              val ops: BspServerOps =
                                new BspServerOps(
                                  state,
                                  sup,
                                  output,
                                  buildArgs,
                                  aqueryArgs,
                                  lg,
                                  trace,
                                  cache,
                                  cacheKeys
                                )

                              trace.trace("root") {
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
                                  case "buildTarget/resources" =>
                                    expect[ResourcesParams]
                                      .map(p => Some(ResourcesResult(p.targets.map(t => ResourcesItem(t, Nil))).asJson))
                                  // case "workspace/reload" =>
                                  // state.getAndSet(BspState.empty).flatMap { os =>
                                  //   os.initReq.traverse(ops.initalize) >> ops.buildTargets.as(None)
                                  // }
                                  case "build/exit" | "build/shutdown" => Exit.raise(())
                                  // I got this while testing?
                                  case "$/cancelRequest" => IO.pure(None)
                                  case m                 => IO.raiseError(new RuntimeException(s"Unknown method: $m"))
                                }
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

                          handleError.flatMap(_.map(_.asJson).traverse_(output.send))
                        }
                    }

                    logger(None).logInfo(s"Starting Mezel server, logs will be at ${tmpDir}") >>
                      output.stream
                        .concurrently(ioStream)
                        .map(_.deepDropNullValues.noSpaces)
                        .map { data =>
                          val encodedData = data.getBytes(StandardCharsets.UTF_8)
                          // UTF-8 length != string length (special characters like gamma are encoded with more bytes)
                          Chunk.array(s"Content-Length: ${encodedData.length}\r\n\r\n".getBytes(StandardCharsets.UTF_8)) ++
                            Chunk.array(encodedData)
                        }
                        .unchunks
                        .observe(Files[IO].writeAll(toMetals))
                        .through(write)
                        .compile
                        .drain
                  }
                }
              }
            }
          }.void
        }
      }
    }
  }
}
