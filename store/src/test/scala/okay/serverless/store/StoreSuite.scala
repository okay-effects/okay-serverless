package okay.serverless.store

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.Durable
import okay.serverless.cloud.*
import okay.store.Data

import scala.concurrent.ExecutionContext

/** okay-store's effects on the memory cloud: KV, and an object's durable state — one transaction a step */
class StoreSuite extends munit.FunSuite:
  import StorePrograms.*
  given ExecutionContext = Async.defaultContext

  type D[X] = (Cloud + Data)[X]
  type P[X] = (D + S)[X]

  test("on KV: saved, found by the reference's meaning, counted; no keys from KV, said") {
    val c = MemoryCloud()
    for
      got <- c.platform.future(StoreOn.kv[D, Cloud, (List[String], Long)]("docs")(
        StoreOn.data[Sale, P, D, (List[String], Long)](sales)(saving[P])))
      fresh <- c.platform.future(StoreOn.kv[D, Cloud, String]("docs")(Docs.fresh[D]("sales"))).failed
    yield
      assertEquals(got, (List("b"), 2L))
      assert(fresh.getMessage.contains("no sequences on KV"), fresh.getMessage)
  }

  type AD[X] = (ActorStep + Data)[X]
  type AP[X] = (AD + S)[X]

  /** a shop: an object; each request the whole program, one step */
  object Shops extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      Durable.step[ActorStep, InActor, Product](name)(StoreOn.durable[AD, ActorStep, Product](
        StoreOn.data[Sale, AP, AD, Product](sales)(program[AP]))).map(r => Response.text(r.toString))

  test("in an object's durable state: the same answers, the documents in its storage") {
    val c = MemoryCloud()
    c.actors("SHOPS") = Shops
    c.platform.future(Objects.call[Cloud]("SHOPS", "kyiv", Request.get("/"))).map { r =>
      assertEquals(r.text, expected.toString)
      val stored = c.stored("SHOPS", "kyiv")
      assertEquals(stored("data/_keys/sales"), "2")
      assert(stored("data/sales/000000000002").contains("\"green tea\""), stored.toString)
      assert(!stored.contains("data/sales/x1"), "removed")
    }
  }
