package okay.serverless.cloud

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Codec, field}

import scala.concurrent.ExecutionContext

/** A WORKFLOW on the memory platform: a failing stage retried by alarms, a sleep, an event, the end */
class WorkflowSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext

  final case class Order(id: String, total: Long, charged: Boolean = false, approvedBy: Option[String] = None)
  given Codec[Order] = Codec.json(
    o => Json.obj("id" -> Json.Str(o.id), "total" -> Json.num(o.total), "charged" -> Json.Bool(o.charged),
      "approvedBy" -> o.approvedBy.fold(Json.Null)(Json.Str(_))),
    j => Right(Order(j.field("id").collect { case Json.Str(s) => s }.getOrElse(""),
      j.field("total").collect { case Json.Num(n) => n.toLong }.getOrElse(0L),
      j.field("charged").contains(Json.Bool(true)), j.field("approvedBy").collect { case Json.Str(s) => s })))

  var time = 0L
  var chargeCalls = 0
  val order = Workflow[Order](j => Order(j.field("id").collect { case Json.Str(s) => s }.getOrElse("?"),
    j.field("total").collect { case Json.Num(n) => n.toLong }.getOrElse(0L)))(
    Stage.Do("charge", o => defer {
      chargeCalls += 1
      if chargeCalls < 3 then Fail.raise[String, InActor + Fail % String]("the bank is down")
      else pure(o.copy(charged = true))
    }, retries = 5, backoffMs = 1000),
    Stage.Sleep("cool-off", 86_400_000L),
    Stage.Wait("approval", "approved", (o, j) => o.copy(approvedBy = j.field("by").collect { case Json.Str(s) => s })),
    Stage.Do("notify", o => Mails.send[InActor + Fail % String]("out", Mail("shop@x", "ann@x",
      s"order ${o.id}", s"approved by ${o.approvedBy.getOrElse("?")}")).as(o)))

  def field(r: Response, name: String): Option[Json] = Descent.parse(r.text).toOption.flatMap(_.field(name))

  test("retries by alarm with backoff; a day's sleep; an event; done — each stage committed, nothing replayed") {
    val c = MemoryCloud(() => time)
    c.actors("orders") = order
    def call(r: Request) = c.platform.future(Objects.call[Cloud]("orders", "o-1", r))
    for
      started <- call(Request.post("https://w/start", """{"id":"o-1","total":250}"""))
      _ = time += 1000
      _ <- c.fireAlarms() // the second try: down again
      afterTwo <- call(Request.get("https://w/"))
      early <- c.fireAlarms() // the backoff doubled: not yet
      _ = time += 2000
      _ <- c.fireAlarms() // the third: charged; then asleep a day
      asleep <- call(Request.get("https://w/"))
      tooEarly <- call(Request.post("https://w/event/approved", """{"by":"bob"}"""))
      _ = time += 86_400_000L
      _ <- c.fireAlarms()
      waiting <- call(Request.get("https://w/"))
      done <- call(Request.post("https://w/event/approved", """{"by":"bob"}"""))
      again <- call(Request.post("https://w/start", "{}"))
    yield
      assertEquals(field(started, "status"), Some(Json.Str("retrying")))
      assertEquals(field(afterTwo, "error"), Some(Json.Str("the bank is down")))
      assertEquals(early, 0)
      assertEquals(chargeCalls, 3)
      assertEquals(field(asleep, "status"), Some(Json.Str("sleeping")))
      assertEquals(field(asleep, "at"), Some(Json.Str("cool-off")))
      assertEquals(tooEarly.status, 409)
      assertEquals(field(waiting, "status"), Some(Json.Str("waiting:approved")))
      assertEquals(field(done, "status"), Some(Json.Str("done")))
      assertEquals(field(done, "state").flatMap(_.field("approvedBy")), Some(Json.Str("bob")))
      assertEquals(c.sent.map(_._2.text).toList, List("approved by bob"))
      assertEquals(again.status, 409)
  }
