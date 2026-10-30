package okay.serverless.cloud

import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.{OAEPParameterSpec, PSource}

/** RSA-OAEP decrypted by a private key: SHA-256 for the digest AND for MGF1, as the Web Crypto API does */
class CryptoJvmSuite extends munit.FunSuite:
  test("RSA-OAEP SHA-256 with MGF1 SHA-256: the private key's owner reads it") {
    val pair = KeyPairGenerator.getInstance("RSA").tap(_.initialize(2048)).generateKeyPair()
    val boxed = CryptoNative.answer(Crypto.RsaOaep(Bytes(pair.getPublic.getEncoded), Bytes.of("token|1793344654786")))
    val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
    c.init(Cipher.DECRYPT_MODE, pair.getPrivate, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
      PSource.PSpecified.DEFAULT))
    assertEquals(new String(c.doFinal(boxed.toArray), "UTF-8"), "token|1793344654786")
  }

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
