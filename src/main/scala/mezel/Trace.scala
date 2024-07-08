package mezel

import cats._
import scala.concurrent.duration._
import fs2.Stream
import cats.effect._
import cats.implicits._

trait Trace {
  def trace[A](name: String)(fa: IO[A]): IO[A]

  def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A]

  def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A]

  def nested(next: String): IO ~> IO

  def logger: Logger
}

object Trace {
  def in(context: String, logger: Logger): IO[Trace] =
    IOLocal[String](context).map(fromLocal(logger, _))

  def fromLocal(logger: Logger, local: IOLocal[String]): Trace = {
    val logger0 = logger

    def ctx(name: String) = local.get.map(ctx => s"${ctx}: ${name}")

    def endMsg(name: String)(startTime: FiniteDuration) =
      IO.monotonic.flatMap { endTime =>
        ctx(name).flatMap { c =>
          logger.logLog(s"ended task: ${c} took ${(endTime - startTime).toMillis}ms")
        }
      }

    def startMsg(name: String) = ctx(name).flatMap { c =>
      logger.logLog(s"started task: ${c}")
    }

    new Trace {
      def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A] =
        Resource.eval(IO.monotonic).flatMap { start =>
          Resource.eval(startMsg(name)) *> fa <* Resource.onFinalize(endMsg(name)(start))
        }

      def trace[A](name: String)(fa: IO[A]): IO[A] =
        IO.monotonic.flatMap(start => startMsg(name) *> fa <* endMsg(name)(start))

      def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A] =
        Stream.eval(IO.monotonic).flatMap { start =>
          Stream.exec(startMsg(name)) ++ fa ++ Stream.exec(endMsg(name)(start))
        }

      def nested(next: String): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            local.get.flatMap { s =>
              local.set(s"${s} -> ${next}") *> fa <* local.set(s)
            }
        }

      def logger: Logger = new Logger {
        override def logError(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.logError

        override def printStdOut(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.printStdOut

        override def logLog(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.logLog

        override def printStdErr(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.printStdErr

        override def logWarn(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.logWarn

        override def logInfo(msg: String): IO[Unit] =
          ctx(msg) >>= logger0.logInfo
      }
    }
  }
}
