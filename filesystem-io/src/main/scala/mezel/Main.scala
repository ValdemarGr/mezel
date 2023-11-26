package mezel

import cats.implicits.*
import cats.effect.*
import fs2.io.file.*

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    val read = Path("/tmp/from-metals")
    val write = Path("/tmp/to-metals")

    val readStdin =
      fs2.io.stdin[IO](4096).through(Files[IO].writeAll(read)).compile.drain

    val writeStdout =
      Files[IO].tail(write).through(fs2.io.stdout[IO]).compile.drain

    Files[IO].deleteIfExists(read) *>
      Files[IO].deleteIfExists(write) *>
      Files[IO].createFile(read) *>
      Files[IO].createFile(write) *>
      (readStdin, writeStdout).parTupled.void
  }
}
