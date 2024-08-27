package mezel

import cats.implicits.*
import fs2.*
import cats.effect.*
import fs2.io.file.*
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.*

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

  val verbose: Opts[Verbosity] = Opts.flags(
    "verbose",
    "Enable verbose logging and tracing.",
    short="v"
  ).map(Verbosity.fromInt).withDefault(Verbosity.Normal)

  def main: Opts[IO[ExitCode]] = (fsFlag, buildArgsFlag, aqueryArgsFlag, verbose)
    .mapN { case (fs, buildArgs, aqueryArgs, verbose) =>
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

    BSPServerDeps.make.use{ deps =>
      val bsl = new BSPServerLifecycle(buildArgs, aqueryArgs, deps, verbose)
      bsl.start(stdin, stdout).as(ExitCode.Success)
    }
  }
}
