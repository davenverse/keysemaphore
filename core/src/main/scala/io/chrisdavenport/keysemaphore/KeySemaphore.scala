package io.chrisdavenport.keysemaphore

import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._

import scala.collection.immutable.Queue

object KeySemaphore {
  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def apply[F[_]](n: Long)(implicit F: Concurrent[F]): F[Semaphore[F]] = {
    assertNonNegative[F](n) *>
      Ref.of[F, State[F]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))
  }

  /**
   * Like [[apply]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable.
   *
   * WARN: some `Async` data types, like [[IO]], can be cancelable,
   * making `uncancelable` values unsafe. Such values are only useful
   * for optimization purposes, in cases where the use case does not
   * require cancellation or in cases in which an `F[_]` data type
   * that does not support cancellation is used.
   */
  def uncancelable[F[_]](n: Long)(implicit F: Async[F]): F[Semaphore[F]] = {
    assertNonNegative[F](n) *>
      Ref.of[F, State[F]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))
  }

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * like `apply` but initializes state using another effect constructor
   */
  def in[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Concurrent[G]): F[Semaphore[G]] =
    assertNonNegative[F](n) *>
      Ref.in[F, G, State[G]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * Like [[apply]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable
   * and initializes state using another effect constructor
   */
  def uncancelableIn[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Async[G]): F[Semaphore[G]] =
    assertNonNegative[F](n) *>
      Ref.in[F, G, State[G]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))

  private def assertNonNegative[F[_]](n: Long)(implicit F: ApplicativeError[F, Throwable]): F[Unit] =
    if (n < 0) F.raiseError(new IllegalArgumentException(s"n must be nonnegative, was: $n")) else F.unit

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[Queue[(Long, Deferred[F, Unit])], Long]

  abstract class AbstractKeySemaphore[F[_], K](state: Ref[F, Map[K, State[F]]], keyFunction: K => Long)(implicit F: Async[F]) extends Semaphore[Kleisli[F, K, ?]]{
    protected def mkGate: F[Deferred[F, Unit]]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())


    def acquireNInternal(n: Long): Kleisli[F, K, (F[Unit], F[Unit])]= Kleisli{ k => 
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure((F.unit, F.unit))
        else mkGate.flatMap { gate =>
          state
            .modify { oldMap =>
              val u = oldMap.get(k) match {
                case Some(Left(waiting)) => Left(waiting :+ (n -> gate))
                case Some(Right(m))=>
                  if (n <= m) Right(m - n)
                  else Left(Queue((n - m) -> gate))
                case None => 
                  val m = keyFunction(k)
                  if (n <= m) Right(m - n)
                  else  Left(Queue((n - m) -> gate))
              }
              (oldMap + (k -> u), u)
            }
            .map{
              case Left(waiting) =>
                val cleanup: F[Unit] = state.modify { oldMap => oldMap.get(k) match {
                  case Some(Left(waiting)) =>
                    waiting.find(_._2 eq gate).map(_._1) match {
                      case None => (oldMap + (k -> Left(waiting)), releaseN(n))
                      case Some(m) => (oldMap + (k -> Left(waiting.filterNot(_._2 eq gate))), releaseN(n - m))
                    }
                  case Some(Right(m)) => 
                    if (m + n >= keyFunction(k)) (oldMap - k, Kleisli{_ : K => F.unit})
                    else (oldMap + (k -> Right(m + n)), Kleisli{_: K => F.unit})
                  case None => (oldMap, Kleisli{_: K => F.unit})
                }}.flatMap(_.run(k))
                val entry = waiting.lastOption.getOrElse(sys.error("Semaphore has empty waiting queue rather than 0 count"))
                entry._2.get -> cleanup
              case Right(_) => F.unit -> releaseN(n).run(k)
            }
        }
      }
    }

    def acquireN(n: Long): Kleisli[F,K,Unit] =  Kleisli{ k => 
      F.bracketCase(acquireNInternal(n).run(k)) { case (g, _) => g } {
        case ((_, c), ExitCase.Canceled) => c
        case _ => F.unit
      }
    }

    def available: Kleisli[F,K,Long] = Kleisli{k => 
      state.get.map(_.get(k).map{
        case Left(_) =>  0
        case Right(n) => n
      }.getOrElse(keyFunction(k)))
    }

    def count: Kleisli[F,K,Long] = Kleisli{k => state.get.map(_.get(k).map(count_).getOrElse(keyFunction(k))) }
    private def count_(s: State[F]): Long = s match {
      case Left(waiting) => -waiting.map(_._1).sum
      case Right(available) => available
    }


    def releaseN(n: Long): Kleisli[F,K,Unit] = ???


    def tryAcquireN(n: Long): Kleisli[F,K,Boolean] = Kleisli{ k => 
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure(true)
        else
          state
            .modify { oldMap =>
              val u: Option[State[F]] = oldMap.get(k) match {
                case Some(Right(m)) if m >= n => Right(m - n).some
                case None if (keyFunction(k) >= n) => 
                  val count = keyFunction(k)
                  Right(count - n).some
                case w                  => w
              }
              val newMap : Map[K, State[F]] = u.map(u2 => oldMap + (k -> u2)).getOrElse(oldMap)
              (newMap, (oldMap.get(k), u))
            }
            .map { case (previous, now) =>
              now match {
                case Some(Left(_)) => false
                case Some(Right(n)) => previous match {
                  case Some(Left(_)) => false
                  case Some(Right(m)) => n != m
                  case None => true
                }
                case None => false
              }
            }
      }
    }

    def withPermit[A](t: Kleisli[F,K,A] ): Kleisli[F,K,A] = Kleisli{ k => 
      F.bracket(acquireNInternal(1).run(k)) { case (g, _) => g *> t.run(k) } { case (_, c) => c }
    }
  }

  private abstract class AbstractSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends Semaphore[F] {
    protected def mkGate: F[Deferred[F, Unit]]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())

    def count = state.get.map(count_)

    private def count_(s: State[F]): Long = s match {
      case Left(waiting) => -waiting.map(_._1).sum
      case Right(available) => available
    }

    def acquireN(n: Long) = F.bracketCase(acquireNInternal(n)) { case (g, _) => g } {
      case ((_, c), ExitCase.Canceled) => c
      case _ => F.unit
    }

    def acquireNInternal(n: Long): F[(F[Unit], F[Unit])]= {
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure((F.unit, F.unit))
        else mkGate.flatMap { gate =>
          state
            .modify { old =>
              val u = old match {
                case Left(waiting) => Left(waiting :+ (n -> gate))
                case Right(m) =>
                  if (n <= m) Right(m - n)
                  else Left(Queue((n - m) -> gate))
              }
              (u, u)
            }
            .map {
              case Left(waiting) =>
                val cleanup = state.modify {
                  case Left(waiting) =>
                    waiting.find(_._2 eq gate).map(_._1) match {
                      case None => (Left(waiting), releaseN(n))
                      case Some(m) => (Left(waiting.filterNot(_._2 eq gate)), releaseN(n - m))
                    }
                  case Right(m) => (Right(m + n), F.unit)
                }.flatten
                val entry = waiting.lastOption.getOrElse(sys.error("Semaphore has empty waiting queue rather than 0 count"))
                entry._2.get -> cleanup

              case Right(_) => F.unit -> releaseN(n)
            }
        }
      }
    }

    def tryAcquireN(n: Long): F[Boolean] = {
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure(true)
        else
          state
            .modify { old =>
              val u = old match {
                case Right(m) if m >= n => Right(m - n)
                case w                  => w
              }
              (u, (old, u))
            }
            .map { case (previous, now) =>
              now match {
                case Left(_) => false
                case Right(n) => previous match {
                  case Left(_) => false
                  case Right(m) => n != m
                }
              }
            }
      }
    }

    def releaseN(n: Long) = {
      assertNonNegative[F](n) *> {
        if (n == 0) F.unit
        else
          state
            .modify { old =>
              val u = old match {
                case Left(waiting) =>
                  // just figure out how many to strip from waiting queue,
                  // but don't run anything here inside the modify
                  var m = n
                  var waiting2 = waiting
                  while (waiting2.nonEmpty && m > 0) {
                    val (k, gate) = waiting2.head
                    if (k > m) {
                      waiting2 = (k - m, gate) +: waiting2.tail
                      m = 0
                    } else {
                      m -= k
                      waiting2 = waiting2.tail
                    }
                  }
                  if (waiting2.nonEmpty) Left(waiting2)
                  else Right(m)
                case Right(m) => Right(m + n)
              }
              (u, (old, u))
            }
            .flatMap { case (previous, now) =>
              // invariant: count_(now) == count_(previous) + n
              previous match {
                case Left(waiting) =>
                  // now compare old and new sizes to figure out which actions to run
                  val newSize = now match {
                    case Left(w) => w.size
                    case Right(_) => 0
                  }
                  val released = waiting.size - newSize
                  waiting.take(released).foldRight(F.unit) { (hd, tl) =>
                    open(hd._2) *> tl
                  }
                case Right(_) => F.unit
              }
            }
      }
    }

    def available: F[Long] = state.get.map {
      case Left(_)  => 0
      case Right(n) => n
    }

    def withPermit[A](t: F[A]): F[A] =
      F.bracket(acquireNInternal(1)) { case (g, _) => g *> t } { case (_, c) => c }
  }

  private final class ConcurrentSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Concurrent[F]) extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred[F, Unit]
  }

  private final class AsyncSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred.uncancelable[F, Unit]
  }
}

