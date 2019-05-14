/*
package io.chrisdavenport.keysemaphore

import cats.implicits._
import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._

object MaxKeySemaphore {

  def of[F[_]: Concurrent, K](maxTotal: Long, f: K => Long): F[Semaphore[Kleisli[F, K, ?]]] = for {
    maxSem <- Semaphore[F](maxTotal)
    keySem <- KeySemaphore.of(f)
  } yield new MaxKeySem[F, K](maxSem, keySem)

  private class MaxKeySem[F[_]: Concurrent, K](
    private val maxSem: Semaphore[F],
    private val keySem: Semaphore[Kleisli[F, K, ?]]
  ) extends Semaphore[Kleisli[F, K, ?]]{
    def available: Kleisli[F, K, Long] = ???
    def count: Kleisli[F, K, Long] = ???
    def acquireN(n: Long): Kleisli[F, K, Unit] = ???
    def tryAcquireN(n: Long): Kleisli[F, K, Boolean] = Kleisli{k: K => 
      maxSem.tryAcquireN(n)
        .ifM(
          keySem.tryAcquireN(n).run(k)
            .ifM(
              true.pure[F],
              maxSem.releaseN(n).as(false)
            ),
          false.pure[F]
        )
    }
    def releaseN(n: Long): Kleisli[F, K, Unit] = ???
    def withPermit[A](t: Kleisli[F, K, A]): Kleisli[F, K, A] = ???
  }
}
*/