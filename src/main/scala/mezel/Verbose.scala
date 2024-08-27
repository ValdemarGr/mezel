package mezel

import cats.effect._

trait Verbose {
  def verbose(fa: => IO[Unit]): IO[Unit]

  def debug(fa: => IO[Unit]): IO[Unit]

  def trace(fa: => IO[Unit]): IO[Unit]
}

object Verbose {
  def make(verbosity: Verbosity): Verbose = new Verbose {
    override def verbose(fa: => IO[Unit]): IO[Unit] = 
      IO.whenA(verbosity.level >= Verbosity.Verbose.level)(fa)

    override def debug(fa: => IO[Unit]): IO[Unit] = 
      IO.whenA(verbosity.level >= Verbosity.Debug.level)(fa)

    override def trace(fa: => IO[Unit]): IO[Unit] = 
      IO.whenA(verbosity.level >= Verbosity.Trace.level)(fa)
  }
}
