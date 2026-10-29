package okay.serverless.host

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.scala3.*
import okay.serverless.Durable
import okay.serverless.cloud.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, CompletionStage, LinkedBlockingQueue, TimeUnit}

/** A COUNTER, an object a name: its count durable, its sockets a room, its alarm a mark */
object Counter extends Actor:
  def fetch(name: String, r: Request): Response ! InActor =
    if r.path.startsWith("/ws") then Sockets.accept[InActor](r, "room")
    else r.path match
      case "/tell" => Sockets.broadcast[InActor](r.body, Some("room")).map(n => Response.text(n.toString))
      case "/wake" => Durable.step[ActorStep, InActor, Unit](name)(Durable.wakeAt[ActorStep](0L))
          .as(Response.text("set"))
      case "/woke" => Durable.step[ActorStep, InActor, Option[Boolean]](name)(Durable.get[Boolean, ActorStep]("woke"))
          .map(w => Response.text(w.contains(true).toString))
      case _ => Durable.step[ActorStep, InActor, Long](name)(Durable.get[Long, ActorStep]("n").flatMap { n =>
          val next = n.getOrElse(0L) + 1
          Durable.put[Long, ActorStep]("n", next).as(next)
        }).map(n => Response.text(n.toString))
  override def message(name: String, socket: Socket, text: String): Unit ! InActor =
    Sockets.send[InActor](socket, s"echo: $text")
  override def alarm(name: String): Unit ! InActor =
    Durable.step[ActorStep, InActor, Unit](name)(Durable.put[Boolean, ActorStep]("woke", true))

/** AN APP: a greeting, KV, the counters, a queue whose "fail" never succeeds, a cron that writes its mark */
object TheApp extends App:
  override def objectFor(r: Request): Option[(String, String)] =
    Option.when(r.path.startsWith("/ws/"))(("COUNTERS", r.path.stripPrefix("/ws/")))
  override def fetch(r: Request): Response ! Cloud = (r.method, r.path.split('/').toList) match
    case ("GET", List("", "hello")) => pure(Response.text("hello"))
    case ("POST", List("", "kv", k)) => Kv.put[Cloud]("NS", k, r.body).as(Response.text("ok"))
    case ("GET", List("", "kv", k)) => Kv.get[Cloud]("NS", k).map(v => v.fold(Response.notFound())(Response.text(_)))
    case (_, List("", "count", name)) => Objects.call[Cloud]("COUNTERS", name, Request("POST", "/count"))
    case (_, List("", "tell", name)) => Objects.call[Cloud]("COUNTERS", name, Request.post("/tell", r.body))
    case (_, List("", "wake", name)) => Objects.call[Cloud]("COUNTERS", name, Request("POST", "/wake"))
    case (_, List("", "woke", name)) => Objects.call[Cloud]("COUNTERS", name, Request("GET", "/woke"))
    case ("POST", List("", "send")) => Queues.send[Cloud]("JOBS", r.body).as(Response.text("queued"))
    case _ => pure(Response.notFound())
  override def queue(queue: String, m: Message): Settle ! Cloud =
    if m.body == "fail" then pure(Settle.Retry())
    else Kv.put[Cloud]("NS", s"got-${m.body}", m.attempts.toString).as(Settle.Ack)
  override def scheduled(e: Scheduled): Unit ! Cloud = Kv.put[Cloud]("NS", "cron", e.cron)

