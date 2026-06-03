package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** A process-wide cap on concurrent target-facing HTTP requests (instruction.md
  * §5: stay polite, do not knock over the target). Every prober routes its
  * request through here, so the scanner never has more than
  * `DAST_MAX_CONCURRENCY` (default 4) requests in flight at once. LLM API calls
  * (a different host, not the target) are deliberately not throttled here.
  */
object HttpThrottle:

  private val sem =
    new AsyncSemaphore(DastConfig.getInt("DAST_MAX_CONCURRENCY", 4))

  def apply[T](task: => Future[T])(using ExecutionContext): Future[T] = sem
    .withPermit(() => task)
