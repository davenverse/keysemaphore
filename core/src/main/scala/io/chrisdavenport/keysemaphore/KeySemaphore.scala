package io.chrisdavenport.keysemaphore

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.syntax.all._

import scala.collection.immutable.Queue

/**
 * A KeySemaphore is a structure which has Semaphores
 * for each of the keys in the domain, it cleans up the additional
 * values when permits reach those indicated by the keyfunction,
 * as to not leak space
 **/
trait KeySemaphore[F[_], K] extends Function1[K, Semaphore[F]]{
  def apply(k: K): Semaphore[F]
}
object KeySemaphore {
  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def of[F[_], K](keyFunction: K => Long)(implicit F: Concurrent[F]): F[KeySemaphore[F, K]] = {
      Ref.of[F, Map[K, State[F]]](Map.empty[K, State[F]]).map(stateRef => new ConcurrentKeySemaphore(stateRef, keyFunction))
  }

  /**
   * Like [[of]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable.
   *
   * WARN: some `Async` data types, like `IO`, can be cancelable,
   * making `uncancelable` values unsafe. Such values are only useful
   * for optimization purposes, in cases where the use case does not
   * require cancellation or in cases in which an `F[_]` data type
   * that does not support cancellation is used.
   */
  def uncancelable[F[_], K](keyFunction: K => Long)(implicit F: Async[F]): F[KeySemaphore[F, K]] = {
      Ref.of[F, Map[K, State[F]]](Map.empty[K, State[F]]).map(stateRef => new AsyncKeySemaphore(stateRef, keyFunction))
  }

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * like `apply` but initializes state using another effect constructor
   */
  def in[F[_], G[_], K](keyFunction: K => Long)(implicit F: Sync[F], G: Concurrent[G]): F[KeySemaphore[G, K]] =
      Ref.in[F, G, Map[K, State[G]]](Map()).map(stateRef => new ConcurrentKeySemaphore(stateRef, keyFunction))

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * Like [[in]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable
   * and initializes state using another effect constructor
   */
  def uncancelableIn[F[_], G[_], K](keyFunction: K => Long)(implicit F: Sync[F], G: Async[G]): F[KeySemaphore[G, K]] =//Kleisli[G, K, *]]] =
      Ref.in[F, G, Map[K, State[G]]](Map()).map(stateRef => new AsyncKeySemaphore(stateRef, keyFunction))

  private def assertNonNegative[F[_]](n: Long)(implicit F: ApplicativeError[F, Throwable]): F[Unit] =
    if (n < 0) F.raiseError(new IllegalArgumentException(s"n must be nonnegative, was: $n")) else F.unit

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[Queue[(Long, Deferred[F, Unit])], Long]

  private abstract class AbstractKeySemaphore[F[_], K](state: Ref[F, Map[K, State[F]]], keyFunction: K => Long)(implicit F: Async[F]) extends KeySemaphore[F, K] {
    protected def mkGate: F[Deferred[F, Unit]]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())

    def apply(k: K): Semaphore[F] = new InternalSem(k)

    class InternalSem(k: K) extends Semaphore[F]{
      def acquireNInternal(n: Long): F[(F[Unit], F[Unit])]= {
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
                      if (m + n >= keyFunction(k)) (oldMap - k, F.unit)
                      else (oldMap + (k -> Right(m + n)), F.unit)
                    case None => (oldMap, F.unit)
                  }}.flatten
                  val entry = waiting.lastOption.getOrElse(sys.error("Semaphore has empty waiting queue rather than 0 count"))
                  entry._2.get -> cleanup
                case Right(_) => F.unit -> releaseN(n)
              }
          }
        }
      }

      def acquireN(n: Long): F[Unit] =  {
        F.bracketCase(acquireNInternal(n)) { case (g, _) => g } {
          case ((_, c), ExitCase.Canceled) => c
          case _ => F.unit
        }
      }

      def available: F[Long] = {
        state.get.map(_.get(k).map{
          case Left(_) =>  0
          case Right(n) => n
        }.getOrElse(keyFunction(k)))
      }


      def count: F[Long] = {state.get.map(_.get(k).map(count_).getOrElse(keyFunction(k))) }

      private def count_(s: State[F]): Long = s match {
        case Left(waiting) => -waiting.map(_._1).sum
        case Right(available) => available
      }


      def releaseN(n: Long): F[Unit] = { 
        assertNonNegative[F](n) *> {
          if (n == 0) F.unit
          else
            state
              .modify { old =>
                val u : Option[State[F]] = old.get(k) match {
                  case Some(Left(waiting)) =>
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
                    
                    if (waiting2.nonEmpty) Some(Left(waiting2))
                    else if (m >= keyFunction(k)) None
                    else Some(Right(m))
                  case Some(Right(m)) => 
                    if (m + n >= keyFunction(k)) None
                    else Some(Right(m + n))
                  case None => None
                }
                val out = u.map(state => old + (k -> state)).getOrElse(old - k)
                (out, (old.get(k), u))
              }
              .flatMap { case (previous, now) =>
                // invariant: count_(now) == count_(previous) + n
                previous match {
                  case Some(Left(waiting)) =>
                    // now compare old and new sizes to figure out which actions to run
                    val newSize = now match {
                      case Some(Left(w)) => w.size
                      case Some(Right(_)) => 0
                      case None => 0
                    }
                    val released = waiting.size - newSize
                    waiting.take(released).foldRight(F.unit) { (hd, tl) =>
                      open(hd._2) *> tl
                    }
                  case Some(Right(_)) => F.unit
                  case None => F.unit
                }
              }
        }
      }


      def tryAcquireN(n: Long): F[Boolean] = {
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

      def withPermit[A](t: F[A] ): F[A] = {
        F.bracket(acquireNInternal(1)) { case (g, _) => g *> t } { case (_, c) => c }
      }
    }
  }

  private final class ConcurrentKeySemaphore[F[_], K](state: Ref[F, Map[K, State[F]]], keyFunction: K => Long)(implicit F: Concurrent[F]) extends AbstractKeySemaphore(state, keyFunction) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred[F, Unit]
  }

  private final class AsyncKeySemaphore[F[_], K](state: Ref[F, Map[K, State[F]]], keyFunction: K => Long)(implicit F: Async[F]) extends AbstractKeySemaphore(state, keyFunction) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred.uncancelable[F, Unit]
  }
}


