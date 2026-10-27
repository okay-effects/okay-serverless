package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.*

import scala.concurrent.ExecutionContext

/** ADMISSION: a token, its caller; none, or an unknown one, refused before anything; a caller from outside dropped */
class AuthSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext

  object Echo extends App:
    override def fetch(r: Request): Response ! Cloud = pure(Response.text(Auth.caller(r).getOrElse("nobody")))
    override def objectFor(r: Request): Option[(String, String)] = Option.when(r.path == "/room")(("ROOMS", "a"))
  object Rooms extends Actor:
    def fetch(name: String, r: Request): Response ! InActor = pure(Response.text(s"room for ${Auth.caller(r)}"))

  test("bearer tokens: the environment's secret, KV's; refused without one, or an unknown; open paths open") {
    val c = MemoryCloud()
    c.secrets("API_TOKEN") = "s3cret"
    c.actors("ROOMS") = Rooms
    val app = Auth.guarded(Echo, t => Auth.secret("API_TOKEN")(t).flatMap {
      case Some(who) => pure(Some(who))
      case None => Auth.kv("TOKENS")(t)
    }, open = _ == "/health")
    def call(path: String, token: Option[String], extra: Map[String, List[String]] = Map.empty) =
      c.serve(app)(Request.get(path).copy(headers = extra ++ token.map(t => "authorization" -> List(s"Bearer $t"))))
    for
      _ <- c.platform.future(Kv.put[Cloud]("TOKENS", "token/k-ann", "ann"))
      none <- call("/", None)
      wrong <- call("/", Some("nope"))
      owner <- call("/", Some("s3cret"))
      ann <- call("/", Some("k-ann"))
      spoofed <- call("/", Some("k-ann"), Map(Auth.callerHeader -> List("admin")))
      health <- call("/health", None, Map(Auth.callerHeader -> List("admin")))
      room <- call("/room", Some("k-ann"))
      roomShut <- call("/room", None)
    yield
      assertEquals((none.status, wrong.status), (401, 401))
      assertEquals(none.headerOf("www-authenticate"), Some("Bearer"))
      assertEquals((owner.text, ann.text, spoofed.text), ("owner", "ann", "ann"))
      assertEquals(health.text, "nobody", "an open path: let in, a caller from outside dropped")
      assertEquals((room.text, roomShut.status), ("room for Some(ann)", 401), "an object's request admitted too")
  }

  test("the same in constant time: equal, unequal, of another length") {
    assert(Auth.same("abc", "abc"))
    assert(!Auth.same("abc", "abd"))
    assert(!Auth.same("abc", "abcd"))
  }
