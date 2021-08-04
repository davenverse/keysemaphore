package io.chrisdavenport.keysemaphore

import org.specs2.mutable.Specification
import cats.effect._
import scala.concurrent.ExecutionContext.global

class KeySemaphoreSpec extends Specification {
  "KeySemaphore" should {
    "only take the maximum values per key" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- KeySemaphore.of[IO, Unit]{(_: Unit) => 1L}
        first <- sem(()).tryAcquire
        second <- sem(()).tryAcquire
      } yield (first, second)
      test.unsafeRunSync() must_=== ((true, false))
    }

    "not be affected by other keys" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- KeySemaphore.of[IO, Int]{(_: Int) => 1L}
        first <- sem(1).tryAcquire
        second <- sem(2).tryAcquire
        third <- sem(1).tryAcquire
      } yield (first, second, third)
      test.unsafeRunSync() must_=== ((true, true, false))
    }

    "restore on finished" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- KeySemaphore.of[IO, Int]{(_: Int) => 1L}
        first <- sem(1).tryAcquire
        second <- sem(1).tryAcquire
        _ <- sem(1).release
        third <- sem(1).tryAcquire
      } yield (first, second, third)
      test.unsafeRunSync() must_=== ((true,false, true))
    }

    "not allow more than the key" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- KeySemaphore.of[IO, Int]{(_: Int) => 1L}
        first <- sem(1).tryAcquire
        _ <- sem(1).releaseN(10)
        second <- sem(1).tryAcquire
        third <- sem(1).tryAcquire
      } yield (first, second, third)
      test.unsafeRunSync() must_=== ((true, true, false))
    }
  }

  // def printState(sem: KeySemaphore.AbstractKeySemaphore[IO, _]): IO[Unit] = 
  //   sem.getState.flatMap(st => IO.delay(println(st)))

}