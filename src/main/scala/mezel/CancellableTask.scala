package mezel

import cats.implicits._
import cats.effect.{Trace => _, _}
import cats.effect.std.Mutex

trait CancellableTask {
  def startFiber[A](t: Trace, id: String, task: IO[A]): IO[FiberIO[A]]

  def start[A](t: Trace, id: String, task: IO[A]): IO[IO[Option[A]]] =
    startFiber[Option[A]](t, id, task.map(_.some)).map(_.joinWith(IO.pure(none)))

  def cancel(t: Trace, id: String): IO[Unit]
}

object CancellableTask {
  def make: Resource[IO, CancellableTask] = {
    Resource.eval(Mutex[IO]).flatMap { lck =>
      Resource.make(IO.ref(Map.empty[String, IO[Unit]]))(_.get.flatMap(_.values.toList.sequence_)).map { ref =>
        new CancellableTask {
          def startFiber[A](t: Trace, id: String, task: IO[A]): IO[FiberIO[A]] =
            t.trace(s"starting fiber $id, awaiting lock") {
              IO.uncancelable { poll =>
                lck.lock.surround {
                  ref.modify(m => (m - id, m.get(id).sequence_)).flatten *>
                    t.trace(s"got lock, starting fiber $id") {
                      poll {
                        task.onError(e => t.logger.logError(s"Fiber $id failed with $e"))
                      }
                    }.start
                      .flatTap(fib => ref.update(_ + (id -> fib.cancel)))
                }
              }
            }

          def cancel(t: Trace, id: String): IO[Unit] =
            t.trace(s"cancelling $id, awaiting lock") {
              lck.lock.surround {
                t.trace(s"got lock, cancelling $id") {
                  ref.get.flatMap(_.get(id).sequence_)
                }
              }
            }
        }
      }
    }
  }
}
