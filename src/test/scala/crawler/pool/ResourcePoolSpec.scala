package crawler.pool

import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import crawler.pool.ResourcePool.asPool
import crawler.pool.ResourcePool.submit
import crawler.pool.ResourcePool.submitAll
import crawler.pool.ResourcePool.submitTo

object ResourcePoolSpec {

  /** Same PinnedDispatcher the production pool uses — the testkit's ActorSystem
    * doesn't load application.conf, so declare it here.
    */
  val config: String =
    """session-pinned-dispatcher {
      |  type     = PinnedDispatcher
      |  executor = "thread-pool-executor"
      |  thread-pool-executor.allow-core-timeout = off
      |}
      |""".stripMargin

  /** Test resource that records the thread it was constructed on (the pinned
    * session thread) and whether it was closed. Registers itself so the test
    * can inspect every resource the pool built.
    */
  final class ProbeResource(
      val id: Int,
      registry: ConcurrentLinkedQueue[ProbeResource],
  ) extends AutoCloseable {
    val constructionThread: String = Thread.currentThread().getName
    @volatile
    var closed: Boolean = false
    registry.add(this)

    def threadName(): String = Thread.currentThread().getName
    override def close(): Unit = closed = true
  }
}

class ResourcePoolSpec
    extends ScalaTestWithActorTestKit(ResourcePoolSpec.config)
    with AnyWordSpecLike
    with Matchers
    with Eventually {

  import ResourcePoolSpec.*

  private val await = 3.seconds

  private def newPool(size: Int) = {
    val registry = new ConcurrentLinkedQueue[ProbeResource]()
    val ref = spawn(ResourcePool[ProbeResource](
      size = size,
      make = i => new ProbeResource(i, registry),
    ))
    (ref.asPool[ProbeResource], registry)
  }

  "ResourcePool" should {

    "run submitted work and return its result" in {
      val (pool, _) = newPool(1)
      Await.result(pool.submit(_.id + 100), await) shouldBe 100
    }

    "pin one session to a single thread across many submits" in {
      val (pool, registry) = newPool(1)
      val threads = (1 to 10)
        .map(_ => Await.result(pool.submit(_.threadName()), await))
      threads.distinct should have size 1
      // Work runs on the very thread the resource was constructed on.
      eventually {
        registry.asScala.map(_.constructionThread).toSet shouldBe threads.toSet
      }
    }

    "spread work across sessions round-robin" in {
      val (pool, _) = newPool(2)
      val threads = (1 to 6)
        .map(_ => Await.result(pool.submit(_.threadName()), await))
      threads.distinct should have size 2
    }

    "route the same submitTo key to the same session" in {
      val (pool, _) = newPool(3)
      val a = (1 to 4)
        .map(_ => Await.result(pool.submitTo("key")(_.threadName()), await))
      a.distinct should have size 1
    }

    "fan out submitAll to every session" in {
      val (pool, _) = newPool(3)
      val threads = Await.result(pool.submitAll(_.threadName()), await)
      threads should have size 3
      threads.distinct should have size 3
    }

    "fail the future when the work throws" in {
      val (pool, _) = newPool(1)
      val f = pool.submit[Int](_ => throw new RuntimeException("boom"))
      val ex = intercept[RuntimeException](Await.result(f, await))
      ex.getMessage shouldBe "boom"
    }

    "close every resource on Stop" in {
      val (pool, registry) = newPool(3)
      // Make sure all sessions have started (resources constructed).
      Await.result(pool.submitAll(_.id), await) should have size 3
      pool ! ResourcePool.Stop
      eventually {
        registry.asScala.toList should have size 3
        registry.asScala.forall(_.closed) shouldBe true
      }
    }
  }
}
