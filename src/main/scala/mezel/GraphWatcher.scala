package mezel

import cats.implicits._
import scala.concurrent.duration._
import cats.effect._
import fs2.io.file._
import fs2._
import cats._

trait GraphWatcher {}

object GraphWatcher {
  enum EvType {
    case Created, Deleted, Modified
  }

  def extractEvents(events: Chunk[Watcher.Event]): Chunk[(EvType, Path)] =
    events.mapFilter {
      case Watcher.Event.Overflow(_)       => None
      case Watcher.Event.NonStandard(_, _) => None
      case Watcher.Event.Created(p, _)     => Some((EvType.Created, p))
      case Watcher.Event.Deleted(p, _)     => Some((EvType.Deleted, p))
      case Watcher.Event.Modified(p, _)    => Some((EvType.Modified, p))
    }

  def eliminateRedundant(events: Chunk[(EvType, Path)]): Chunk[(EvType, Path)] = {
    val m = events.toList.zipWithIndex.groupMap { case ((_, p), _) => p } { case ((et, _), i) => (et, i) }

    val etOrd = Order.by[EvType, Int] {
      // If there is a created/deleted event use that, otherwise use the modified event
      case EvType.Created | EvType.Deleted => 0
      case EvType.Modified                  => 1
    }

    val o2 = (etOrd, Order.reverse(Order[Int])).tupled

    val bestEvents = m.toList.map { case (p, xs) => 
      val (et, _) = xs.min(o2.toOrdering) 
      (et, p)
    }

    Chunk.from(bestEvents)
  }

  // BUILD.bazel add/remove -> diff this build target labels import with previous
  // BUILD.bazel modify -> is file defined at a known build taget label?
  // *.scala add/remove -> look in newly computed build targets for a ref to this file
  // *.scala modify -> do nothing, metals will handle it
  // other file change -> 
  //   - schema for source code generator, build and metals will detect change in input files
  //   - schema for jar code generator, must re-import
  //   - filegroup as data, do nothing
  //   - other?

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
