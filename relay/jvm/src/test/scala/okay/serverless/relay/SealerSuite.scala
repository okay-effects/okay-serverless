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
