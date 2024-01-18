package mezel

import cats.data._
import cats.implicits._
import scala.concurrent.duration._
import cats.effect.{Trace => _, *}
import fs2.io.file._
import fs2._
import cats._

class GraphWatcher(
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

  def startWatcher(
      paths: NonEmptyList[Path]
  ): Stream[IO, Unit] = {
    Stream.eval {
      trace.logger.logInfo(s"Starting watcher for ${paths.map(p => s"'${p.toString}'").mkString_(", ")}")
    } >>
      Stream
        .emits(paths.toList)
        .map(Files[IO].watch)
        .parJoinUnbounded
        .groupWithin(1024, 250.millis)
        .map(extractEvents(_).filter { case (_, p) => !(p.toString.contains("bazel-") || p.toString.contains("/.")) })
        .filter(_.nonEmpty)
        .evalMap { events =>
          val interesting = eliminateRedundant(events)
          trace.logger.logInfo(s"${events.size} events unconsed ${events}") *>
            trace.logger.logInfo(s"eliminated ${events.size - interesting.size} uninteresting events ${interesting}") *>
            buildDidChange(interesting).attempt.flatMap {
              case Left(err) =>
                trace.logger.logError(s"error while checking if build changed: ${err}").as(true)
              case Right(bdc) =>
                trace.logger.logInfo(s"did build change form looking at the interesting events? ${bdc}").as(bdc)
            }
        }
        .filter(identity)
        .void
  }
}
