package mezel

import cats.implicits._
import scala.concurrent.duration._
import cats.effect._
import fs2.io.file._
import fs2._

trait GraphWatcher {}

object GraphWatcher {
  // def watchAll(root: Path) = {
  //   // IO.ref()
  //   Files[IO].watch(root).groupWithin(1024, 50.millis).evalMap { events =>
  //     val relevant = events.mapFilter {
  //       case Watcher.Event.Overflow(_)       => None
  //       case Watcher.Event.NonStandard(_, _) => None
  //       case Watcher.Event.Created(p, _)     => Some(p)
  //       case Watcher.Event.Deleted(p, _)     => Some(p)
  //       case Watcher.Event.Modified(p, _)    => Some(p)
  //     }

  //     ???
  //     // if (relevant.isEmpty) IO.unit
  //     // else {
  //     // }
  //   }
  // }

  // def computeHash(
  //   buildFile: Path,
  //   activeBuildTargets: Map[Path, BuildTargetFiles]
  // ): IO[Option[String]] = {
  //   ???
  //   // Files[IO].read
  // }

  // def go(
  //     buildFiles: Set[Path],
  //     events: Chunk[Watcher.Event],
  //     buildHash: Set[String]
  // ) = { // : Pipe[IO, Watcher.Event, Path] = { stream =>
  //   // val parentDirs = buildFiles.map(_.parent).collect{ case Some(x) => x }
  //   // val parentDirsLst = parentDirs.toList

  //   // stream.groupWithin(1024, 50.millis).map { events =>
  //   val paths = events.mapFilter {
  //     case Watcher.Event.Created(p, _)     => Some(p)
  //     case Watcher.Event.Deleted(p, _)     => Some(p)
  //     case Watcher.Event.Modified(p, _)    => Some(p)
  //     case Watcher.Event.Overflow(_)       => None
  //     case Watcher.Event.NonStandard(_, _) => None
  //   }

  //   lazy val (changedBuildFiles, changedOtherFiles) = paths.partitionEither { x =>
  //     if (buildFiles.contains(x)) Right(x) else Left(x)
  //   }

  //   // As a heuristic we start by looking at the build file that shares the same parent directory
  //   // For now let's just reimport all build files
  //   Stream.iterable(buildFiles).evalMap { p =>
  //     p
  //     ???
  //   }
  //   // }

  //   ???
  // }
}
