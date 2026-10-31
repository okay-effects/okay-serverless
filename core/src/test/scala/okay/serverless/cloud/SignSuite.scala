package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.*

import scala.concurrent.{ExecutionContext, Future}

/** SIGNING by a private key, the runtime's own: ES256 as JWS writes it (64 bytes), RS256 — checked by the runtime's
 * own verification of the public key (`SignCheck`, the JDK's or Web Crypto's) */
class SignSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  val data = Bytes("header.claims".getBytes("UTF-8"))

  test("ES256: 64 bytes, r and s; the public key verifies it, not another text") {
    for
      sig <- Crypto.native(Crypto.SignEs256(Crypto.pem(TestKeys.ecPrivate), data))
      ok <- SignCheck.es256(Crypto.pem(TestKeys.ecPublic), data, sig)
      other <- SignCheck.es256(Crypto.pem(TestKeys.ecPublic), Bytes("header.claimz".getBytes("UTF-8")), sig)
    yield
      assertEquals(sig.size, 64)
      assert(ok); assert(!other)
  }

  test("RS256: the public key verifies it") {
    for
      sig <- Crypto.native(Crypto.SignRs256(Crypto.pem(TestKeys.rsaPrivate), data))
      ok <- SignCheck.rs256(Crypto.pem(TestKeys.rsaPublic), data, sig)
    yield
      assertEquals(sig.size, 256)
      assert(ok)
  }
