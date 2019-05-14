package io.chrisdavenport.keysemaphore

// import cats._
import cats.implicits._
import cats.data._
import cats.effect._
import cats.effect.concurrent._

object KeySemaphore {

  def of[F[_]: Concurrent, K](f: K => Long): F[Semaphore[Kleisli[F, K, ?]]] = for {
    map <- Ref[F].of(Map.empty[K, Semaphore[F]])
  } yield new SimpleKeySemaphore[F, K](map, f)

  private class SimpleKeySemaphore[F[_], K](
    private val semRef: Ref[F, Map[K, Semaphore[F]]],
    private val f: K => Long
  )(implicit F: Concurrent[F]) extends Semaphore[Kleisli[F, K, ?]]{

    private def getOrMake(k: K): F[Semaphore[F]] = for {
      semMap <- semRef.get
      sem <- semMap.get(k)
        .fold(
          for {
            newSem <- Semaphore[F](f(k))
            out <- semRef.modify(m => m.get(k).fold(((m + (k -> newSem)), newSem))(sem => (m, sem)))
          } yield out
          
        )(_.pure[F])
        
      } yield sem
    def available: Kleisli[F, K, Long] = Kleisli{k: K => 
      for {
        sem <- getOrMake(k)
        avail <- sem.available
      } yield avail
    }

    def count: Kleisli[F, K, Long] = Kleisli{k: K => 
      for {
        sem <- getOrMake(k)
        avail <- sem.count
      } yield avail
    }

    def acquireN(n: Long): Kleisli[F, K, Unit] = Kleisli{k: K => 
      for {
        sem <- getOrMake(k)
        acq <- sem.acquireN(n)
      } yield acq
    }

    def tryAcquireN(n: Long): Kleisli[F, K, Boolean] = Kleisli{k: K => 
      for {
        sem <- getOrMake(k)
        acq <- sem.tryAcquireN(n)
      } yield acq
    }

    def releaseN(n: Long): Kleisli[F, K, Unit] = Kleisli{k: K => 
      for {
        sem <- getOrMake(k)
        rel <- sem.releaseN(n)
      } yield rel
    }

    def withPermit[A](t: Kleisli[F, K, A]): Kleisli[F, K, A] = Kleisli{k:K => 
      for {
        sem <- getOrMake(k)
        out <- sem.withPermit(t.run(k))
      } yield out
    }
  }
}