package okay.serverless.cloud

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Durable, Storage}

import scala.concurrent.{ExecutionContext, Future}

/** A CLOUD'S EFFECTS on the memory platform: the same programs a Worker or a Lambda runs */
class CloudSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext

  var time = 1_000_000L
  def cloud(): MemoryCloud = MemoryCloud(() => time)

  test("KV: a value with its metadata; expiry by ttl; keys by prefix, page by page") {
    val c = cloud()
    for
      got <- c.platform.future(for
        _ <- Kv.put[Cloud]("sessions", "s1", "ann", ttlSeconds = Some(60), metadata = Some("""{"v":1}"""))
        _ <- Kv.put[Cloud]("sessions", "s2", "bob")
        _ <- Kv.put[Cloud]("sessions", "s3", "cy")
        e <- Kv.entry[Cloud]("sessions", "s1")
        p1 <- Kv.list[Cloud]("sessions", "s", limit = 2)
        p2 <- Kv.list[Cloud]("sessions", "s", cursor = p1.cursor, limit = 2)
      yield (e, p1, p2))
      _ = time += 61_000
      after <- c.platform.future(Kv.get[Cloud]("sessions", "s1"))
    yield
      assertEquals(got._1, Some(KvEntry("ann", Some("""{"v":1}"""))))
      assertEquals(got._2, Page(List("s1", "s2"), Some("s2")))
      assertEquals(got._3, Page(List("s3"), None))
      assertEquals(after, None)
  }

  test("blobs: put, a range, head, list, delete") {
    val c = cloud()
    c.platform.future(for
      info <- Blobs.put[Cloud]("uploads", "a/one.txt", Bytes.of("hello, world"), Some("text/plain"))
      part <- Blobs.get[Cloud]("uploads", "a/one.txt", Some((7L, 12L)))
      head <- Blobs.head[Cloud]("uploads", "a/one.txt")
      listed <- Blobs.list[Cloud]("uploads", "a/")
      _ <- Blobs.delete[Cloud]("uploads", "a/one.txt")
      gone <- Blobs.head[Cloud]("uploads", "a/one.txt")
    yield
      assertEquals(info.size, 12L)
      assertEquals(part.map(_._2.text), Some("world"))
      assertEquals(head.flatMap(_.contentType), Some("text/plain"))
      assertEquals(listed.items.map(_.key), List("a/one.txt"))
      assertEquals(gone, None))
  }

  object Mailer extends App:
    override def queue(queue: String, m: Message): Settle ! Cloud =
      if m.body == "flaky" && m.attempts < 2 then pure(Settle.Retry())
      else if m.body == "broken" then defer(throw IllegalStateException("cannot"))
      else Mails.send[Cloud]("out", Mail("app@x", m.body, "hi", "")).as(Settle.Ack)

  test("queues: sent with a delay; delivered; a retry later; failing ones dead after the attempts") {
    val c = cloud()
    for
      _ <- c.platform.future(Queues.send[Cloud]("mail", "ann@x").andThen(Queues.send[Cloud]("mail", "flaky"))
        .andThen(Queues.send[Cloud]("mail", "broken")).andThen(Queues.send[Cloud]("mail", "later@x", 30)))
      n1 <- c.deliver(Mailer)
      n2 <- c.deliver(Mailer)
      n3 <- c.deliver(Mailer)
      _ = time += 30_000
      n4 <- c.deliver(Mailer)
    yield
      assertEquals((n1, n2, n3, n4), (3, 2, 1, 1))
      assertEquals(c.sent.map(_._2.to).toList, List("ann@x", "flaky", "later@x"))
      assertEquals(c.dead.map(_._2.body).toList, List("broken"))
      assertEquals(c.pending, 0)
  }

  test("fetch through the cache; env; rate limits; a model; analytics; logs") {
    val c = cloud()
    var calls = 0
    c.internet = r => { calls += 1; Response.text(s"price of ${r.path}") }
    c.vars("CURRENCY") = "EUR"
    c.secrets("KEY") = "k"
    c.limitsConfig("api") = (2, 60)
    c.model = (_, in) => Json.obj("echo" -> in)
    c.platform.future(for
      a <- Cache.through[Cloud]("https://p/x", 60)(Fetch[Cloud](Request.get("https://p/x")))
      b <- Cache.through[Cloud]("https://p/x", 60)(Fetch[Cloud](Request.get("https://p/x")))
      cur <- Env.variable[Cloud]("CURRENCY")
      key <- Env.secret[Cloud]("KEY")
      l <- Limits.take[Cloud]("api", "ann").flatMap(a => Limits.take[Cloud]("api", "ann").flatMap(b =>
        Limits.take[Cloud]("api", "ann").flatMap(c => Limits.take[Cloud]("api", "bob").map(d => List(a, b, c, d)))))
      out <- Ai.run[Cloud]("m", Json.Str("hi"))
      _ <- Analytics.write[Cloud]("views", DataPoint(List("ann"), List("/x"), List(1.0)))
      _ <- Log.info[Cloud]("done")
    yield
      assertEquals((a.text, b.text, calls), ("price of /x", "price of /x", 1))
      assertEquals((cur, key), (Some("EUR"), Some("k")))
      assertEquals(l, List(true, true, false, true))
      assertEquals(out, Json.obj("echo" -> Json.Str("hi")))
      assertEquals(c.points.toList, List("views" -> DataPoint(List("ann"), List("/x"), List(1.0))))
      assertEquals(c.logged.toList, List(Level.Info -> "done")))
  }

  test("vectors: the nearest, filtered") {
    val c = cloud()
    c.platform.future(for
      _ <- Vectors.upsert[Cloud]("docs", Embedding("a", List(1, 0)), Embedding("b", List(0.9, 0.1), Map("lang" -> "en")),
        Embedding("c", List(0, 1), Map("lang" -> "en")))
      all <- Vectors.query[Cloud]("docs", List(1, 0), topK = 2)
      en <- Vectors.query[Cloud]("docs", List(1, 0), topK = 1, filter = Map("lang" -> "en"))
    yield
      assertEquals(all.map(_.id), List("a", "b"))
      assertEquals(en.map(_.id), List("b")))
  }

  /** a chat room: an object; its members' sockets; its history durable */
  object Room extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      if request.header("upgrade").contains("websocket") then
        Sockets.accept[InActor](request, request.query.getOrElse("who", "guest"))
      else Durable.step[ActorStep, InActor, List[String]](name)(Durable.get[List[String], ActorStep]("history")
        .map(_.getOrElse(Nil))).map(h => Response.text(h.mkString("\n")))
    override def message(name: String, socket: Socket, text: String): Unit ! InActor =
      val line = s"${socket.tags.headOption.getOrElse("?")}: $text"
      Durable.step[ActorStep, InActor, Unit](name)(Durable.get[List[String], ActorStep]("history")
        .flatMap(h => Durable.put[List[String], ActorStep]("history", h.getOrElse(Nil) :+ line))
        .andThen(Durable.wakeAt[ActorStep](time + 1000)))
        .andThen(Sockets.broadcast[InActor](line)).as(())
    override def alarm(name: String): Unit ! InActor = Sockets.broadcast[InActor]("tick").as(())

  test("objects: one a name; sockets accepted, a message broadcast and kept; an alarm; the state durable") {
    val c = cloud()
    c.actors("rooms") = Room
    val ws = Map("upgrade" -> "websocket")
    for
      ann <- c.connect("rooms", "lobby", Request("GET", "https://x/?who=ann", ws))
      bob <- c.connect("rooms", "lobby", Request("GET", "https://x/?who=bob", ws))
      other <- c.connect("rooms", "other", Request("GET", "https://x/?who=cy", ws))
      _ <- ann.send("hello")
      history <- c.platform.future(Objects.call[Cloud]("rooms", "lobby", Request.get("https://x/")))
      _ = time += 1000
      fired <- c.fireAlarms()
    yield
      assertEquals(bob.received.toList, List("ann: hello", "tick"))
      assertEquals(other.received.toList, Nil)
      assertEquals(history.text, "ann: hello")
      assertEquals(fired, 1)
      assertEquals(c.stored("rooms", "lobby")("history"), """["ann: hello"]""")
  }

  object Front extends App:
    override def fetch(request: Request): Response ! Cloud =
      Services.call[Cloud]("back", request.copy(url = "https://back/hi")).map(r => Response.text("front+" + r.text))
  object Back extends App:
    override def fetch(request: Request): Response ! Cloud = pure(Response.text("back"))

  test("services: one app calling another by name") {
    val c = cloud()
    c.apps("back") = Back
    c.platform.future(Front.fetch(Request.get("https://front/"))).map(r => assertEquals(r.text, "front+back"))
  }

  test("a port the cloud lacks: the program fails, naming it") {
    c0.platform.future(Sql.query[Cloud]("main", "select 1")).failed.map(e =>
      assert(e.getMessage.startsWith("no SQL here"), e.getMessage))
  }
  lazy val c0: MemoryCloud = cloud()
