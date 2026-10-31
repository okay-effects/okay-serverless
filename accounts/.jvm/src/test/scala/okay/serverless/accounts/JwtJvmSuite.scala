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
