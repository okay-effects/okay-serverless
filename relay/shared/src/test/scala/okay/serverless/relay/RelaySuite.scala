package okay.serverless.relay

import okay.scala3.*
import okay.serverless.cloud.*
import scala.concurrent.ExecutionContext.Implicits.global

class RelaySuite extends munit.FunSuite:
  test("SHA-256 and HMAC-SHA-256: the known answers") {
    assertEquals(Hashes.hex(Hashes.sha256(Hashes.utf8("abc"))),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertEquals(Hashes.hex(Hashes.sha256(Array.emptyByteArray)),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assertEquals(Hashes.hex(Hashes.hmac(Hashes.utf8("Jefe"), Hashes.utf8("what do ya want for nothing?"))),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
  }

  test("base64url both ways, every length") {
    for n <- 0 to 40 do
      val b = Array.tabulate(n)(i => (i * 37 + 11).toByte)
      assertEquals(Hashes.unbase64url(Hashes.base64url(b)).map(_.toList), Some(b.toList))
    assertEquals(Hashes.unbase64url("not*base64"), None)
  }

  test("a pairing derives what both ends share — the same as before the move (okay-remote's label)") {
    val p = Pairing(Array.tabulate(32)(_.toByte), "okay-remote")
    assertEquals(Hashes.hex(p.enc), "1f2550041ca423d7ea248c31489c986d803764214e5ead5bcbf6522ee15360b7")
    assertEquals(p.auth, "088aa32fd19c60e32617562c9fd36003c6ebd488cedde399c35664ec90a7c9b1")
    assertEquals(p.room, "158d356c43e2e1d26ed2ac5bdf9dbb65ee90a893e29631e0c7d0d89773967595")
    assertEquals(Pairing.of(p.text, "okay-remote").map(_.room), Some(p.room))
    assertEquals(Pairing.of("short", "okay-remote"), None)
  }

  test("a token names its room until its deadline, signed: forged, expired or of another secret — nothing") {
    val room = "ab" * 32
    val t = Tokens.sign(room, "s3cret", 2000000)
    assert(t.length <= 64 && t.matches("[A-Za-z0-9_-]+"), t)
    assertEquals(Tokens.check(t, "s3cret", 1000000), Some(room))
    assertEquals(Tokens.check(t, "s3cret", 3000000), None)
    assertEquals(Tokens.check(t, "other", 1000000), None)
    assertEquals(Tokens.check(t.updated(3, if t(3) == 'A' then 'B' else 'A'), "s3cret", 1000000), None)
  }

  test("an envelope: from the other side, in time, new — taken once; reflected, stale, nonceless — not") {
    val seen = Envelope.Seen()
    val f = Envelope.wrap("guest", 1000000, "abcdef0123", "hello")
    assertEquals(Envelope.open(f, "guest", 1000500, seen).map(_._1), Right("hello"))
    assertEquals(Envelope.open(f, "guest", 1000600, seen), Left("seen before"))
    assertEquals(Envelope.open(Envelope.wrap("host", 0, "n0000001", "x"), "guest", 0, seen),
      Left("from the wrong side"))
    assertEquals(Envelope.open(Envelope.wrap("guest", 0, "n0000002", "x"), "guest", Envelope.windowMs + 1, seen),
      Left("out of time"))
  }

  /** a steward that says what it was told */
  final class Told extends Steward:
    val lines = scala.collection.mutable.Buffer.empty[(String, List[String], String)]
    override def hostTags(room: String, request: Request): List[String] ! InActor =
      pure(request.param("chat").map(c => s"chat:$c").toList)
    override def control(room: String, host: Socket, line: String): Unit ! InActor =
      pure { lines += ((room, host.tags, line)); () }

  test("a room: in by its auth; the host's frames to the guests, theirs to it; its control lines to the steward") {
    val cloud = MemoryCloud()
    val told = Told()
    cloud.actors("ROOMS") = Rooms(told)
    val p = Pairing(Array.tabulate(32)(i => (i + 1).toByte), "test")
    val ws = Map("upgrade" -> List("websocket"))
    def dial(side: String, auth: String = p.auth, extra: String = "") =
      cloud.connect("ROOMS", p.room, Request.get(s"/r/${p.room}/$side?auth=$auth$extra").copy(headers = ws))
    val refused = cloud.objects(Objects.Call("ROOMS", p.room,
      Request.get(s"/r/${p.room}/guest?auth=${"0" * 64}").copy(headers = ws)))
    for
      no <- refused
      early <- dial("guest")
      host <- dial("host", extra = "&chat=42")
      guest <- dial("guest")
      _ <- guest.send("sealed-1")
      _ <- host.send("sealed-2")
      _ <- host.send("ctl:notify hi")
      _ <- guest.send("ctl:nothing")
      _ <- guest.send("ping")
      second <- dial("host")
      _ <- second.close()
    yield
      assertEquals(no.status, 403)
      assertEquals(early.received.toList.take(2), List("offline", "online"))
      assertEquals(guest.received.toList, List("online", "sealed-2", "pong", "online", "offline"))
      assertEquals(host.received.toList, List("sealed-1"))
      assertEquals(told.lines.toList, List((p.room, List("host", "chat:42"), "notify hi")))
      assertEquals(host.closedWith, Some(4000))
  }

  test("a room's route: /r/<room>/<side>, and nothing else") {
    assertEquals(Rooms.route(Request.get(s"/r/${"a" * 64}/host"), "ROOMS"), Some(("ROOMS", "a" * 64)))
    assertEquals(Rooms.route(Request.get(s"/r/${"a" * 64}/admin"), "ROOMS"), None)
    assertEquals(Rooms.route(Request.get("/r/x/host"), "ROOMS"), None)
  }
