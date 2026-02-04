package mezel

import cats.effect.std.*
import cats.effect.*

final case class MtxRef[A](
    guard: Mutex[IO],
    @volatile private var _state: A // mutex should force a volatile READ, but it is not in the specification
) {
  def modify[B](f: A => (A, B)): IO[B] =
    guard.lock.surround {
      IO {
        val current = _state
        val (newState, result) = f(current)
        _state = newState
        result
      }
    }

  def update(f: A => A): IO[Unit] =
    modify { s =>
      val newState = f(s)
      (newState, ())
    }

  def mut(f: A => Unit): IO[Unit] =
    guard.lock.surround {
      IO {
        val current = _state
        f(current)
      }
    }

  def use[B](f: A => IO[B]): IO[B] =
    guard.lock.surround {
      IO(_state).flatMap(f)
    }
}

object MtxRef {
  def make[A](initial: A): IO[MtxRef[A]] =
    Mutex[IO].map { mtx =>
      MtxRef(mtx, initial)
    }
}
