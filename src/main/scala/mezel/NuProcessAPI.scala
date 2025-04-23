package mezel

import scala.jdk.CollectionConverters._
import fs2._
import cats.effect._
import fs2.io.file.Path
import com.zaxxer.nuprocess._
import cats.effect.std.Dispatcher
import fs2.concurrent.Channel
import java.nio.ByteBuffer
import scodec.bits.ByteVector
import cats.effect.std.Queue
import cats.effect.std.AtomicCell
import java.util.concurrent.TimeUnit

trait NuProcessAPI[F[_]] {
  def stdout: Stream[F, Byte]

  def stderr: Stream[F, Byte]

  def stdin: Pipe[F, Byte, Unit]

  def kill: F[Unit]

  def terminate: F[Unit]

  def pid: F[Int]

  def statusCode: F[ExitCode]
}

object NuProcessAPI {
  sealed trait InterruptCase
  object InterruptCase {
    final case class Thrown(t: Throwable) extends InterruptCase
    final case class InvalidState(msg: String) extends InterruptCase
  }

  sealed trait ProcessState
  object ProcessState {
    final case class NotInitialized(wantWrite: Boolean) extends ProcessState
    final case class NotStarted(
        processRef: NuProcess,
        wantWrite: Boolean
    ) extends ProcessState
    final case class Started(processRef: NuProcess) extends ProcessState
  }

  def spawn(
      args: List[String],
      cwd: Option[Path] = None,
      environment: Option[Map[String, String]] = None
  ): Stream[IO, NuProcessAPI[IO]] = for {
    runtimeDispatcher <- Stream.resource(Dispatcher.parallel[IO])
    state <- Stream.eval(AtomicCell[IO].of[ProcessState](ProcessState.NotInitialized(false)))
    interruptCase <- Stream.eval(IO.deferred[InterruptCase])
    stdoutChan <- Stream.eval(Channel.unbounded[IO, ByteVector])
    stderrChan <- Stream.eval(Channel.unbounded[IO, ByteVector])
    stdinQ <- Stream.eval(Queue.bounded[IO, Chunk[Byte]](64))
    prefix <- Stream.eval(IO.ref(Option.empty[Chunk[Byte]]))
    exitD <- Stream.eval(IO.deferred[ExitCode])
    _ <- Stream.unit.covary[IO].interruptWhen {
      interruptCase.get.map {
        case InterruptCase.InvalidState(msg) => Left(new IllegalStateException(msg))
        case InterruptCase.Thrown(t)         => Left(t)
      }
    }
    doWrite = (c: Chunk[Byte]) =>
      IO.race(
        interruptCase.get,
        stdinQ.offer(c) >>
          state.evalUpdate {
            case ProcessState.NotInitialized(_) => IO.pure(ProcessState.NotInitialized(true))
            case ns: ProcessState.NotStarted    => IO.pure(ns.copy(wantWrite = true))
            case s @ ProcessState.Started(p)    => IO(p.wantWrite()).as(s)
          }
      ).map(_.isRight)
    np = {
      import InterruptCase._
      def interrupt(c: InterruptCase): IO[Unit] = interruptCase.complete(c).void
      def err(msg: String): IO[Unit] = interrupt(InvalidState(msg))
      def thrown(t: Throwable): IO[Unit] = interrupt(Thrown(t))
      def exit(ec: ExitCode): IO[Unit] = exitD.complete(ec).void

      def unsafe[A](ioa: => IO[A]): A =
        runtimeDispatcher.unsafeRunSync {
          IO.defer(ioa).onError(thrown)
        }
      new NuAbstractProcessHandler {
        override def onStart(nuProcess: NuProcess): Unit = unsafe {
          state.evalUpdate {
            case ProcessState.NotStarted(ref, wantWrite) =>
              val s = ProcessState.Started(nuProcess)
              if (wantWrite) IO(ref.wantWrite()).as(s)
              else IO.pure(s)
            case other =>
              err("onStart called before onPreStart").as(other)
          }
        }

        override def onPreStart(nuProcess: NuProcess): Unit = unsafe {
          nuProcess.setProcessHandler {
            new NuAbstractProcessHandler {
              override def onExit(statusCode: Int): Unit = unsafe {
                exit(ExitCode(statusCode))
              }
              override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = unsafe {
                val bv = ByteVector(buffer)
                buffer.position(buffer.limit)
                val fa =
                  if (closed) stdoutChan.closeWithElement(bv)
                  else stdoutChan.send(bv)
                fa.flatMap {
                  case Left(_)  => err("Failed to send stdout, closed")
                  case Right(_) => IO.unit
                }
              }
              override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = unsafe {
                val bv = ByteVector(buffer)
                buffer.position(buffer.limit)
                val fa =
                  if (closed) stderrChan.closeWithElement(bv)
                  else stderrChan.send(bv)
                fa.flatMap {
                  case Left(_)  => err("Failed to send stderr, closed")
                  case Right(_) => IO.unit
                }
              }
              override def onStdinReady(buffer: ByteBuffer): Boolean = unsafe {
                val size = buffer.remaining()

                def go(accum: Chunk[Byte]): IO[(Chunk[Byte], Option[Chunk[Byte]])] =
                  stdinQ.tryTake.flatMap {
                    case None => IO.pure((accum, None))
                    case Some(next) =>
                      val (produce, overflow) = (accum ++ next).splitAt(size)
                      if (overflow.isEmpty) go(produce)
                      else IO.pure((produce, Some(overflow)))
                  }

                val fa = prefix.get.flatMap {
                  case Some(prefix) =>
                    val (produce, overflow) = prefix.splitAt(size)
                    if (overflow.isEmpty) go(produce)
                    else IO.pure((produce, Some(overflow)))
                  case None => go(Chunk.empty)
                }

                fa.flatMap { case (emit, overflow) =>
                  prefix.set(overflow).map { _ =>
                    buffer.put(emit.toByteBuffer)
                    buffer.flip()
                    overflow.isDefined
                  }
                }
              }
            }
          }

          state.modify {
            case ProcessState.NotInitialized(wantWrite) =>
              ProcessState.NotStarted(nuProcess, wantWrite) -> IO.unit
            case other =>
              other -> err("onPreStart called after onStart")
          }.flatten
        }
      }
    }
    b <- Stream.eval {
      IO {
        val b =
          environment.fold(new NuProcessBuilder(args.asJava))(env => new NuProcessBuilder(args.asJava, env.asJava))
        b.setProcessListener(np)
        cwd.map(_.toNioPath).foreach(b.setCwd)
        b
      }
    }

    np <- Stream.bracket(IO(b.start())) { ph =>
      val awaitStatus = IO.interruptibleMany(ph.waitFor(10, TimeUnit.SECONDS)).void
      IO(ph.destroy(false)) *> awaitStatus *> IO(ph.destroy(true)) *> awaitStatus
    }

    api = new NuProcessAPI[IO] {
      override def stdout: Stream[IO, Byte] = stdoutChan.stream.map(Chunk.byteVector(_)).unchunks

      override def stderr: Stream[IO, Byte] = stderrChan.stream.map(Chunk.byteVector(_)).unchunks

      override def stdin: Pipe[IO, Byte, Unit] =
        _.chunks.evalMap(doWrite).takeWhile(identity).as(()).onFinalize {
          IO(np.closeStdin(false))
        }

      override def kill: IO[Unit] =
        IO(np.destroy(true))

      override def terminate: IO[Unit] =
        IO(np.destroy(false))

      override def pid: IO[Int] =
        IO(np.getPID)

      override def statusCode: IO[ExitCode] =
        exitD.get
    }
  } yield api
}
