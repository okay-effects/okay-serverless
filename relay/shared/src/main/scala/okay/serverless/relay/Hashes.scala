package okay.serverless.relay

import java.nio.charset.StandardCharsets.UTF_8

/** SHA-256, HMAC-SHA-256, hex and base64url in plain Scala: the same on the JVM, in a browser and in a Worker */
object Hashes:
  private val k = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2)

  def utf8(s: String): Array[Byte] = s.getBytes(UTF_8)

  def sha256(data: Array[Byte]): Array[Byte] =
    val h = Array(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
    val bits = data.length.toLong * 8
    val padded = new Array[Byte](((data.length + 8) / 64 + 1) * 64)
    System.arraycopy(data, 0, padded, 0, data.length)
    padded(data.length) = 0x80.toByte
    for i <- 0 until 8 do padded(padded.length - 1 - i) = (bits >>> (8 * i)).toByte
    val w = new Array[Int](64)
    for chunk <- 0 until padded.length / 64 do
      for t <- 0 until 16 do
        val o = chunk * 64 + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) | ((padded(o + 2) & 0xff) << 8) |
          (padded(o + 3) & 0xff)
      for t <- 16 until 64 do
        val s0 = Integer.rotateRight(w(t - 15), 7) ^ Integer.rotateRight(w(t - 15), 18) ^ (w(t - 15) >>> 3)
        val s1 = Integer.rotateRight(w(t - 2), 17) ^ Integer.rotateRight(w(t - 2), 19) ^ (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1
      var (a, b, c, d, e, f, g, hh) = (h(0), h(1), h(2), h(3), h(4), h(5), h(6), h(7))
      for t <- 0 until 64 do
        val s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
        val t1 = hh + s1 + ((e & f) ^ (~e & g)) + k(t) + w(t)
        val s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
        val t2 = s0 + ((a & b) ^ (a & c) ^ (b & c))
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
      h(0) += a; h(1) += b; h(2) += c; h(3) += d; h(4) += e; h(5) += f; h(6) += g; h(7) += hh
    h.flatMap(x => Array((x >>> 24).toByte, (x >>> 16).toByte, (x >>> 8).toByte, x.toByte))

  def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val k0 = if key.length > 64 then sha256(key) else key
    val block = k0 ++ new Array[Byte](64 - k0.length)
    val inner = block.map(b => (b ^ 0x36).toByte)
    val outer = block.map(b => (b ^ 0x5c).toByte)
    sha256(outer ++ sha256(inner ++ message))

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  def unhex(s: String): Option[Array[Byte]] =
    if s.length % 2 != 0 || !s.forall(c => "0123456789abcdef".contains(c)) then None
    else Some(s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
  def base64url(b: Array[Byte]): String =
    val out = new StringBuilder
    b.grouped(3).foreach { g =>
      val n = (g.padTo(3, 0.toByte).foldLeft(0)((acc, x) => (acc << 8) | (x & 0xff)))
      val chars = g.length + 1
      for i <- 0 until chars do out += alphabet((n >>> (18 - 6 * i)) & 63)
    }
    out.toString
  def unbase64url(s: String): Option[Array[Byte]] =
    if !s.forall(alphabet.contains(_)) || s.length % 4 == 1 then None
    else Some(s.grouped(4).flatMap { g =>
      val n = g.map(alphabet.indexOf(_)).padTo(4, 0).foldLeft(0)((acc, x) => (acc << 6) | x)
      (0 until g.length - 1).map(i => ((n >>> (16 - 8 * i)) & 0xff).toByte)
    }.toArray)

  /** two secrets compared in a time that does not tell how much of them matched */
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
