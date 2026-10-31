package okay.serverless.accounts

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.relay.Hashes

import scala.concurrent.{ExecutionContext, Future}

/** A small RSA key of the test's own, to sign tokens as Apple would: plain Scala, the same on every platform */
object TestKey:
  private val rnd = new java.util.Random(7)
  val e = BigInt(65537)
  val (n, d) =
    def prime = BigInt(java.math.BigInteger.probablePrime(1024, rnd))
    val p = prime; val q = prime
    (p * q, e.modInverse((p - 1) * (q - 1)))
  def jwks(kid: String): String = Json.print(Json.obj("keys" -> Json.Arr(Vector(Json.obj("kid" -> Json.Str(kid),
    "kty" -> Json.Str("RSA"), "alg" -> Json.Str("RS256"), "n" -> Json.Str(Hashes.base64url(unsigned(n))),
    "e" -> Json.Str(Hashes.base64url(unsigned(e))))))))
  def unsigned(b: BigInt): Array[Byte] = b.toByteArray.dropWhile(_ == 0)
  /** RSASSA-PKCS1-v1_5 with SHA-256 */
  def sign(data: Array[Byte], key: BigInt = d): Array[Byte] =
    val k = (n.bitLength + 7) / 8
    val t = "3031300d060960864801650304020105000420".grouped(2).map(Integer.parseInt(_, 16).toByte).toArray ++
      Hashes.sha256(data)
    val em = Array[Byte](0, 1) ++ Array.fill[Byte](k - t.length - 3)(0xff.toByte) ++ Array[Byte](0) ++ t
    val s = unsigned(BigInt(1, em).modPow(key, n))
    Array.fill[Byte](k - s.length)(0) ++ s
  def token(kid: String, claims: Json): String =
    val h = Hashes.base64url(Hashes.utf8(Json.print(Json.obj("alg" -> Json.Str("RS256"), "kid" -> Json.Str(kid)))))
    val c = Hashes.base64url(Hashes.utf8(Json.print(claims)))
    s"$h.$c.${Hashes.base64url(sign(Hashes.utf8(s"$h.$c")))}"

class AccountsSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  val now = 1_800_000_000_000L

  def cloud(): MemoryCloud =
    val c = MemoryCloud(() => now)
    Accounts.actors.foreach((k, a) => c.actors(k) = a)
    c.vars("ACCOUNTS_TEST") = "yes"
    c.vars("APPLE_CLIENT_IDS") = "com.example.app"
    c.internet = (url, _) =>
      if url.startsWith("https://appleid.apple.com/auth/keys") then Response.json(Descent.parse(TestKey.jwks("k1"))
        .toOption.get)
      else Response.text("no", 404)
    c
  def post(c: MemoryCloud, path: String, j: Json, session: Option[String] = None): Future[Response] =
    c.serve(Accounts())(Request.json("POST", path, j).copy(headers = Map("content-type" -> List("application/json")) ++
      session.map(s => "authorization" -> List(s"Bearer $s"))))
  def call(c: MemoryCloud, method: String, path: String, session: Option[String], j: Json = Json.Obj.empty) =
    c.serve(Accounts())(Request.json(method, path, j).copy(headers = session.map(s =>
      "authorization" -> List(s"Bearer $s")).toMap))
  def field(r: Response, k: String): Option[String] = Descent.parse(r.text).toOption.flatMap {
    case o: Json.Obj => o.get(k).map { case Json.Str(s) => s; case x => Json.print(x) }; case _ => None }

  test("a code by mail: the wrong one refused, the right one a session; the shared values the person's own") {
    val c = cloud()
    for
      sent <- post(c, "/auth/email", Json.obj("email" -> Json.Str("Ann@Example.org")))
      code = field(sent, "code").get
      wrong <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str("ann@example.org"),
        "code" -> Json.Str(if code == "000000" then "111111" else "000000")))
      right <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str("ann@example.org"), "code" -> Json.Str(code)))
      again <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str("ann@example.org"), "code" -> Json.Str(code)))
      s = field(right, "session")
      none <- call(c, "GET", "/shared/notes", None)
      put1 <- call(c, "PUT", "/shared/notes", s, Json.obj("value" -> Json.Str("milk")))
      put2 <- call(c, "PUT", "/shared/notes", s, Json.obj("value" -> Json.Str("milk\nbread")))
      got <- call(c, "GET", "/shared/notes", s)
      all <- call(c, "GET", "/shared", s)
      // another person sees none of it
      other <- post(c, "/auth/email", Json.obj("email" -> Json.Str("bob@example.org")))
      bob <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str("bob@example.org"),
        "code" -> Json.Str(field(other, "code").get)))
      bobs <- call(c, "GET", "/shared/notes", field(bob, "session"))
      gone <- call(c, "DELETE", "/shared/notes", s)
      after <- call(c, "GET", "/shared/notes", s)
      out <- post(c, "/auth/out", Json.Obj.empty, s)
      outs <- call(c, "GET", "/shared/notes", s)
    yield
      assertEquals(code.length, 6)
      assertEquals(wrong.status, 401)
      assertEquals(field(right, "who"), Some("email:ann@example.org"))
      assertEquals(again.status, 401, "a code is taken once")
      assertEquals(none.status, 401)
      assertEquals((field(put1, "version"), field(put2, "version")), (Some("1"), Some("2")))
      assertEquals(field(got, "value"), Some("milk\nbread"))
      assert(all.text.contains("\"notes\":2"), all.text)
      assertEquals(bobs.status, 404)
      assertEquals((gone.status, after.status), (200, 404))
      assertEquals(outs.status, 401, "signed out")
  }

  test("five wrong codes, and the code is no more") {
    val c = cloud()
    def wrong = post(c, "/auth/email/code", Json.obj("email" -> Json.Str("eve@example.org"), "code" -> Json.Str("x")))
    for
      sent <- post(c, "/auth/email", Json.obj("email" -> Json.Str("eve@example.org")))
      _ <- wrong; _ <- wrong; _ <- wrong; _ <- wrong; _ <- wrong
      late <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str("eve@example.org"),
        "code" -> Json.Str(field(sent, "code").get)))
    yield assertEquals(field(late, "error"), Some("too many tries"))
  }

  test("Apple's token: signed by its key, for this app, in time — a session; another app's, expired, forged — not") {
    val c = cloud()
    def claims(aud: String, exp: Long) = Json.obj("iss" -> Json.Str("https://appleid.apple.com"),
      "aud" -> Json.Str(aud), "exp" -> Json.num(exp), "sub" -> Json.Str("001234.abc"))
    val good = TestKey.token("k1", claims("com.example.app", now / 1000 + 600))
    val forged = good.dropRight(4) + (if good.endsWith("AAAA") then "BBBB" else "AAAA")
    for
      ok <- post(c, "/auth/apple", Json.obj("token" -> Json.Str(good)))
      other <- post(c, "/auth/apple", Json.obj("token" ->
        Json.Str(TestKey.token("k1", claims("com.evil", now / 1000 + 600)))))
      old <- post(c, "/auth/apple", Json.obj("token" ->
        Json.Str(TestKey.token("k1", claims("com.example.app", now / 1000 - 1)))))
      fake <- post(c, "/auth/apple", Json.obj("token" -> Json.Str(forged)))
    yield
      assertEquals(field(ok, "who"), Some("apple:001234.abc"))
      assertEquals(field(other, "error"), Some("for another app"))
      assertEquals(field(old, "error"), Some("expired"))
      assertEquals(field(fake, "error"), Some("not signed by the key"))
  }
