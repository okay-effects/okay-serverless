package okay.serverless.cloudflare

import okay.scala3.*
import okay.serverless.{Durable, Storage, Versioned}

import scala.scalajs.js

/**
 * `ObjectStorage`'s OWN SQL, run under Node on SQLite (`node:sqlite`) — a Durable Object's storage is SQLite — behind
 * the shape of an object's `ctx.storage` (`sql.exec`, `transactionSync`, `setAlarm`): the statements the object runs
 * on Cloudflare, checked here before any `wrangler dev`
 */
class ObjectStorageSuite extends munit.FunSuite:
  /** an object's context on Node's SQLite: what `ObjectStorage` asks of `ctx.storage`, nothing more */
  final class Context:
    val db = js.Dynamic.newInstance(js.Dynamic.global.require("node:sqlite").DatabaseSync)(":memory:")
    var alarm: Option[Double] = None
    private val exec: js.Function = (query: String, args: js.Array[js.Any]) =>
      val statement = db.prepare(query)
      val rows: js.Array[js.Dynamic] =
        if query.trim.toUpperCase.startsWith("SELECT") then
          statement.applyDynamic("all")(args.toSeq*).asInstanceOf[js.Array[js.Dynamic]]
        else
          statement.applyDynamic("run")(args.toSeq*)
          js.Array()
      js.Dynamic.literal(toArray = (() => rows): js.Function0[js.Array[js.Dynamic]])
    private val sql = js.Dynamic.literal(exec = js.Dynamic.global.Function("run",
      "return function (q, ...args) { return run(q, args); }")(exec))
    val storage: js.Dynamic = js.Dynamic.literal(sql = sql,
      transactionSync = ((f: js.Function0[js.Any]) => {
        db.exec("BEGIN")
        try { val r = f(); db.exec("COMMIT"); r }
        catch case e: Throwable => { db.exec("ROLLBACK"); throw e }
      }): js.Function1[js.Function0[js.Any], js.Any],
      setAlarm = ((at: Double) => { alarm = Some(at) }): js.Function1[Double, Unit])
    val ctx: js.Dynamic = js.Dynamic.literal(storage = storage)

  test("values by key with their versions; keys by prefix, its LIKE characters only themselves") {
    val c = Context()
    val s = ObjectStorage(c.ctx)
    assert(s.commit(Map.empty, Map("a/1" -> Some("x"), "a%/2" -> Some("y"), "ab" -> Some("z")), None))
    assertEquals(s.read(List("a/1", "none")), Map("a/1" -> Versioned("x", 1)))
    assertEquals(s.keys("a/"), List("a/1"))
    assertEquals(s.keys("a%"), List("a%/2"))
    assert(s.commit(Map("a/1" -> 1), Map("a/1" -> Some("x2")), None))
    assertEquals(s.read(List("a/1")), Map("a/1" -> Versioned("x2", 2)))
  }

  test("a commit all or nothing: a version changed meanwhile writes nothing; a delete; the alarm on a commit") {
    val c = Context()
    val s = ObjectStorage(c.ctx)
    s.commit(Map.empty, Map("k" -> Some("1")), None)
    assert(!s.commit(Map("k" -> 0), Map("k" -> Some("2"), "other" -> Some("3")), Some(10)))
    assertEquals(s.read(List("k", "other")), Map("k" -> Versioned("1", 1)))
    assertEquals(c.alarm, None)
    assert(s.commit(Map("k" -> 1), Map("k" -> None), Some(10)))
    assertEquals(s.read(List("k")), Map.empty)
    assertEquals(c.alarm, Some(10.0))
  }

  test("Durable's steps on the object: read your writes, a step's count, keys with the step's own") {
    val c = Context()
    val s = ObjectStorage(c.ctx)
    type D[X] = (Pure + Storage + Durable)[X]
    def step[A](p: A ! D): A = run(s.handler(Durable.step[D, Pure + Storage, A]("shop")(p)))
    step(Durable.put[String, D]("shop/order/1", "a").andThen(Durable.put[String, D]("shop/order/2", "b")))
    val (keys, first) = step(Durable.put[String, D]("shop/order/3", "c").andThen(
      Durable.keys[D]("shop/order/").flatMap(ks => Durable.get[String, D]("shop/order/1").map(ks -> _))))
    assertEquals(keys, List("shop/order/1", "shop/order/2", "shop/order/3"))
    assertEquals(first, Some("a"))
    assertEquals(s.read(List("shop/#steps")).get("shop/#steps").map(_.value), Some("2"))
  }
