package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

/** A non-blocking counting semaphore for bounding concurrent async tasks.
  *
  * `acquire`/`release` only touch a small counter + waiter queue under a short
  * `synchronized` (no I/O held), so it never blocks a thread. A released permit
  * is handed directly to the next waiter. Used to cap how many requests the
  * scanner has in flight at once (instruction.md §5 backpressure).
  */
final class AsyncSemaphore(permits: Int):
  require(permits > 0, "permits must be > 0")

  private var available = permits
  private val waiters = scala.collection.mutable.Queue.empty[Promise[Unit]]

  private def acquire(): Future[Unit] = synchronized {
    if available > 0 then
      available -= 1
      Future.unit
    else
      val p = Promise[Unit]()
      waiters.enqueue(p)
      p.future
  }

  private def release(): Unit = synchronized {
    if waiters.nonEmpty then waiters.dequeue().success(())
    else available += 1
  }

  /** Run `task` once a permit is free, releasing it when the task completes
    * (success or failure).
    */
  def withPermit[T](task: () => Future[T])(using ec: ExecutionContext): Future[T] =
    acquire().flatMap { _ =>
      val f =
        try task()
        catch { case NonFatal(e) => Future.failed(e) }
      f.onComplete(_ => release())
      f
    }
