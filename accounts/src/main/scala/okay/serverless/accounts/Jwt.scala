package okay.serverless.accounts

import okay.effects.json.{Descent, Json}
import okay.serverless.relay.Hashes

/** a public key of a JSON Web Key Set: its id, RSA's modulus and exponent */
final case class Jwk(kid: String, n: BigInt, e: BigInt)

/**
 * AN IDENTITY TOKEN CHECKED (specs/accounts.md): a JWT signed RS256 — what Sign in with Apple and Google give a phone
 * — read, its signature checked against the issuer's keys in plain Scala (`s^e mod n` by `BigInt`, the PKCS#1 v1.5
 * block with SHA-256's DigestInfo compared), its issuer, audience and time checked. The same on the JVM, in Node and
 * in a Worker; nothing of the runtime's crypto.
 */
object Jwt:
  /** a token read: its header and claims, the bytes signed, the signature */
  final case class Token(header: Json, claims: Json, signed: Array[Byte], signature: Array[Byte]):
    def kid: Option[String] = Jwt.text(header, "kid")
    def alg: Option[String] = Jwt.text(header, "alg")
    def claim(k: String): Option[String] = Jwt.text(claims, k)

  private def field(j: Json, k: String): Option[Json] = j match { case o: Json.Obj => o.get(k); case _ => None }
  private def text(j: Json, k: String): Option[String] = field(j, k).collect { case Json.Str(s) => s }

  def parse(token: String): Option[Token] = token.split('.') match
    case Array(h, c, s) =>
      for
        hb <- Hashes.unbase64url(h); cb <- Hashes.unbase64url(c); sig <- Hashes.unbase64url(s)
        header <- Descent.parse(new String(hb, "UTF-8")).toOption
        claims <- Descent.parse(new String(cb, "UTF-8")).toOption
      yield Token(header, claims, Hashes.utf8(s"$h.$c"), sig)
    case _ => None

  /** a key set's RSA keys (`{keys: [{kid, kty: RSA, n, e}]}`) */
  def keys(set: Json): Vector[Jwk] = field(set, "keys").collect { case Json.Arr(ks) => ks }.getOrElse(Vector.empty)
    .flatMap { k =>
      for
        kid <- text(k, "kid"); if text(k, "kty").contains("RSA")
        n <- text(k, "n").flatMap(Hashes.unbase64url); e <- text(k, "e").flatMap(Hashes.unbase64url)
      yield Jwk(kid, BigInt(1, n), BigInt(1, e))
    }

  /** SHA-256's DigestInfo, the prefix the hash is signed with (RFC 8017 §9.2) */
  private val digestInfo: Array[Byte] =
    "3031300d060960864801650304020105000420".grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  /** RSASSA-PKCS1-v1_5 with SHA-256: whether `signature` is `key`'s over `data` */
  def verifies(key: Jwk, data: Array[Byte], signature: Array[Byte]): Boolean =
    val k = (key.n.bitLength + 7) / 8
    val s = BigInt(1, signature)
    if signature.length != k || s >= key.n then false
    else
      val m = s.modPow(key.e, key.n).toByteArray.dropWhile(_ == 0)
      val em = Array.fill[Byte](k - m.length)(0) ++ m
      val t = digestInfo ++ Hashes.sha256(data)
      val expected = Array[Byte](0, 1) ++ Array.fill[Byte](k - t.length - 3)(0xff.toByte) ++ Array[Byte](0) ++ t
      k >= t.length + 11 && java.util.Arrays.equals(em, expected)

  /**
   * THE PERSON A TOKEN NAMES, `sub`, when it is signed RS256 by one of `keys`, issued by one of `issuers` for one of
   * `audiences`, and `now` (seconds) is before it expires; else why not
   */
  def check(token: Token, keys: Vector[Jwk], issuers: Set[String], audiences: Set[String], now: Long)
  : Either[String, String] =
    def audience: Set[String] = field(token.claims, "aud") match
      case Some(Json.Str(a)) => Set(a)
      case Some(Json.Arr(as)) => as.collect { case Json.Str(a) => a }.toSet
      case _ => Set.empty
    def expires: Option[Long] = field(token.claims, "exp").collect { case Json.Num(n) => n.toLong }
    for
      _ <- Either.cond(token.alg.contains("RS256"), (), "not RS256")
      key <- keys.find(k => token.kid.contains(k.kid)).toRight("no such key")
      _ <- Either.cond(verifies(key, token.signed, token.signature), (), "not signed by the key")
      _ <- Either.cond(token.claim("iss").exists(issuers), (), "another issuer")
      _ <- Either.cond(audience.exists(audiences), (), "for another app")
      _ <- Either.cond(expires.exists(_ > now), (), "expired")
      sub <- token.claim("sub").filter(_.nonEmpty).toRight("no subject")
    yield sub
