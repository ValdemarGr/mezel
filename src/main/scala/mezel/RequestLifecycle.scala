package mezel

import cats.implicits._
import cats.effect._

trait RequestLifecycle {
  def run[A](id: RpcId, request: IO[A]): IO[Option[A]]

  def cancel(id: RpcId): IO[Unit]
}

object RequestLifecycle {
  def make: IO[RequestLifecycle] = {
    IO.ref(Map.empty[RpcId, IO[Unit]]).map { ref =>
      new RequestLifecycle {
        override def run[A](id: RpcId, request: IO[A]): IO[Option[A]] =
          IO.deferred[Unit].flatMap { cancelMe =>
            ref.update(_ + (id -> cancelMe.complete(()).void)) *>
              IO.race(request.map(_.some), cancelMe.get.as(None)).map(_.merge)
          }

        override def cancel(id: RpcId): IO[Unit] = ???
      }
    }
  }
}
