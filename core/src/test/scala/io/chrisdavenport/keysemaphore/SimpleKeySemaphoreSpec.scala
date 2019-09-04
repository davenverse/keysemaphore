package io.chrisdavenport.keysemaphore

import org.specs2.mutable.Specification
import cats.effect._
import scala.concurrent.ExecutionContext.global

class SimpleKeySemaphoreSpec extends Specification {
  "KeySemaphore" should {
    "only take the maximum values per key" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- SimpleKeySemaphore.of[IO, Unit]{_: Unit => 1L}
        first <- sem.tryAcquire.run(())
        second <- sem.tryAcquire.run(())
      } yield (first, second)
      test.unsafeRunSync() must_=== ((true, false))
    }

    "not be affected by other keys" in {
      implicit val CS = IO.contextShift(global)
      val test = for {
        sem <- SimpleKeySemaphore.of[IO, Int]{_: Int => 1L}
        first <- sem.tryAcquire.run(1)
        second <- sem.tryAcquire.run(2)
        third <- sem.tryAcquire.run(1)
      } yield (first, second, third)
      test.unsafeRunSync() must_=== ((true, true, false))
    }
  }

}