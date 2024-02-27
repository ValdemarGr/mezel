package mezel

import cats.implicits._
import cats.effect._
import cats.effect.std._

trait CancellableTask {
  def startFiber[A](id: String, task: IO[A]): IO[FiberIO[A]]

  def start[A](id: String, task: IO[A]): IO[IO[Option[A]]] =
    startFiber[Option[A]](id, task.map(_.some)).map(_.joinWith(IO.pure(none)))

  def cancel(id: String): IO[Unit]
}

object CancellableTask {
  def make: Resource[IO, CancellableTask] = {
    Supervisor[IO](await = false).evalMap { sup =>
      IO.ref(Map.empty[String, IO[Unit]])
        .map(ref =>
          new CancellableTask {
            def startFiber[A](id: String, task: IO[A]): IO[FiberIO[A]] =
              IO.deferred[Unit].flatMap { cancelMe =>
                val t = IO
                  .race(task, cancelMe.get >> IO.canceled >> IO.never[A])
                  .map(_.merge)
                  .guarantee(ref.update(_ - id))
                val doCancel = cancelMe.complete(()).void
                ref.update(_ + (id -> doCancel)) *> sup.supervise(t)
              }

            def cancel(id: String): IO[Unit] =
              ref.get.flatMap(_.get(id).sequence_)
          }
        )
    }
  }
}
