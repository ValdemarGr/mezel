package mezel

import cats.implicits._
import cats.effect._
import org.typelevel.vault._
import cats.effect.std.Mutex

final case class ValueCache[A](
    state: Ref[IO, Option[A]],
    lock: Mutex[IO]
)

final case class CacheKey[A](k: Key[ValueCache[A]])

object CacheKey {
  def make[A]: IO[CacheKey[A]] = Key.newKey[IO, ValueCache[A]].map(CacheKey(_))
}

trait Cache {
  def clear: IO[Unit]

  def cached[A](key: CacheKey[A])(force: IO[A]): IO[A]
}

object Cache {
  def make: IO[Cache] = {
    IO.ref(Vault.empty).flatMap { state =>
      Mutex[IO].map { mod =>
        new Cache {
          def clear: IO[Unit] = mod.lock.surround {
            state.update(_.empty)
          }

          def cached[A](key: CacheKey[A])(force: IO[A]): IO[A] = {
            val getVc = mod.lock.surround {
              state.get.map(_.lookup(key.k)).flatMap {
                case Some(vc) => IO.pure(vc)
                case None =>
                  (IO.ref[Option[A]](None), Mutex[IO])
                    .mapN(ValueCache.apply)
                    .flatTap(vc => state.update(_.insert(key.k, vc)))
              }
            }

            getVc.flatMap { vc =>
              vc.lock.lock.surround {
                vc.state.get.flatMap {
                  case Some(x) => IO.pure(x)
                  case None    => force.flatTap(x => vc.state.set(Some(x)))
                }
              }
            }
          }
        }
      }
    }
  }
}
