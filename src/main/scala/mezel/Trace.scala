package mezel

import scala.concurrent.duration._
import fs2.Stream
import cats.effect._
import cats.implicits._

trait Trace {
  def trace[A](name: String)(fa: IO[A]): IO[A]

  def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A]

  def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A]
}

object Trace {
  def in(context: String, logger: Logger) = {
    def msg(name: String)(startTime: FiniteDuration) =
      IO.monotonic.flatMap { endTime =>
        logger.logLog(s"${context}: ${name} took ${(endTime - startTime).toMillis}ms")
      }

    new Trace {
      def traceResource[A](name: String)(fa: Resource[IO, A]): Resource[IO, A] = 
        Resource.eval(IO.monotonic).flatMap{ start =>
          fa <* Resource.onFinalize(msg(name)(start))
        }

      def trace[A](name: String)(fa: IO[A]): IO[A] =
        IO.monotonic.flatMap(start => fa <* msg(name)(start))

      def traceStream[A](name: String)(fa: Stream[IO, A]): Stream[IO, A] = 
        Stream.eval(IO.monotonic).flatMap{ start =>
          fa ++ Stream.exec(msg(name)(start))
        }
    }
  }
}
