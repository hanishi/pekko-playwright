package crawler.pool

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

/** A node-local pool of [[ResourceSession]] actors, generic over the resource
  * type `R`. Each session holds one `R` (constructed by the supplied `make`
  * factory on its pinned thread) and lives on its own pinned thread. Submitted
  * work is dispatched round-robin across sessions.
  *
  * Total threads dedicated to the resource = `size`. Callers can outnumber
  * sessions — extra work just queues in each session's mailbox until the pinned
  * thread is free.
  *
  * The public API is one method: `submit`, exposed as a Scala 3 extension on
  * `Pool[R]`. Callers pass a typed `work: R => T` and get a `Future[T]`. The
  * pool's internal message protocol uses `Any => Any` because Pekko message
  * types are fixed at protocol declaration time; `Pool[R]` is a phantom-typed
  * alias that preserves `R` at compile time without leaking it into the
  * protocol.
  */
object ResourcePool {

  sealed trait Command

  private final case class Submit(work: Any => Any, promise: Promise[Any])
      extends Command

  private final case class SubmitTo(
      hash: Int,
      work: Any => Any,
      promise: Promise[Any],
  ) extends Command

  private final case class SubmitAll(work: Any => Any, promise: Promise[Any])
      extends Command

  case object Stop extends Command

  opaque type Pool[R] <: ActorRef[Command] = ActorRef[Command]

  extension (ref: ActorRef[Command]) inline def asPool[R]: Pool[R] = ref

  def apply[R <: AutoCloseable](
      size: Int = 4,
      make: Int => R,
      dispatcherName: String = "session-pinned-dispatcher",
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val log = LoggerFactory.getLogger("crawler.pool.ResourcePool")
    log.info(
      "starting pool of {} sessions on dispatcher '{}'",
      size,
      dispatcherName,
    )

    val sessions: Vector[ActorRef[ResourceSession.Command]] = Vector
      .tabulate(size) { i =>
        val behavior = Behaviors.supervise(ResourceSession[R](i, make))
          .onFailure[Exception](SupervisorStrategy.restart)
        ctx.spawn(
          behavior,
          s"session-$i",
          DispatcherSelector.fromConfig(dispatcherName),
        )
      }

    given ec: ExecutionContext = ctx.executionContext

    def routing(next: Int): Behavior[Command] = Behaviors.receiveMessage {
      case Submit(work, promise) =>
        sessions(next) ! ResourceSession.Submit(work, promise)
        routing((next + 1) % sessions.size)

      case SubmitTo(hash, work, promise) =>
        sessions(Math.floorMod(hash, sessions.size)) !
          ResourceSession.Submit(work, promise)
        Behaviors.same

      case SubmitAll(work, promise) =>
        val perShard = Vector.fill(sessions.size)(Promise[Any]())
        sessions.zip(perShard).foreach { case (s, p) =>
          s ! ResourceSession.Submit(work, p)
        }
        Future.sequence(perShard.map(_.future)).onComplete {
          case Success(vec) => promise.success(vec)
          case Failure(t) => promise.failure(t)
        }
        Behaviors.same

      case Stop =>
        log.info("stopping pool ({} sessions)", sessions.size)
        sessions.foreach(_ ! ResourceSession.Stop)
        Behaviors.stopped
    }

    routing(0)
  }

  extension [R](pool: Pool[R])
    def submit[T](work: R => T): Future[T] = {
      val promise = Promise[Any]()
      pool ! Submit(work.asInstanceOf[Any => Any], promise)
      promise.future.asInstanceOf[Future[T]]
    }

    def submitTo[T](key: Any)(work: R => T): Future[T] = {
      val promise = Promise[Any]()
      pool ! SubmitTo(key.##, work.asInstanceOf[Any => Any], promise)
      promise.future.asInstanceOf[Future[T]]
    }

    def submitAll[T](work: R => T): Future[Vector[T]] = {
      val promise = Promise[Any]()
      pool ! SubmitAll(work.asInstanceOf[Any => Any], promise)
      promise.future.asInstanceOf[Future[Vector[T]]]
    }
}