/**
// import cats._
import cats.implicits._
import cats.data._
import cats.effect._
import cats.effect.concurrent._

object KeySemaphore {

  def of[F[_]: Concurrent, K](f: K => Long): F[Semaphore[Kleisli[F, K, ?]]] = 
    in[F, F, K](f)

  def in[G[_]: Sync, F[_]: Concurrent, K](f: K => Long): G[Semaphore[Kleisli[F, K, ?]]] = for {
    map <- Ref.in[G, F, Map[K, Semaphore[F]]](Map.empty[K, Semaphore[F]])
  } yield new SimpleKeySemaphore[F, K](map, {k: K => Semaphore[F](f(k))})

  def uncancelable[F[_]: Async, K](f: K => Long): F[Semaphore[Kleisli[F, K, ?]]] =
    uncancelableIn[F, F, K](f)

  def uncancelableIn[G[_]: Sync, F[_]: Async, K](f: K => Long): G[Semaphore[Kleisli[F, K, ?]]] = for {
    map <- Ref.in[G, F, Map[K, Semaphore[F]]](Map.empty[K, Semaphore[F]])
  } yield new SimpleKeySemaphore[F, K](map, {k: K => Semaphore.uncancelable(f(k))})


  private class SimpleKeySemaphore[F[_], K](
    private val semRef: Ref[F, Map[K, Semaphore[F]]],
    private val makeSem: K => F[Semaphore[F]]
  )(implicit F: Sync[F]) extends Semaphore[Kleisli[F, K, ?]]{

    private def getOrMake(k: K): F[Semaphore[F]] = for {
      semMap <- semRef.get
      sem <- semMap.get(k)
        .fold(
          for {
            newSem <- makeSem(k)
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

**/