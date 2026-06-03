package dast

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AsyncSemaphoreSpec extends AnyWordSpec with Matchers {

  given ExecutionContext = ExecutionContext.global

  "AsyncSemaphore" should {
    "never run more than `permits` tasks concurrently" in {
      val sem = new AsyncSemaphore(3)
      val live = new AtomicInteger(0)
      val maxLive = new AtomicInteger(0)
      val gate = Promise[Unit]() // hold tasks so they overlap

      val tasks = (1 to 20).map { _ =>
        sem.withPermit { () =>
          val now = live.incrementAndGet()
          maxLive.updateAndGet(m => math.max(m, now))
          gate.future.map { _ =>
            live.decrementAndGet(); ()
          }
        }
      }
      // Let the first wave acquire, then release everyone.
      Thread.sleep(100)
      maxLive.get() shouldBe 3 // exactly `permits` got in
      gate.success(())
      Await.result(Future.sequence(tasks), 5.seconds)
      maxLive.get() should be <= 3
    }

    "release on failure so later tasks still run" in {
      val sem = new AsyncSemaphore(1)
      val boom = sem.withPermit(() => Future.failed(new RuntimeException("x")))
      Await.ready(boom, 2.seconds)
      // If the permit leaked, this would never complete.
      Await
        .result(sem.withPermit(() => Future.successful(42)), 2.seconds) shouldBe
        42
    }
  }
}
