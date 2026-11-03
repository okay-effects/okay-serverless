package okay.serverless.relay

/** the pairing's derivations and a frame's envelope — the same on the JVM, in a browser, on a phone (Native) */
class PairingSuite extends munit.FunSuite:
  test("SHA-256 as everyone has it; base64url both ways"):
    assertEquals(Hashes.hex(Hashes.sha256(Hashes.utf8("abc"))),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    val b = Array.tabulate[Byte](32)(i => (i * 7).toByte)
    assertEquals(Hashes.unbase64url(Hashes.base64url(b)).map(_.toList), Some(b.toList))

  test("a pairing: its keys from the secret, its room the hash of its auth; a frame's envelope opened once"):
    val p = Pairing(Array.fill[Byte](32)(1), "okay-remote")
    assertEquals(p.room, Pairing.roomOf(p.auth))
    assertEquals(p.enc.length, 32)
    assertEquals(Pairing.of(p.text, "okay-remote").map(_.auth), Some(p.auth))
    val seen = Envelope.Seen()
    val text = Envelope.wrap("host", 1000L, "nonce-0001", "event", "name" -> okay.effects.json.Json.Str("hello"))
    assert(Envelope.open(text, "host", 1000L, seen).isRight)
    assert(Envelope.open(text, "host", 1000L, seen).isLeft)           // seen before
    assert(Envelope.open(text, "guest", 1000L, Envelope.Seen()).isLeft)   // the wrong side
