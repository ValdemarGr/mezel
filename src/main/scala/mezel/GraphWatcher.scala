package mezel

import cats.implicits._
import scala.concurrent.duration._
import cats.effect.{Trace => _, *}
import fs2.io.file._
import fs2._
import cats._

class GraphWatcher(
    // root: SafeUri,
    // tasks: Tasks,
    trace: Trace
) {
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

    Chunk.from {
      m.toList.mapFilter { case (k, vs) =>
        val sorted = vs.sortBy { case (_, i) => i }
        // some editors write a file by first deleting it and then creating it
        // if first is a deleted and it ends with a created, it is a modified
        // if the first event is created and last is deleted, we ignore it
        val (hdEt, _) = sorted.head
        val (lastEt, _) = sorted.last

        (hdEt, lastEt) match {
          case (EvType.Deleted, EvType.Created) => Some((EvType.Modified, k))
          case (EvType.Created, EvType.Deleted) => None
          case _                                => Some((lastEt, k))
        }
      }
    }
  }

  def filterFSEvents(events: Chunk[(EvType, Path)]): IO[Chunk[(EvType, Path)]] =
    events.filterA {
      case (EvType.Modified | EvType.Created, p) => Files[IO].exists(p)
      case (EvType.Deleted, p)                   => Files[IO].exists(p).map(!_)
    }

  def buildDidChange(events: Chunk[(EvType, Path)]): IO[Boolean] =
    events.existsM { case (et, p) =>
      val fn = p.fileName.toString
      // If it's a modified scala file, we can just skip it, everything else causes a build change
      val isSourceMod = (fn.endsWith(".scala") || fn.endsWith(".sc")) && et == EvType.Modified
      // https://github.com/vim/vim/issues/5145#issuecomment-547768070
      // Vim creates files with ~ at the end and files named 4913 (and incrementing by 123)
      val isVimFile = fn.endsWith("~") || fn.toIntOption.exists(x => (x - 4913) % 123 === 0)

      val toSkip = isSourceMod || isVimFile

      if (toSkip) IO.pure(false)
      else {
        et match {
          // If it is created but doesn't exist, we can skip it
          case EvType.Modified | EvType.Created => Files[IO].exists(p)
          // If it is deleted but exists, we can skip it
          case EvType.Deleted => Files[IO].exists(p).map(!_)
        }
      }
    }

  // metals just needs to know if it needs to reimport
  // final case class WatchResult(
  //     modified: Chunk[String],
  //     deleted: Set[String],
  //     created: Set[String]
  // )
  // object WatchResult {
  //   given Monoid[WatchResult] = new Monoid[WatchResult] {
  //     def empty: WatchResult = WatchResult(Chunk.empty, Set.empty, Set.empty)
  //     def combine(x: WatchResult, y: WatchResult): WatchResult =
  //       WatchResult(
  //         x.modified ++ y.modified,
  //         x.deleted ++ y.deleted,
  //         x.created ++ y.created
  //       )
  //   }
  // }
  // def computeEvents(
  //     prevLabels: Set[String],
  //     labels: Set[String],
  //     events: Chunk[(EvType, Path)]
  // ): IO[WatchResult] = {
  //   val r = uriToPath(root)
  //   // This handles creation/deletion of BUILD files
  //   val createdLabels = labels -- prevLabels
  //   val deletedLabels = prevLabels -- labels

  //   // We can only find changes in the workspace (since symlink (execroot) changes are really hard)
  //   // Thus we can infer any BUILD location by just using the label
  //   val localPaths = labels.toList.mapFilter { x =>
  //     val tl =
  //       if (x.startsWith("@//")) Some(x.drop(3))
  //       else if (x.startsWith("//")) Some(x.drop(2))
  //       else None

  //     tl.map(y => r / Path(y.takeWhile(_ =!= ':')) -> x)
  //   }.toMap

  //   sealed trait WatchTask
  //   object WatchTask {
  //     final case class BuildModification(label: String) extends WatchTask
  //     final case class FileModification(p: Path) extends WatchTask
  //   }

  //   val ys = events.mapFilter { case (et, p) =>
  //     val fn = p.fileName.toString
  //     if (fn === "BUILD.bazel" || fn === "BUILD") {
  //       et match {
  //         case EvType.Modified => p.parent.flatMap(localPaths.get).map(WatchTask.BuildModification(_))
  //         case _               => None
  //       }
  //     } else if (fn.endsWith(".scala") || fn.endsWith(".sc")) {
  //       et match {
  //         case EvType.Modified => None
  //         case _               => Some(WatchTask.FileModification(p))
  //       }
  //     } else Some(WatchTask.FileModification(p))
  //   }

  //   val fileMods: IO[List[String]] = ys
  //     .collect { case WatchTask.FileModification(p) => p }
  //     .toNel
  //     .toList
  //     .flatTraverse(xs => tasks.buildTargetRdeps(xs.map(p => r.relativize(p).toString).toList: _*))

  //   val buildMods: Chunk[String] = ys.collect { case WatchTask.BuildModification(p) => p }

  //   fileMods.map { xs =>
  //     WatchResult(
  //       (Chunk.from(xs) ++ buildMods).hashDistinct,
  //       deletedLabels,
  //       createdLabels
  //     )
  //   }
  // }

  def startWatcher(
      root: Path
      // init: BuildTargetCache
  ): IO[Unit] = {
    // val genLabels = trace.trace("genLabels") {
    //   tasks.buildTargetCache.map(_.buildTargets.map { case (k, _) => k }.toSet)
    // }
    // genLabels.map { init =>
    trace.logger.logInfo(s"Starting watcher for ${root}") >>
      Files[IO]
        .watch(root)
        .groupWithin(1024, 250.millis)
        .evalMap { events =>
          val interesting = eliminateRedundant(extractEvents(events))
          trace.logger.logInfo(s"${events.size} events unconsed ${events}") *>
            trace.logger.logInfo(s"eliminated ${events.size - interesting.size} uninteresting events ${interesting}") *>
            buildDidChange(interesting).flatTap { bdc =>
              trace.logger.logInfo(s"did build change form looking at the interesting events? ${bdc}")
            }
        }
        .exists(identity)
        .compile
        .drain
    // .evalMapAccumulate(init) { case (prev, events) =>
    //   trace.logger.logInfo(s"${events.size} events unconsed ${events}") >>
    //     genLabels
    //       .flatMap { nextLabels =>
    //         val interesting = eliminateRedundant(extractEvents(events))

    //         def run(events: Chunk[(EvType, Path)]) =
    //           trace.logger.logInfo(s"handling ${events.size} events after elimination ${events}") >>
    //             computeEvents(prev, nextLabels, events) <*
    //             trace.logger.logInfo(s"successfully handled ${events.size} events")

    //         run(interesting)
    //           .handleErrorWith { e =>
    //             trace.logger.logError(s"Error, recovering by handling events one at the time: ${e}") >>
    //               interesting.foldMapA { x =>
    //                 run(Chunk(x)).handleErrorWith { e =>
    //                   trace.logger.logError(s"Error, failed to handle event ${x}: ${e}").as(Monoid[WatchResult].empty)
    //                 }
    //               }
    //           }
    //           .tupleLeft(nextLabels)
    //       }
    // }
    // .map { case (_, wr) => wr }
    // }
  }
}
