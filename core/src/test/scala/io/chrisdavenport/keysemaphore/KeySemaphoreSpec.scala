package io.chrisdavenport.keysemaphore

import cats.effect.IO
import munit.CatsEffectSuite

class KeySemaphoreSpec extends CatsEffectSuite {
  test("only take the maximum values per key") {
    val obtained = for {
      sem <- KeySemaphore.of[IO, Unit] { _: Unit => 1L }
      first <- sem(()).tryAcquire
      second <- sem(()).tryAcquire
    } yield (first, second)
    val expected = (true, false)
    assertIO(obtained, expected)
  }

  test("not be affected by other keys") {
    val obtained = for {
      sem <- KeySemaphore.of[IO, Int] { _: Int => 1L }
      first <- sem(1).tryAcquire
      second <- sem(2).tryAcquire
      third <- sem(1).tryAcquire
    } yield (first, second, third)
    val expected = (true, true, false)
    assertIO(obtained, expected)
  }

  test("restore on finished") {
    val obtained = for {
      sem <- KeySemaphore.of[IO, Int] { _: Int => 1L }
      first <- sem(1).tryAcquire
      second <- sem(1).tryAcquire
      _ <- sem(1).release
      third <- sem(1).tryAcquire
    } yield (first, second, third)
    val expected = (true, false, true)
    assertIO(obtained, expected)
  }

  test("not allow more than the key") {
    val obtained = for {
      sem <- KeySemaphore.of[IO, Int] { _: Int => 1L }
      first <- sem(1).tryAcquire
      _ <- sem(1).releaseN(10)
      second <- sem(1).tryAcquire
      third <- sem(1).tryAcquire
    } yield (first, second, third)
    val expected = (true, true, false)
    assertIO(obtained, expected)
  }
}
