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
      case "/kv" => Kv.put[Cloud]("main", "k", r.text).andThen(Kv.get[Cloud]("main", "k"))
          .map(v => Response.text(v.getOrElse("?")))
      case "/via" => Objects.call[Cloud]("COUNTERS", "c", r).map(x => Response.text(s"object said ${x.text}"))
      case "/notes" => Sql.batch[Cloud]("main",
          Statement("create table if not exists notes (id serial primary key, body text, data bytea)"),
          Statement.of("insert into notes (body, data) values (?, ?)", r.text, Bytes(Array[Byte](0, -1)))).andThen(
          Sql.query[Cloud]("main", "select count(*)::int as n, max(body) as last from notes")).map(rows =>
          Response.text(s"${rows.head("n")} ${rows.head("last")}"))
      case "/flip" => pure(Response.binary(Bytes(r.content.toArray.map(b => (~b).toByte))))
      case _ => pure(Response.notFound())
    override def queue(queue: String, m: Message): Settle ! Cloud =
      if m.body == "again" then pure(Settle.Retry()) else Kv.put[Cloud]("jobs", m.body, queue).as(Settle.Ack)
    override def scheduled(e: Scheduled): Unit ! Cloud = Kv.put[Cloud]("main", "cron", e.cron)

  val postgres: Option[String] = g.process.env.OKAY_POSTGRES.asInstanceOf[js.UndefOr[String]].toOption
  lazy val ports = AwsPorts(client, table, limits = Map("api" -> (2, 60)), actors = Map("COUNTERS" -> Counters),
    log = s => logged += s, sql = postgres.fold(Port.missing[Sql]("SQL"))(u => AwsPorts.postgres(_ => Some(u))))
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

  test("A LAMBDA: a binary body in (base64, as API Gateway gives it) and out") {
    val raw = js.Array[Short](0, 255, 128, 127, 195, 40)
    val in = g.Buffer.from(raw).applyDynamic("toString")("base64")
    val e = httpEvent("POST", "/flip", in.toString)
    e.isBase64Encoded = true
    lambda.handle(e).map { r =>
      val out = g.Buffer.from(r.asInstanceOf[js.Dynamic].body, "base64")
      assertEquals(out.toJSON().data.asInstanceOf[js.Array[Int]].toList, List(255, 0, 127, 128, 60, 215))
    }
  }

  test("POSTGRES (RDS Proxy's way): a batch in one transaction, rolled back whole; types; through the Lambda") {
    assume(postgres.isDefined, "OKAY_POSTGRES")
    val t = s"t$unique"
    for
      out <- ports.platform.future(for
        _ <- Sql.run[Cloud]("main", s"create table $t (id serial primary key, name text not null, n bigint, " +
          "x double precision, ok boolean, data bytea)")
        c <- Sql.run[Cloud]("main", s"insert into $t (name, n, x, ok, data) values (?, ?, ?, ?, ?)", "ann", 7L, 1.5,
          SqlValue.Int(1), Bytes(Array[Byte](1, -2)))
        rows <- Sql.query[Cloud]("main", s"select name, x, ok, data from $t where name = ? and name <> '?'", "ann")
      yield (c, rows))
      failed <- ports.platform.future(Sql.batch[Cloud]("main", Statement(s"insert into $t (name) values ('b')"),
        Statement(s"insert into $t (name) values (null)"))).failed
      count <- ports.platform.future(Sql.query[Cloud]("main", s"select count(*)::int as n from $t"))
      viaLambda <- lambda.handle(httpEvent("POST", "/notes", "hello"))
    yield
      assertEquals(out._1.changes, 1L)
      assertEquals(out._2, List(Map("name" -> SqlValue.Text("ann"), "x" -> SqlValue.Real(1.5),
        "ok" -> SqlValue.Int(1), "data" -> SqlValue.Blob(Bytes(Array[Byte](1, -2))))))
      assert(failed.getMessage.contains("null value"), failed.getMessage)
      assertEquals(count, List(Map("n" -> SqlValue.Int(1))))
      assertEquals(text(viaLambda), "Int(1) Text(hello)")
  }

  test("OKAY-STORE ON POSTGRES (RDS Proxy's way): a mapped type's program, the same answers as everywhere") {
    assume(postgres.isDefined, "OKAY_POSTGRES")
    import okay.serverless.store.{StoreOn, StorePrograms}
    import StorePrograms.*
    type D[X] = (Cloud + okay.store.Data)[X]
    type P[X] = (D + S)[X]
    val db = s"store$unique"
    val pg = AwsPorts(client, table, sql = AwsPorts.postgres(_ => postgres)).platform
    for
      _ <- pg.future(Sql.run[Cloud]("main", s"create schema $db"))
      got <- AwsPorts(client, table,
          sql = AwsPorts.postgres(_ => postgres.map(u => s"$u?options=-c%20search_path%3D$db")))
        .platform.future(StoreOn.sql[D, Cloud, Product]("main", okay.store.Dialect.postgres)(
          StoreOn.data[Sale, P, D, Product](sales)(program[P])))
    yield assertEquals(got, expected)
  }

  test("OKAY-STORE'S JOURNALED RECORDS ON POSTGRES: okay-store's own handler, the answers of its reference") {
    assume(postgres.isDefined, "OKAY_POSTGRES")
    import okay.serverless.store.{Journal, JournalPrograms, StoreOn}
    type D[X] = (Cloud + okay.store.Data)[X]
    val db = s"journal$unique"
    for
      _ <- AwsPorts(client, table, sql = AwsPorts.postgres(_ => postgres)).platform
        .future(Sql.run[Cloud]("main", s"create schema $db"))
      got <- AwsPorts(client, table,
          sql = AwsPorts.postgres(_ => postgres.map(u => s"$u?options=-c%20search_path%3D$db")))
        .platform.future(StoreOn.sql[D, Cloud, JournalPrograms.Out]("main", okay.store.Dialect.postgres)(
          Journal[D, JournalPrograms.Out](JournalPrograms.ids(), JournalPrograms.clock())(JournalPrograms.program)))
    yield assertEquals(got, JournalPrograms.reference)
  }

  test("A LAMBDA SERVING A MAPPED TYPE: okay-faces' REST and MCP of it, the type on Postgres") {
    assume(postgres.isDefined, "OKAY_POSTGRES")
    import okay.effects.cek.OnCek
    import okay.serverless.faces.Faces
    import okay.serverless.store.{Repo, StoreOn, StorePrograms}
    import StorePrograms.{Sale, sales}
    type D[X] = (Cloud + okay.store.Data)[X]
    type DS[X] = (D + Repo.Of[Sale])[X]
    val rest = Faces.rest(okay.store.Mapping.describe(sales), "/api/sales")
    val mcp = Faces.mcp(okay.store.Mapping.describe(sales), "shop")
    object Api extends App:
      override def fetch(r: Request): Response ! Cloud =
        StoreOn.sql[D, Cloud, Response]("main", okay.store.Dialect.postgres)(StoreOn.data[Sale, DS, D, Response](
          sales)(OnCek.into[DS](if r.path == "/mcp" then mcp(r) else rest(r))))
    val db = s"faces$unique"
    for
      _ <- AwsPorts(client, table, sql = AwsPorts.postgres(_ => postgres)).platform
        .future(Sql.run[Cloud]("main", s"create schema $db"))
      api = Lambda(Api, AwsPorts(client, table,
        sql = AwsPorts.postgres(_ => postgres.map(u => s"$u?options=-c%20search_path%3D$db"))))
      created <- api.handle(httpEvent("POST", "/api/sales/create", """{"item":"tea","qty":3,"tags":["hot"]}"""))
      tools <- api.handle(httpEvent("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
        """"params":{"name":"sales_count","arguments":{}}}"""))
    yield
      assert(text(created).contains("\"id\":\"000000000001\""), text(created))
      assert(text(tools).contains("\"structuredContent\":1"), text(tools))
  }

  test("A LAMBDA BEHIND A TOKEN: admitted first, to the app and to an object; the caller passed on") {
    g.process.env.OKAY_TEST_TOKEN = "tok"
    val guarded = Lambda(Auth.guarded(Shop, Auth.secret("OKAY_TEST_TOKEN")), ports)
    def withToken(e: js.Dynamic, t: String) = { e.headers.authorization = s"Bearer $t"; e }
    for
      _ <- ready
      none <- guarded.handle(httpEvent("POST", "/kv", "x"))
      wrong <- guarded.handle(withToken(httpEvent("POST", "/kv", "x"), "nope"))
      fine <- guarded.handle(withToken(httpEvent("POST", "/kv", "admitted"), "tok"))
      toObject <- guarded.handle(httpEvent("POST", s"/count/c$unique"))
    yield
      assertEquals(none.asInstanceOf[js.Dynamic].statusCode.asInstanceOf[Int], 401)
      assertEquals(wrong.asInstanceOf[js.Dynamic].statusCode.asInstanceOf[Int], 401)
      assertEquals(text(fine), "admitted")
      assertEquals(toObject.asInstanceOf[js.Dynamic].statusCode.asInstanceOf[Int], 401)
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
