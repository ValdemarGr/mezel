package mezel

import cats.implicits._
import cats.effect._
import cats.effect.std.Mutex

trait CancellableTask {
  def startFiber[A](id: String, task: IO[A]): IO[FiberIO[A]]

  def start[A](id: String, task: IO[A]): IO[IO[Option[A]]] =
    startFiber[Option[A]](id, task.map(_.some)).map(_.joinWith(IO.pure(none)))

  def cancel(id: String): IO[Unit]
}

object CancellableTask {
  def make: Resource[IO, CancellableTask] = {
    Resource.eval(Mutex[IO]).flatMap { lck =>
      Resource.make(IO.ref(Map.empty[String, IO[Unit]]))(_.get.flatMap(_.values.toList.sequence_)).map { ref =>
        new CancellableTask {
          def startFiber[A](id: String, task: IO[A]): IO[FiberIO[A]] =
            IO.uncancelable { poll =>
              lck.lock.surround {
                ref.modify(m => (m - id, m.get(id).sequence_)).flatten *>
                  poll(task).start.flatTap(fib => ref.update(_ + (id -> fib.cancel)))
              }
            }

          def cancel(id: String): IO[Unit] =
            lck.lock.surround {
              ref.get.flatMap(_.get(id).sequence_)
            }
        }
      }
    }
  }
}
