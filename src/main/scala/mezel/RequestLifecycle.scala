package mezel

import cats.implicits._
import cats.effect._

trait RequestLifecycle {
  def run[A](id: RpcId, request: IO[A]): IO[Option[A]]

  def cancel(id: RpcId): IO[Unit]
}

object RequestLifecycle {
  def make(ct: CancellableTask): RequestLifecycle = {
    new RequestLifecycle {
      override def run[A](id: RpcId, request: IO[A]): IO[Option[A]] =
        ct.start(id.value.toString(), request).flatten

      override def cancel(id: RpcId): IO[Unit] =
        ct.cancel(id.value.toString())
    }
  }
}