class HostSuite extends munit.FunSuite:
  private val http = HttpClient.newHttpClient()
  private def tmp(): Path = Files.createTempDirectory("okay-host")

  /** a host on a port of its own, its ticks by hand (`tick`), its clock `time` */
  private final class Running(dir: Path, static: Option[Path] = None, crons: List[String] = Nil):
    @volatile var time: Long = System.currentTimeMillis()
    val host: Host = Host.start(TheApp, Map("COUNTERS" -> Counter),
      HostConfig(dir, port = 0, static = static, crons = crons, tickMillis = 3_600_000, clock = () => time,
        log = (_, _) => ()))
    def url(path: String): String = s"http://127.0.0.1:${host.port}$path"
    def get(path: String): HttpResponse[String] =
      http.send(HttpRequest.newBuilder(URI.create(url(path))).build(), HttpResponse.BodyHandlers.ofString())
    def post(path: String, body: String = ""): HttpResponse[String] =
      http.send(HttpRequest.newBuilder(URI.create(url(path))).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString())

  private def running[A](dir: Path = tmp(), static: Option[Path] = None, crons: List[String] = Nil)(
    f: Running => A): A =
    val r = Running(dir, static, crons)
    try f(r) finally r.host.stop()

  test("a request to the app, and a static file first"):
    val site = tmp()
    Files.writeString(site.resolve("index.html"), "<h1>static</h1>")
    running(static = Some(site)) { h =>
      assertEquals(h.get("/hello").body, "hello")
      assertEquals(h.get("/").body, "<h1>static</h1>")
      assert(h.get("/").headers().firstValue("content-type").get.startsWith("text/html"))
      assertEquals(h.get("/nothing").statusCode, 404)
      // nothing outside the static directory
      assertEquals(h.get("/../../etc/passwd").statusCode, 404)
    }

  test("an object's state and KV kept across a restart on the same directory"):
    val dir = tmp()
    running(dir) { h =>
      assertEquals(h.post("/count/a").body, "1")
      assertEquals(h.post("/count/a").body, "2")
      assertEquals(h.post("/count/b").body, "1")
      h.post("/kv/k", "v1")
    }
    running(dir) { h =>
      assertEquals(h.post("/count/a").body, "3")
      assertEquals(h.get("/kv/k").body, "v1")
    }

  test("a WebSocket both ways: the object's echo, and what the object is told"):
    running() { h =>
      val got = LinkedBlockingQueue[String]()
      val ws = http.newWebSocketBuilder().buildAsync(URI.create(s"ws://127.0.0.1:${h.host.port}/ws/room1"),
        new WebSocket.Listener:
          override def onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
            got.put(data.toString)
            w.request(1)
            CompletableFuture.completedFuture(null)
      ).get(10, TimeUnit.SECONDS)
      ws.sendText("hi", true).get(10, TimeUnit.SECONDS)
      assertEquals(got.poll(10, TimeUnit.SECONDS), "echo: hi")
      assertEquals(h.post("/tell/room1", "news").body, "1")
      assertEquals(got.poll(10, TimeUnit.SECONDS), "news")
      ws.sendClose(1000, "bye").get(10, TimeUnit.SECONDS): Unit
    }

  test("an alarm: set by a step, fired by a tick, cleared"):
    running() { h =>
      assertEquals(h.post("/wake/w").body, "set")
      assertEquals(h.get("/woke/w").body, "false")
      h.host.tick()
      assertEquals(h.get("/woke/w").body, "true")
      assertEquals(h.host.cloud.store.alarm("COUNTERS", "w"), None)
    }

  test("a queue: delivered by a tick, retried, given up after three attempts"):
    running() { h =>
      h.post("/send", "job1")
      h.post("/send", "fail")
      h.host.tick()
      assertEquals(h.get("/kv/got-job1").body, "1")
      h.host.tick()
      h.host.tick()
      assertEquals(h.host.cloud.store.dead, Vector("JOBS" -> "fail"))
      assertEquals(h.host.cloud.store.pending, 0)
    }

  test("a cron trigger: run in the minute it matches"):
    running(crons = List("*/5 * * * *")) { h =>
      h.time = 1_800_000_000_000L - 1_800_000_000_000L % 300_000 - 60_000 // a minute before a fifth minute
      h.host.tick()
      assertEquals(h.get("/kv/cron").statusCode, 404)
      h.time += 60_000
      h.host.tick()
      assertEquals(h.get("/kv/cron").body, "*/5 * * * *")
    }

