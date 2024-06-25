package mezel

import cats.data._
import cats.implicits._
import scala.concurrent.duration._
import cats.effect.{Trace => _, *}
import fs2.io.file._
import fs2._
import cats._
import cats.effect.std.Supervisor
import fs2.concurrent.SignallingRef

class GraphWatcher(trace: Trace) {
  enum EvType {
    case Created, Deleted, Modified
  }

  def extractEvent(event: Watcher.Event): Option[(EvType, Path)] =
    event match {
      case Watcher.Event.Overflow(_)       => None
      case Watcher.Event.NonStandard(_, _) => None
      case Watcher.Event.Created(p, _)     => Some((EvType.Created, p))
      case Watcher.Event.Deleted(p, _)     => Some((EvType.Deleted, p))
      case Watcher.Event.Modified(p, _)    => Some((EvType.Modified, p))
    }

  def simplyfyEvent(et0: EvType, et1: EvType): Option[EvType] = {
    import EvType._
    (et0, et1) match {
      case (_, Deleted)  => None
      case (_, Modified) => Some(Modified)
      // must be deleted -> created = modified since any other combination is a bug
      case (_, Created) => Some(Modified)
    }
  }

  def didChange(et: EvType, p: Path): IO[Boolean] = {
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
    for {
      _ <- Stream.eval {
        trace.logger.logInfo(s"Starting watcher for ${paths.map(p => s"'${p.toString}'").mkString_(", ")}")
      }
      sup <- Stream.resource(Supervisor[IO](await = false))
      state <- Stream.eval(IO.ref(Map.empty[Path, (EvType, IO[Unit])]))
      changeSig <- Stream.eval(SignallingRef.of[IO, Unit](()))
      bg = Stream
        .emits(paths.toList)
        .map(Files[IO].watch)
        .parJoinUnbounded
        .map(extractEvent(_))
        .unNone
        .filter { case (_, p) => !(p.toString.contains("bazel-") || p.toString.contains("/.")) }
        .evalMap { case (et, p) =>
          val onTimeout = state
            .modify(m => (m - p, m.get(p)))
            .flatMap(_.traverse_ { case (et, _) =>
              didChange(et, p)
                .flatTap { b =>
                  trace.logger.logInfo(s"did build change form looking at the interesting events? ${b} for $p")
                }
                .flatTap {
                  case true  => changeSig.set(())
                  case false => IO.unit
                }
            })

          trace.logger.logInfo(s"Event ${et} for ${p}") *>
            Deferred[IO, Unit].flatMap { cancel =>
              val timeout = IO.sleep(400.millis) *> IO.race(cancel.get, onTimeout)

              state
                .modify { m =>
                  m.get(p) match {
                    case None => (m + (p -> (et, cancel.complete(()).void)), Some(sup.supervise(timeout).void))
                    case Some((et0, cancel)) =>
                      simplyfyEvent(et0, et) match {
                        case None      => (m - p, Some(cancel))
                        case Some(et2) => (m + (p -> (et2, cancel)), None)
                      }
                  }
                }
                .flatMap(_.sequence_)
            }
        }
      _ <- changeSig.discrete.tail.concurrently(bg)
    } yield ()
  }
}
