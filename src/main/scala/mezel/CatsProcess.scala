package mezel

import java.nio.ByteBuffer
import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.implicits._
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import fs2.{Chunk, Pipe, Stream}
import fs2.io.file.Path
import scodec.bits.ByteVector
import scala.jdk.CollectionConverters._

import scala.util.control.NonFatal
import cats.effect.ExitCode

trait CatsProcess[F[_]] {
  def stdout: Stream[F, Byte]

  def stderr: Stream[F, Byte]

  def stdin: Pipe[F, Byte, Unit]

  def kill: F[Unit]

  def terminate: F[Unit]

  def pid: F[Int]

  def statusCode: F[ExitCode]
}

object CatsProcess {

  def spawn[F[_]: Async](args: String*): Resource[F, CatsProcess[F]] =
    spawnFull(args.toList)

  def spawnFull[F[_]](
    args: List[String],
    cwd: Option[Path] = None,
    environment: Option[Map[String, String]] = None
  )(implicit F: Async[F]): Resource[F, CatsProcess[F]] =
    Dispatcher.parallel[F].flatMap { dispatcher =>
      val acquire: F[CatsProcess[F]] = for {
        processD <- Deferred[F, CatsProcess[F]]
        stdout <- Queue.unbounded[F, Option[ByteVector]]
        stderr <- Queue.unbounded[F, Option[ByteVector]]
        stdin <- Queue.bounded[F, ByteVector](10) // TODO config
        statusCode <- Deferred[F, Int]

        handler = new NuAbstractProcessHandler {

          /** This method is invoked when you call the ''ProcessBuilder#start()'' method. Unlike the
            * ''#onStart(NuProcess)'' method, this method is invoked before the process is spawned, and is guaranteed to
            * be invoked before any other methods are called. The { @link NuProcess} that is starting. Note that the
            * instance is not yet initialized, so it is not legal to call any of its methods, and doing so will result
            * in undefined behavior. If you need to call any of the instance's methods, use ''#onStart(NuProcess)''
            * instead.
            */
          override def onPreStart(nuProcess: NuProcess): Unit = {
            val proc = processFromNu[F](nuProcess, dispatcher, stdout, stderr, stdin, statusCode)
            nuProcess.setProcessHandler(proc)

            try {
              // TODO Handle case where the deferred has already been completed
              val _ = dispatcher.unsafeRunSync(processD.complete(proc))
            } catch {
              case NonFatal(_) => () // TODO Handle errors ?
            }
          }
        }

        builder <- F.delay {
          val b =
            environment.fold(new NuProcessBuilder(args.asJava))(env => new NuProcessBuilder(args.asJava, env.asJava))
          b.setProcessListener(handler)
          cwd.map(_.toNioPath).foreach(b.setCwd)
          b
        }

        _ <- F.delay(builder.start())
        process <- processD.get
        _ <- F.delay("Got process")
      } yield process

      // On resource release, we terminate the process (signal) and then wait for the actual exit
      // to happen (the status code is really the exit code, which is available only once the
      // process has been really terminated)
      val release: CatsProcess[F] => F[Unit] = p => p.terminate >> p.statusCode.void

      Resource.make(acquire)(release)
    }

  def processFromNu[F[_]](
    proc: NuProcess,
    dispatcher: Dispatcher[F],
    stdoutQ: Queue[F, Option[ByteVector]],
    stderrQ: Queue[F, Option[ByteVector]],
    stdinQ: Queue[F, ByteVector],
    statusCodeD: Deferred[F, Int]
  )(implicit F: Async[F]): NuAbstractProcessHandler with CatsProcess[F] =
    new NuAbstractProcessHandler with CatsProcess[F] {

      // Nu, unsafe logic

      def enqueueByteBuffer(buffer: ByteBuffer, q: Queue[F, Option[ByteVector]]): Unit = {
        // Copy the buffer content
        val bv = ByteVector(buffer)
        // Ensure we consume the entire buffer in case it's not used.
        buffer.position(buffer.limit)

        dispatcher.unsafeRunSync(q.offer(Some(bv)))
      }

      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
        enqueueByteBuffer(buffer, stdoutQ)

      override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
        enqueueByteBuffer(buffer, stderrQ)

      override def onStdinReady(buffer: ByteBuffer): Boolean = {
        val write = stdinQ.take
          .flatMap { vector =>
            F.delay {
              buffer.put(vector.toArray)
              buffer.flip()
            }
          }
          .flatMap(_ => stdinQ.size)
          .map(_ > 0)

        // false means we have nothing else to write at this time
        var ret: Boolean = false
        try
          ret = dispatcher.unsafeRunSync(write)
        catch {
          case NonFatal(_) => () // TODO Handle error ?
        }

        ret
      }

      override def onExit(statusCode: Int): Unit =
        dispatcher.unsafeRunSync(
          statusCodeD.complete(statusCode) *> stdoutQ.offer(None) *> stderrQ.offer(None)
        )

      // Nu, wrapped, safe logic

      override def kill: F[Unit] = F.delay(proc.destroy(true))

      override def terminate: F[Unit] = F.delay(proc.destroy(false))

      override def pid: F[Int] = F.delay(proc.getPID)

      // fs2, safe logic

      override def statusCode: F[ExitCode] = statusCodeD.get.map(ExitCode(_))

      override def stdout: Stream[F, Byte] =
        Stream
          .fromQueueNoneTerminated(stdoutQ)
          .flatMap(v => Stream.chunk(Chunk.byteVector(v)))

      override def stderr: Stream[F, Byte] =
        Stream
          .fromQueueNoneTerminated(stderrQ)
          .flatMap(v => Stream.chunk(Chunk.byteVector(v)))

      override def stdin: Pipe[F, Byte, Unit] =
        _.chunks
          .map(_.toByteVector)
          .evalMap(stdinQ.offer)
          .evalMap { _ =>
            // TODO Trigger wantWrite only if queue was empty before this element
            F.delay(proc.wantWrite())
          }
          .onFinalize(F.delay(proc.closeStdin(false)))
    }
}
