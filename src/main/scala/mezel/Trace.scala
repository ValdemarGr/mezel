package mezel

import scala.concurrent.duration._
import fs2.Stream
import cats.effect._
import cats.implicits._

trait Trace {
  def trace[A](name: String)(fa: IO[A]): IO[A]

  def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A]

  def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A]

  def nested(next: String): Trace

  def logger: Logger
}

object Trace {
  def in(context: String, logger: Logger): Trace = {
    val logger0 = logger
    def ctx(name: String) = s"${context}: ${name}"

    def msg(name: String)(startTime: FiniteDuration) =
      IO.monotonic.flatMap { endTime =>
        logger.logLog(s"${ctx(name)} took ${(endTime - startTime).toMillis}ms")
      }

    new Trace {
      def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A] =
        Resource.eval(IO.monotonic).flatMap { start =>
          fa <* Resource.onFinalize(msg(name)(start))
        }

      def trace[A](name: String)(fa: IO[A]): IO[A] =
        IO.monotonic.flatMap(start => fa <* msg(name)(start))

      def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A] =
        Stream.eval(IO.monotonic).flatMap { start =>
          fa ++ Stream.exec(msg(name)(start))
        }

      def nested(next: String): Trace = in(s"${context} -> ${next}", logger)

      def logger: Logger = new Logger {
        override def logError(msg: String): IO[Unit] = 
          logger0.logError(ctx(msg))

        override def printStdOut(msg: String): IO[Unit] = 
          logger0.printStdOut(ctx(msg))

        override def logLog(msg: String): IO[Unit] = 
          logger0.logLog(ctx(msg))

        override def printStdErr(msg: String): IO[Unit] = 
          logger0.printStdErr(ctx(msg))

        override def logWarn(msg: String): IO[Unit] = 
          logger0.logWarn(ctx(msg))

        override def logInfo(msg: String): IO[Unit] = 
          logger0.logInfo(ctx(msg))
      }
    }
  }
}
