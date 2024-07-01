package mezel

import cats.implicits._
import cats.effect.{Trace => _, _}

trait RequestLifecycle {
  def run[A](t: Trace, id: RpcId, request: IO[A]): IO[Option[A]]

  def cancel(t: Trace, id: RpcId): IO[Unit]
}

object RequestLifecycle {
  def make(ct: CancellableTask): RequestLifecycle = {
    new RequestLifecycle {
      override def run[A](t: Trace, id: RpcId, request: IO[A]): IO[Option[A]] =
        ct.start(t, id.value.toString(), request).flatten

      override def cancel(t: Trace, id: RpcId): IO[Unit] =
        ct.cancel(t, id.value.toString())
    }
  }
}
