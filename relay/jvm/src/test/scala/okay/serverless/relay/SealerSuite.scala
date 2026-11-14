package okay.serverless.relay

class SealerSuite extends munit.FunSuite:
  test("sealed and opened by the same pairing; another pairing, or a changed frame, opens nothing") {
    val p = Sealer.fresh("test")
    val s = Sealer(p)
    val frame = s.seal("hello, room")
    assertEquals(s.open(frame), Some("hello, room"))
    assertEquals(Sealer(Sealer.fresh("test")).open(frame), None)
    assertEquals(s.open(frame.updated(20, if frame(20) == 'A' then 'B' else 'A')), None)
    assertEquals(Sealer(Pairing(p.secret, "other")).open(frame), None)
  }

  test("the text the JDK writes is Hashes' text: a frame, a nonce, a fresh pairing's secret") {
    val p = Sealer.fresh("test")
    assertEquals(p.secret.length, 32)
    assertEquals(Pairing.of(p.text, "test").map(_.auth), Some(p.auth))
    val frame = Sealer(p).seal("x" * 50)
    assertEquals(Hashes.unbase64url(frame).map(Hashes.base64url), Some(frame))
    val n = Sealer(p).nonce
    assertEquals(n.length, 24)
    assertEquals(Hashes.unhex(n).map(Hashes.hex), Some(n))
  }
