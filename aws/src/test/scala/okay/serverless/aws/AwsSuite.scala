package okay.serverless.aws

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.{Durable, field}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}

/** THE CLOUD'S EFFECTS ON AWS (DynamoDB Local at OKAY_DYNAMODB), and a Lambda given AWS's events */
class AwsSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  val endpoint: Option[String] = g.process.env.OKAY_DYNAMODB.asInstanceOf[js.UndefOr[String]].toOption
  override def munitIgnore: Boolean = endpoint.isEmpty
  lazy val client = DynamoStorage.client(endpoint = endpoint)
  val table = "okay-cloud-test"
  val logged = scala.collection.mutable.ListBuffer.empty[String]
  def unique = js.Math.random().toString.drop(2)

  /** a counter: an object; each request one step */
  object Counters extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      Durable.step[ActorStep, InActor, Long](name)(Durable.get[Long, ActorStep]("n").flatMap(n =>
        Durable.put[Long, ActorStep]("n", n.getOrElse(0L) + 1).as(n.getOrElse(0L) + 1)))
        .map(n => Response.text(n.toString))

  object Shop extends App:
    override def objectFor(r: Request): Option[(String, String)] = r.path.split('/').toList match
      case List("", "count", name) => Some(("COUNTERS", name))
      case _ => None
    override def fetch(r: Request): Response ! Cloud = r.path match
      case "/kv" => Kv.put[Cloud]("main", "k", r.body.text).andThen(Kv.get[Cloud]("main", "k"))
          .map(v => Response.text(v.getOrElse("?")))
      case "/via" => Objects.call[Cloud]("COUNTERS", "c", r).map(x => Response.text(s"object said ${x.text}"))
      case _ => pure(Response.notFound)
    override def queue(queue: String, m: Message): Settle ! Cloud =
      if m.body == "again" then pure(Settle.Retry()) else Kv.put[Cloud]("jobs", m.body, queue).as(Settle.Ack)
    override def scheduled(e: Scheduled): Unit ! Cloud = Kv.put[Cloud]("main", "cron", e.cron)

  lazy val ports = AwsPorts(client, table, limits = Map("api" -> (2, 60)), actors = Map("COUNTERS" -> Counters),
    log = s => logged += s)
  lazy val lambda = Lambda(Shop, ports)
  def ready: Future[Unit] = DynamoStorage.table(client, table)

  test("KV on DynamoDB: metadata, expiry, pages; a rate limit across instances; analytics as metrics in the log") {
    val ns = s"n$unique"
    val other = AwsPorts(client, table, limits = Map("api" -> (2, 60)))
    for
      _ <- ready
      got <- ports.platform.future(for
        _ <- Kv.put[Cloud](ns, "a", "1", metadata = Some("""{"v":1}"""))
        _ <- Kv.put[Cloud](ns, "b", "2")
        _ <- Kv.put[Cloud](ns, "c", "3", ttlSeconds = Some(-1))
        a <- Kv.entry[Cloud](ns, "a")
        gone <- Kv.get[Cloud](ns, "c")
        p1 <- Kv.list[Cloud](ns, limit = 1)
        p2 <- Kv.list[Cloud](ns, cursor = p1.cursor, limit = 5)
        l1 <- Limits.take[Cloud]("api", s"ann$ns")
        _ <- Analytics.write[Cloud]("Shop", DataPoint(List("ann"), List("/x"), List(2)))
      yield (a, gone, p1.items, p2.items, l1))
      l2 <- other.platform.future(Limits.take[Cloud]("api", s"ann$ns"))
      l3 <- ports.platform.future(Limits.take[Cloud]("api", s"ann$ns"))
    yield
      assertEquals(got._1, Some(KvEntry("1", Some("""{"v":1}"""))))
      assertEquals(got._2, None)
      assertEquals(got._3, List("a"))
      assertEquals(got._4, List("b"))
      assertEquals((got._5, l2, l3), (true, true, false))
      val metric = Descent.parse(logged.last).toOption.get
      assertEquals(metric.field("double0"), Some(Json.num(2)))
      assertEquals(metric.field("_aws").flatMap(_.field("CloudWatchMetrics")).map(Json.print(_)).exists(
        _.contains("\"Namespace\":\"Shop\"")), true)
  }

  def httpEvent(method: String, path: String, body: String = ""): js.Dynamic = obj(version = "2.0",
    rawPath = path, rawQueryString = "", headers = obj(host = "shop.example"), body = body, isBase64Encoded = false,
    requestContext = obj(http = obj(method = method, path = path)))
  def text(r: js.Any): String =
    String(g.Buffer.from(r.asInstanceOf[js.Dynamic].body, "base64").toString().asInstanceOf[String])

  test("A LAMBDA: API Gateway requests — to the app, to an object as it is, to an object through the app") {
    val name = s"/count/c$unique"
    for
      _ <- ready
      kv <- lambda.handle(httpEvent("POST", "/kv", "stored"))
      c1 <- lambda.handle(httpEvent("POST", name))
      c2 <- lambda.handle(httpEvent("POST", name))
      via <- lambda.handle(httpEvent("POST", "/via"))
    yield
      assertEquals(text(kv), "stored")
      assertEquals((text(c1), text(c2)), ("1", "2"))
      assert(text(via).startsWith("object said "), text(via))
  }

  test("A LAMBDA: SQS — acknowledged, or a batch item failure; EventBridge's schedule") {
    def record(id: String, body: String) = obj(messageId = id, body = body, eventSource = "aws:sqs",
      eventSourceARN = "arn:aws:sqs:eu-west-1:1:jobs", attributes = obj(ApproximateReceiveCount = "1",
        SentTimestamp = "1"))
    val job = s"job$unique"
    for
      _ <- ready
      r <- lambda.handle(obj(Records = js.Array(record("m1", job), record("m2", "again"))))
      done <- ports.platform.future(Kv.get[Cloud]("jobs", job))
      _ <- lambda.handle(obj("detail-type" -> "Scheduled Event", "time" -> "2026-10-26T12:00:00Z",
        "resources" -> js.Array("arn:aws:events:eu-west-1:1:rule/nightly")))
      cron <- ports.platform.future(Kv.get[Cloud]("main", "cron"))
    yield
      assertEquals(g.JSON.stringify(r).asInstanceOf[String], """{"batchItemFailures":[{"itemIdentifier":"m2"}]}""")
      assertEquals(done, Some("jobs"))
      assertEquals(cron, Some("arn:aws:events:eu-west-1:1:rule/nightly"))
  }
