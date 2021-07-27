---
layout: home

---

# keysemaphore - Keyed Semaphores [![Build Status](https://travis-ci.com/ChristopherDavenport/keysemaphore.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/keysemaphore) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/keysemaphore_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/keysemaphore_2.12)

## Quick Start

To use keysemaphore in an existing SBT project with Scala 2.12, 2.13, or 3.0, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "keysemaphore" % "<version>"
)
```

## Example

Quick Imports

```tut:silent
import cats.effect._
import io.chrisdavenport.keysemaphore.KeySemaphore
implicit val CS = IO.contextShift(scala.concurrent.ExecutionContext.global)
```

Then we build some operations

```tut
// Second Action Can't Get Permit
val action1 = {
  for {
    sem <- KeySemaphore.of[IO, Unit]{_ => 1L}
    first <- sem(()).tryAcquire
    second <- sem(()).tryAcquire
  } yield (first, second)
}

action1.unsafeRunSync

// Not Affected By Other Keys
val action2 = {
  for {
    sem <- KeySemaphore.of[IO, Int]{_: Int => 1L}
    first <- sem(1).tryAcquire
    second <- sem(2).tryAcquire
    third <- sem(1).tryAcquire
  } yield (first, second, third)
}

action2.unsafeRunSync

// Releases Based on Keys
// This is space safe, so when the semaphore returns to the
// default it removes it from the internal so memory is not
// leaked per key
val action3 = {
  for {
    sem <- KeySemaphore.of[IO, Int]{_: Int => 1L}
    first <- sem(1).tryAcquire
    second <- sem(1).tryAcquire
    _ <- sem(1).release
    third <- sem(1).tryAcquire
  } yield (first, second, third)
}

action3.unsafeRunSync
```