class CronSuite extends munit.FunSuite:
  private def at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC).toInstant.toEpochMilli

  test("fields: every, steps, ranges, lists, days of the week"):
    val c = Cron.parse("*/15 9-17 * * 1-5").toOption.get
    assert(c.matches(at(2026, 10, 29, 9, 30)))  // a Thursday
    assert(!c.matches(at(2026, 10, 29, 9, 31)))
    assert(!c.matches(at(2026, 10, 31, 9, 30))) // a Saturday
    assert(Cron.parse("0 0 1,15 * *").toOption.get.matches(at(2026, 11, 15, 0, 0)))
    assert(Cron.parse("0 0 * * 7").toOption.get.matches(at(2026, 11, 1, 0, 0))) // 7 is Sunday
    // both days restricted: either
    assert(Cron.parse("0 0 1 * 1").toOption.get.matches(at(2026, 11, 2, 0, 0)))
    assert(Cron.parse("2 * * *").isLeft)
    assert(Cron.parse("61 * * * *").isLeft)

class AiSuite extends munit.FunSuite:
  test("Workers AI's shapes by a server of OpenAI's API: a chat, an embedding, a model mapped"):
    val asked = LinkedBlockingQueue[String]()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", ex =>
      val body = String(ex.getRequestBody.readAllBytes(), "UTF-8")
      asked.put(s"${ex.getRequestURI.getPath} $body")
      val out =
        if ex.getRequestURI.getPath.endsWith("/chat/completions") then
          """{"choices":[{"message":{"role":"assistant","content":"Paris."}}],"usage":{"total_tokens":5}}"""
        else """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}"""
      ex.sendResponseHeaders(200, out.length.toLong)
      ex.getResponseBody.write(out.getBytes("UTF-8"))
      ex.close())
    server.start()
    try
      given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
      val ai = OpenAiAi(AiServer(s"http://127.0.0.1:${server.getAddress.getPort}",
        models = Map("@cf/google/gemma" -> "qwen3-4b")), JdkFetch())
      def await[A](f: scala.concurrent.Future[A]): A =
        scala.concurrent.Await.result(f, scala.concurrent.duration.Duration(10, "s"))
      val chat = await(ai(Ai.Run("@cf/google/gemma", Json.obj("messages" -> Json.arr(Json.obj(
        "role" -> Json.Str("user"), "content" -> Json.Str("capital of France?"))), "max_tokens" -> Json.num(20)))))
      assertEquals(chat.field("response"), Some(Json.Str("Paris.")))
      val first = asked.poll()
      assert(first.startsWith("/v1/chat/completions "), first)
      assertEquals(Descent.parse(first.dropWhile(_ != ' ').trim).toOption.flatMap(_.field("model")),
        Some(Json.Str("qwen3-4b")))
      val emb = await(ai(Ai.Run("@cf/baai/bge-m3", Json.obj("text" -> Json.arr(Json.Str("a"), Json.Str("b"))))))
      assertEquals(emb.field("data").collect { case Json.Arr(rows) => rows.size }, Some(2))
      assert(asked.poll().startsWith("/v1/embeddings "))
    finally server.stop(0)

class HostStoreSuite extends munit.FunSuite:
  test("a commit over what changed since it was read writes nothing; over what did not, everything at once"):
    val store = HostStore(Files.createTempDirectory("okay-store").resolve("okay.sqlite"))
    try
      assert(store.commit("N", "a", Map("x" -> 0L), Map("x" -> Some("1"), "y" -> Some("1")), None))
      assertEquals(store.read("N", "a", List("x", "y")).view.mapValues(_.version).toMap, Map("x" -> 1L, "y" -> 1L))
      // another writer moved x on: a step that read x at version 1 commits; one that read it at 0 does not
      assert(!store.commit("N", "a", Map("x" -> 0L), Map("x" -> Some("stale"), "z" -> Some("1")), None))
      assertEquals(store.read("N", "a", List("x", "z")).view.mapValues(_.value).toMap, Map("x" -> "1"))
      assert(store.commit("N", "a", Map("x" -> 1L), Map("x" -> None), Some(50L)))
      assertEquals(store.keys("N", "a", ""), List("y"))
      // the earlier of two alarms
      assert(store.commit("N", "a", Map.empty, Map.empty, Some(80L)))
      assertEquals(store.alarm("N", "a"), Some(50L))
    finally store.close()
