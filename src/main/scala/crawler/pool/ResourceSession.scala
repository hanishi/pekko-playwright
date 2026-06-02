package crawler.pool

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.util.Try

/** One session actor owns one resource of type `R`. Spawned on a
  * `PinnedDispatcher` (see `application.conf`) so the actor's
  * receive method runs on the same OS thread for the actor's
  * lifetime. The resource is constructed lazily inside the actor's
  * setup block, so it captures the pinned thread — every method
  * call on the resource then runs on the thread it was built on.
  *
  * `R` is required to be `AutoCloseable` so the session can release
  * the resource on the pinned thread when it stops or restarts —
  * many thread-affine resources (Graal `Context`, native handles,
  * etc.) require teardown on the same thread that created them.
  *
  * Callers never touch the resource directly. They send `Submit`
  * messages carrying a closure; the actor runs the closure inline
  * with the resource it owns and completes the supplied `Promise`
  * with the result (or the exception).
  */
object ResourceSession {

  sealed trait Command

  /** The closure is `Any => Any` rather than `R => Any` because the
    * protocol is shared across all `R` — `ResourcePool` re-types
    * the work at the public extension boundary. */
  final case class Submit(
      work: Any => Any,
      promise: Promise[Any],
  ) extends Command

  case object Stop extends Command

  def apply[R <: AutoCloseable](id: Int, make: Int => R): Behavior[Command] =
    Behaviors.setup { _ =>
      val log = LoggerFactory.getLogger(s"crawler.pool.ResourceSession.$id")
      // Build the resource HERE — inside setup, on the pinned thread.
      // The resource's owning thread is captured at this point; every
      // subsequent `work(resource)` runs on the same thread.
      val resource = make(id)
      log.info("starting on thread '{}'", Thread.currentThread().getName)

      Behaviors
        .receiveMessage[Command] {
          case Submit(work, promise) =>
            promise.complete(Try(work(resource)))
            Behaviors.same

          case Stop =>
            log.info("stopping")
            Behaviors.stopped
        }
        .receiveSignal {
          // Close on both PostStop and PreRestart — supervised
          // restart would otherwise leak the previous instance.
          case (_, PostStop | PreRestart) =>
            log.info("closing resource on thread '{}'", Thread.currentThread().getName)
            resource.close()
            Behaviors.same
        }
    }
}
