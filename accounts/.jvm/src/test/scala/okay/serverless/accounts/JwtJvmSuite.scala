package okay.serverless.accounts

import okay.serverless.relay.Hashes

/** the plain Scala check against the JDK's own: a token signed by `SHA256withRSA` verifies, a byte off does not */
class JwtJvmSuite extends munit.FunSuite:
  test("RS256 as the JDK signs it") {
    val g = java.security.KeyPairGenerator.getInstance("RSA"); g.initialize(2048)
    val pair = g.generateKeyPair()
    val pub = pair.getPublic.asInstanceOf[java.security.interfaces.RSAPublicKey]
    val data = Hashes.utf8("header.claims")
    val s = java.security.Signature.getInstance("SHA256withRSA"); s.initSign(pair.getPrivate); s.update(data)
    val sig = s.sign()
    val key = Jwk("k", BigInt(pub.getModulus), BigInt(pub.getPublicExponent))
    assert(Jwt.verifies(key, data, sig))
    assert(!Jwt.verifies(key, Hashes.utf8("header.claimz"), sig))
    assert(!Jwt.verifies(key, data, sig.updated(10, (sig(10) ^ 1).toByte)))
  }

/** APNs' token as the service would check it: ES256 by the team's key, verified by the JDK */
class ApnsTokenJvmSuite extends munit.FunSuite:
  import okay.effects.json.Json
  import okay.scala3.std.Async
  import okay.serverless.cloud.*
  given scala.concurrent.ExecutionContext = Async.defaultContext
  test("the provider token verifies by the key's public half") {
    val c = MemoryCloud(() => 1_800_000_000_000L)
    Accounts.actors.foreach((k, a) => c.actors(k) = a)
    c.vars ++= Map("ACCOUNTS_TEST" -> "yes", "APNS_KEY_ID" -> "K", "APNS_TEAM_ID" -> "T", "APNS_SANDBOX" -> "yes")
    c.secrets("APNS_KEY") = TestKeys.ecPrivate
    var jwt = ""
    c.internet = (url, r) => { if url.contains("push.apple.com") then jwt = r.header("authorization").get.drop(7)
      Response.json(Json.Obj.empty) }
    val p = Push.send(Push.Device("apns", "x"), "t", "b")
    c.platform.future(Crypto.run[Push.C, Cloud, Push.Sent](p)).flatMap { _ =>
      val Array(h, cl, sig) = jwt.split('.')
      SignCheck.es256(Crypto.pem(TestKeys.ecPublic), Bytes(okay.serverless.relay.Hashes.utf8(s"$h.$cl")),
        Bytes(okay.serverless.relay.Hashes.unbase64url(sig).get))
    }.map(ok => assert(ok))
  }
