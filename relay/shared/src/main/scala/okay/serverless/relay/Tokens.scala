package okay.serverless.relay

/** A SIGNED TOKEN naming a room, until a deadline: `base64url(room ‖ until ‖ mac)` — 63 characters, fit for a
 * link's parameter (Telegram's `start`); checked with the same secret, no state kept */
object Tokens:
  def sign(room: String, secret: String, until: Long): String =
    val r = Hashes.unhex(room).getOrElse(Array.emptyByteArray)
    val t = Array.tabulate(5)(i => ((until / 1000) >>> (8 * (4 - i))).toByte)
    Hashes.base64url(r ++ t ++ Hashes.hmac(Hashes.utf8(secret), r ++ t).take(10))

  /** the room a token names, if it is signed by `secret` and not past its deadline */
  def check(token: String, secret: String, now: Long): Option[String] =
    Hashes.unbase64url(token).filter(_.length == 47).flatMap { b =>
      val (r, rest) = b.splitAt(32)
      val (t, mac) = rest.splitAt(5)
      val until = t.foldLeft(0L)((acc, x) => (acc << 8) | (x & 0xff)) * 1000
      val want = Hashes.hmac(Hashes.utf8(secret), r ++ t).take(10)
      if Hashes.same(Hashes.hex(want), Hashes.hex(mac)) && now <= until then Some(Hashes.hex(r)) else None
    }
