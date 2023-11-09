package mezel

import cats.effect._

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    fs2.io.stdinUtf8[IO](4096).evalMap(IO.println(_)).compile.drain
  }
}
