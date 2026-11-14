package okay.serverless.cloud

import okay.scala3.*

/**
 * CRYPTOGRAPHY AS AN EFFECT (specs/cloud.md "Crypto"): random bytes, a digest, RSA-OAEP encryption to a public key,
 * AES-256-CBC encryption (another party's format), AES-256-GCM sealing and opening (our own secrets at rest). Not a
 * cloud's service but the runtime's: the Web Crypto API in a Worker, Node or a browser, the JDK's JCA on the JVM —
 * `Platform.run` handles it by the runtime's own (`CryptoNative`, a handler's): it is in the `Cloud` row, last (in
 * 0.2.0 as republished), so a program performs it and nothing in the program handles it. Keys and data are bytes;
 * a public key is its SubjectPublicKeyInfo (DER) — `Crypto.spki` takes it out of an X.509 certificate
 */
enum Crypto[X]:
  /** `n` bytes from the runtime's secure generator */
  case Random(n: Int) extends Crypto[Bytes]
  /** SHA-256 of the data */
  case Sha256(data: Bytes) extends Crypto[Bytes]
  /** RSA-OAEP with SHA-256 (and MGF1 with SHA-256) to the public key `spki` (DER) */
  case RsaOaep(spki: Bytes, data: Bytes) extends Crypto[Bytes]
  /** AES-CBC (a 16, 24 or 32 bytes key) with PKCS#7 padding, the IV 16 bytes */
  case AesCbc(key: Bytes, iv: Bytes, data: Bytes) extends Crypto[Bytes]
  /** AES-GCM (a 32 bytes key, the IV 12 bytes): the ciphertext with its 16 bytes tag at the end */
  case Seal(key: Bytes, iv: Bytes, data: Bytes) extends Crypto[Bytes]
  /** AES-GCM opened: the data, or none when the key is not the one or anything was changed */
  case Open(key: Bytes, iv: Bytes, boxed: Bytes) extends Crypto[Option[Bytes]]
  /** ECDSA on P-256 with SHA-256 by the private key `pkcs8` (DER): the signature as JWS writes it, r and s, 64 bytes
   * (APNs' tokens) */
  case SignEs256(pkcs8: Bytes, data: Bytes) extends Crypto[Bytes]
  /** RSASSA-PKCS1-v1_5 with SHA-256 by the private key `pkcs8` (DER) (a Google service account's tokens) */
  case SignRs256(pkcs8: Bytes, data: Bytes) extends Crypto[Bytes]

object Crypto:
  def random[R[_]](n: Int)(using In[Crypto, R]): Bytes ! R = perform[Crypto](Random(n))
  def sha256[R[_]](data: Bytes)(using In[Crypto, R]): Bytes ! R = perform[Crypto](Sha256(data))
  def rsaOaep[R[_]](spki: Bytes, data: Bytes)(using In[Crypto, R]): Bytes ! R = perform[Crypto](RsaOaep(spki, data))
  def aesCbc[R[_]](key: Bytes, iv: Bytes, data: Bytes)(using In[Crypto, R]): Bytes ! R =
    perform[Crypto](AesCbc(key, iv, data))
  def seal[R[_]](key: Bytes, iv: Bytes, data: Bytes)(using In[Crypto, R]): Bytes ! R =
    perform[Crypto](Seal(key, iv, data))
  def open[R[_]](key: Bytes, iv: Bytes, boxed: Bytes)(using In[Crypto, R]): Option[Bytes] ! R =
    perform[Crypto](Open(key, iv, boxed))
  def signEs256[R[_]](pkcs8: Bytes, data: Bytes)(using In[Crypto, R]): Bytes ! R =
    perform[Crypto](SignEs256(pkcs8, data))
  def signRs256[R[_]](pkcs8: Bytes, data: Bytes)(using In[Crypto, R]): Bytes ! R =
    perform[Crypto](SignRs256(pkcs8, data))

  /** a PEM's DER: the base64 between its BEGIN and END lines (an APNs `.p8`, a service account's `private_key`) */
  def pem(text: String): Bytes =
    Bytes(java.util.Base64.getMimeDecoder.decode(text.linesIterator.filterNot(_.startsWith("-----")).mkString))

  /** `p`'s cryptography given to the row's: before, the runtime's handler, installed here — now the platform's
   * (`Crypto` is in `Cloud`), so this only gives `p`'s operations to `Rest`'s `Crypto` */
  @deprecated("Crypto is in the Cloud row: perform it there (Crypto.random[Cloud](n)); the platform handles it",
    "0.2.0")
  def run[R[_], Rest[_], A](p: A ! R)(using Split[Crypto, R, Rest], In[Crypto, Rest]): A ! Rest =
    val to = summon[In[Crypto, Rest]]
    handle(p, Handler.general[Crypto, Rest, A, A](pure)(
      [X] => (op: Crypto[X], k: X => A ! Rest) => perform[Crypto](op)(using to).flatMap(k)))

  /** the SubjectPublicKeyInfo (DER) of an X.509 certificate (DER): the seventh element of its TBSCertificate when it
   * has the explicit version, else the sixth; none when it is not a certificate */
  def spki(certificate: Bytes): Option[Bytes] =
    val a = certificate.toArray
    /** the element at `at`: its tag, where its content starts, where it ends */
    def element(at: Int): Option[(Int, Int, Int)] =
      if at + 2 > a.length then None
      else
        val tag = a(at) & 0xff
        val first = a(at + 1) & 0xff
        if first < 0x80 then Some((tag, at + 2, at + 2 + first))
        else
          val n = first & 0x7f
          if n == 0 || n > 4 || at + 2 + n > a.length then None
          else
            val len = (0 until n).foldLeft(0L)((acc, i) => (acc << 8) | (a(at + 2 + i) & 0xff))
            Option.when(at + 2 + n + len <= a.length)((tag, at + 2 + n, (at + 2 + n + len).toInt))
    def children(from: Int, until: Int): Vector[(Int, Int, Int, Int)] =
      Iterator.unfold(from)(p => if p >= until then None else element(p).map((t, s, e) => ((t, p, s, e), e)))
        .toVector
    for
      (0x30, cs, ce) <- element(0)
      (0x30, ts, te) <- children(cs, ce).headOption.map((t, _, s, e) => (t, s, e))
      fields = children(ts, te)
      at = if fields.headOption.exists(_._1 == 0xa0) then 6 else 5
      (0x30, start, _, end) <- fields.lift(at)
    yield Bytes(a.slice(start, end))
