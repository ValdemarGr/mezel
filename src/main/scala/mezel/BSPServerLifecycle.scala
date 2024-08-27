package mezel

import fs2.io.file._
import fs2.Chunk
import cats.effect.{Trace => _, _}
import cats.effect.std._
import fs2.concurrent.SignallingRef
import fs2.concurrent.Channel
import io.circe._
import io.circe.syntax._
import cats.implicits._
import catcheffect.Catch
import catcheffect.Raise
import java.nio.charset.StandardCharsets

class BSPServerLifecycle(
    buildArgs: List[String],
    aqueryArgs: List[String],
    deps: BSPServerDeps,
    verbosity: Verbosity
) {
  def verbose: Verbose = Verbose.make(verbosity)

  def logger(originId: Option[String]): Logger =
    Logger.make(None, originId)(x => deps.output.send(x.asJson).void)

  val fromMetals = deps.tmpDir / "metals-to-mezel"
  val toMetals = deps.tmpDir / "mezel-to-metals"

  def makeOps(trace: mezel.Trace, logger: Logger): Raise[IO, BspResponseError] ?=> BspServerOps = R ?=>
    new BspServerOps(
      deps.state,
      deps.sup,
      deps.output,
      buildArgs,
      aqueryArgs,
      logger,
      trace,
      deps.cache,
      deps.cacheKeys,
      deps.ct,
      verbose
    )

  def runRequest(trace: Trace, id: Option[RpcId])(res: IO[Either[BspResponseError, Option[Json]]]): IO[Option[Json]] = {
    val handleError: IO[Option[Response]] =
      res.map {
        case Left(err)    => Some(Response("2.0", id, None, Some(err.responseError)))
        case Right(value) =>
          // if id is defined always respond
          // if id is not defined only respond if value is defined
          id match {
            case Some(id) => Some(Response("2.0", Some(id), value, None))
            case None     => value.map(j => Response("2.0", None, Some(j), None))
          }
      }

    val leased = id.map(id => deps.rl.run(trace, id, handleError).map(_.flatten)).getOrElse(handleError)

    leased.map(_.map(_.asJson))
  }

  def read(stdin: fs2.Stream[IO, Byte]) =
    stdin
      .observe(Files[IO].writeAll(fromMetals))
      .through(jsonRpcRequests)

  def write(stdout: fs2.Pipe[IO, Byte, Unit]): fs2.Pipe[IO, Json, Unit] =
    _.map(_.deepDropNullValues.noSpaces)
      .map { data =>
        val encodedData = data.getBytes(StandardCharsets.UTF_8)
        // UTF-8 length != string length (special characters like gamma are encoded with more bytes)
        Chunk.array(s"Content-Length: ${encodedData.length}\r\n\r\n".getBytes(StandardCharsets.UTF_8)) ++
          Chunk.array(encodedData)
      }
      .unchunks
      .observe(Files[IO].writeAll(toMetals))
      .through(stdout)

  enum TaskType {
    case Concurrent
    case Sequential
  }
  def taskType(method: String): TaskType = method match {
    case "build/exit" | "build/shutdown" | "$/cancelRequest" => TaskType.Concurrent
    case _                                                   => TaskType.Sequential
  }

  def handleRequest(trace: Trace, lg: Logger, x: Request)(implicit Exit: Raise[IO, Unit]): IO[Either[BspResponseError, Option[Json]]] = {
    def expect[A: Decoder]: IO[A] =
      IO.fromOption(x.params)(new RuntimeException(s"No params for method ${x.method}"))
        .map(_.as[A])
        .rethrow

    deps.C.use[BspResponseError] { implicit R =>
      val ops = makeOps(trace, lg)
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
          case "buildTarget/jvmCompileClasspath" =>
            expect[JvmCompileClasspathParams].flatMap(p => ops.jvmCompileCLasspath(p.targets.map(_.uri)))
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
          case "$/cancelRequest" =>
            expect[CancelParams].flatMap(p => deps.rl.cancel(trace, p.id)).as(None)
          case m => IO.raiseError(new RuntimeException(s"Unknown method: $m"))
        }
      }
    }
  }

  def start(
      stdin: fs2.Stream[IO, Byte],
      stdout: fs2.Pipe[IO, Byte, Unit]
  ): IO[Unit] = {
    deps.C
      .use[Unit] { implicit Exit =>
        val consume =
          read(stdin)
            .map { req =>
              val originId = req.params.flatMap(_.asObject).flatMap(_.apply("originId")).flatMap(_.asString)
              val lg = logger(originId)
              val fa: IO[Option[Json]] =
                Trace.in(s"${req.id.map(_.value.toString()).getOrElse("?")}: ${req.method}", lg).flatMap { trace =>
                  runRequest(trace, req.id) {
                    handleRequest(trace, lg, req)
                  }
                }

              fa -> taskType(req.method)
            }
            .parEvalMapUnbounded {
              case (fa, TaskType.Concurrent) => fa.map(_.asRight)
              case (fa, TaskType.Sequential) => IO.pure(Left(fa))
            }
            .evalMap {
              case Right(result) => IO.pure(result)
              case Left(fa)      => fa
            }
            .evalMap(_.traverse_(deps.output.send))

        logger(None).logInfo(s"Starting Mezel server, logs will be at ${deps.tmpDir}") >>
          (deps.output.stream.concurrently(consume)).through(write(stdout)).compile.drain
      }
      .void
  }
}

final case class BSPServerDeps(
    tmpDir: Path,
    state: SignallingRef[IO, BspState],
    sup: Supervisor[IO],
    cache: Cache,
    cacheKeys: BspCacheKeys,
    output: Channel[IO, Json],
    rl: RequestLifecycle,
    C: Catch[IO],
    ct: CancellableTask
)

object BSPServerDeps {
  def make = CancellableTask.make.flatMap { ct =>
    (
      Resource.eval(Files[IO].createTempDirectory(None, "mezel-logs-", None)),
      Resource.eval(SignallingRef.of[IO, BspState](BspState.empty)),
      Supervisor[IO](await = false),
      Resource.eval(Cache.make),
      Resource.eval(BspCacheKeys.make),
      Resource.eval(Channel.bounded[IO, Json](64)),
      Resource.pure(RequestLifecycle.make(ct)),
      Resource.eval(Catch.ioCatch),
      Resource.pure(ct)
    ).mapN(BSPServerDeps.apply)
  }
}
