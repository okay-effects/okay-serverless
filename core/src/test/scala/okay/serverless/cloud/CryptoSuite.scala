package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.Async

import java.util.Base64
import scala.concurrent.Future

/** `Crypto` by the runtime's own (the JDK's on the JVM, the Web Crypto API under Node): known answers */
class CryptoSuite extends munit.FunSuite:
  given scala.concurrent.ExecutionContext = Async.defaultContext
  private def run[A](p: A ! (Async + Crypto)): Future[A] = Async.toFuture(Crypto.run[Async + Crypto, Async, A](p))
  private def hex(b: Bytes) = b.toArray.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String) = Bytes(s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  private def b64(b: Bytes) = Base64.getEncoder.encodeToString(b.toArray)

  /** the Ministry of Finance's KSeF test certificate for tokens (api-test.ksef.mf.gov.pl, valid to 2027-09-29) */
  private val certificate = Bytes(Base64.getDecoder.decode(
    "MIIGWDCCBECgAwIBAgIQGmXqNRg5ye1JMZDOQ7HNCTANBgkqhkiG9w0BAQsFADBOMQswCQYDVQQGEwJQTDEhMB8GA1UECgwYQXNz" +
    "ZWNvIERhdGEgU3lzdGVtcyBTLkEuMRwwGgYDVQQDDBNDZXJ0dW0gU01JTUUgUlNBIENBMB4XDTI1MDkyOTA2MDMxOVoXDTI3MDky" +
    "OTA2MDMxOFowgb4xGTAXBgNVBGETEFZBVFBMLTUyNjAyNTAyNzQxCzAJBgNVBAYTAlBMMRQwEgYDVQQIDAttYXpvd2llY2tpZTER" +
    "MA8GA1UEBwwIV2Fyc3phd2ExHzAdBgNVBAoMFk1pbmlzdGVyc3R3byBGaW5hbnPDs3cxHzAdBgNVBAMMFk1pbmlzdGVyc3R3byBG" +
    "aW5hbnPDs3cxKTAnBgkqhkiG9w0BCQEWGmtvbnN1bHRhY2plLmtzZWZAbWYuZ292LnBsMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A" +
    "MIIBCgKCAQEAxsyeiYiWB2+KFxEpQGoNQa6W8Pc4kWGl8V+sBMdW3Fqh0lhKiqKfpH5RWLDmZ30EzkKJ5+IdaWYFoijhYxDBIBhI" +
    "NVQKlBZvEVd6CfPJUJypa94eRO5cc6IPNI35aMhfKP/Kc4A/OiT2J4nyCz6BV98xOXCAlyDPD73XM6O2ormL6gUb673zvjOIakf3" +
    "9tAPPVgWIDuX7GDZYGebN7LXoGvjPo5YDqC2KN51ofLbO+n74iei5OaGN94Ap52vI7uzK2g/hQslOd0Avl2U1kwRnnF0yzwbDzRr" +
    "HqPCHUYxVp5nHdo+jHe1CNoa6gt0m6pn1StYcitSXKg2hTNjnes6TQIDAQABo4IBvzCCAbswDAYDVR0TAQH/BAIwADBBBgNVHR8E" +
    "OjA4MDagNKAyhjBodHRwOi8vY3NtaW1lcnNhY2EuY3JsLmNlcnR1bS5wbC9jc21pbWVyc2FjYS5jcmwwgYMGCCsGAQUFBwEBBHcw" +
    "dTAuBggrBgEFBQcwAYYiaHR0cDovL2NzbWltZXJzYWNhLm9jc3AtY2VydHVtLmNvbTBDBggrBgEFBQcwAoY3aHR0cDovL2NzbWlt" +
    "ZXJzYWNhLnJlcG9zaXRvcnkuY2VydHVtLnBsL2NzbWltZXJzYWNhLmNlcjAfBgNVHSMEGDAWgBRm+8MPvvS/4JzJq03eRxm9wMqm" +
    "aDAdBgNVHQ4EFgQUyDshAguoI0vLLGyA3aRgapjC4JEwTAYDVR0gBEUwQzAJBgdngQwBBQICMDYGCyqEaAGG9ncCZAMBMCcwJQYI" +
    "KwYBBQUHAgEWGWh0dHBzOi8vd3d3LmNlcnR1bS5wbC9DUFMwHQYDVR0lBBYwFAYIKwYBBQUHAwQGCCsGAQUFBwMCMA4GA1UdDwEB" +
    "/wQEAwIE8DAlBgNVHREEHjAcgRprb25zdWx0YWNqZS5rc2VmQG1mLmdvdi5wbDANBgkqhkiG9w0BAQsFAAOCAgEAxX7ltOEd6+Rb" +
    "ztKIgfmpfxsgmg3TXdmwQucy+tw6aqBNF7Xk22PhVcWVgHKLq6xkaCTCfHbfpl6iGWsWkM5re2FltEF8QuLJbI7n6sC/T/pG+aIj" +
    "4TWgaiKO79dST4kda9GxMEuKxZDkC7OXg4optdxB8Kg3ctFPqzLdnH71lL8I+Wo+KIwGe2h0tDMo39+UQC2XOd5l//1abiuO8ZMa" +
    "l+NEbz8WBeS4saH3qPcYmB8+4hV16kU4csNcyrR6PBKO7vkUXI0Lqh0ioEyFJyhxmx3ZPN4VUQFyQZ8l+GmbRFWBCHIhB5dfWmGa" +
    "zE1gWQmVfpYsmuot7sSI2Uw1pBLPsniA9sBQfIB1tPGmdfTb/Cpkj1k/owYN6G+08dTqG7v8O7R+skSgcem4O9Ftr+8RDTmhLrPw" +
    "px9RXf881bmm47aw1BTPzzqDsFGmcNF2Hjb8OpJoJ3ZQQ98ep+yJUb0Ub9trfQNRVKfWltrTDl4Uc+vlWYegocIhJdZ5ZwpeJIZM" +
    "UHIUXarsuC/hyHZb5nDZSp/mf8a1Qxw9SeYb5TG6AAacGfnVPK2YXA6mu4thhszUBxc6/WKEovN8LfYhZzppf9bgp1Gs7TJIzPUJ" +
    "bsAD2tZg2VssAsuJ17u10vTBEZnYwRZXsfMnMJJMC1wsrA5Xmp3C+oN84Z8l7viL5l1VxbU="))

  test("the public key of a certificate: its SubjectPublicKeyInfo, whose SHA-256 the publisher gives as its id") {
    val spki = Crypto.spki(certificate).get
    run(Crypto.sha256[Async + Crypto](spki)).map(h => assertEquals(b64(h),
      "IPbPM4CB49vtoR/x/3fEI+Y+Q6lK/bVVehQ7/NlPJoo="))
  }

  test("not a certificate: none") {
    assertEquals(Crypto.spki(Bytes.of("nothing")), None)
    assertEquals(Crypto.spki(Bytes.empty), None)
  }

  test("SHA-256 of abc") {
    run(Crypto.sha256[Async + Crypto](Bytes.of("abc"))).map(h => assertEquals(hex(h),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
  }

  test("AES-256-CBC: NIST SP 800-38A F.2.5, its first block; PKCS#7 adds one") {
    val key = unhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
    val iv = unhex("000102030405060708090a0b0c0d0e0f")
    run(Crypto.aesCbc[Async + Crypto](key, iv, unhex("6bc1bee22e409f96e93d7e117393172a"))).map { c =>
      assertEquals(c.size, 32)
      assertEquals(hex(c.slice(0, 16)), "f58c4c04d6e5f1ba779eabfb5f7bfbd6")
    }
  }

  test("AES-GCM sealed and opened; another key or a changed byte opens nothing") {
    val p = for
      key <- Crypto.random[Async + Crypto](32)
      other <- Crypto.random[Async + Crypto](32)
      iv <- Crypto.random[Async + Crypto](12)
      boxed <- Crypto.seal[Async + Crypto](key, iv, Bytes.of("a token"))
      opened <- Crypto.open[Async + Crypto](key, iv, boxed)
      wrong <- Crypto.open[Async + Crypto](other, iv, boxed)
      changed = Bytes(boxed.toArray.updated(0, (boxed.toArray(0) ^ 1).toByte))
      tampered <- Crypto.open[Async + Crypto](key, iv, changed)
    yield (key.size, boxed.size, opened.map(_.text), wrong, tampered)
    run(p).map(r => assertEquals(r, (32, 7 + 16, Some("a token"), None, None)))
  }

  test("RSA-OAEP to the certificate's key: 256 bytes, never the same twice") {
    val spki = Crypto.spki(certificate).get
    run(Crypto.rsaOaep[Async + Crypto](spki, Bytes.of("token|1")).flatMap(a =>
      Crypto.rsaOaep[Async + Crypto](spki, Bytes.of("token|1")).map(b => (a, b)))).map((a, b) =>
      assertEquals((a.size, b.size, a == b), (256, 256, false)))
  }
