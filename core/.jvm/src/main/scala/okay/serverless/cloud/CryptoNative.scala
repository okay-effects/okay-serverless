package okay.serverless.cloud

import java.security.spec.{MGF1ParameterSpec, X509EncodedKeySpec}
import java.security.{KeyFactory, MessageDigest, SecureRandom}
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, IvParameterSpec, OAEPParameterSpec, PSource, SecretKeySpec}
import scala.concurrent.Future
import scala.util.Try

/** `Crypto` ON THE JVM: the JDK's JCA (RSA-OAEP with SHA-256 for both the digest and MGF1, said explicitly: the JDK's
 * "OAEPWithSHA-256AndMGF1Padding" alone takes MGF1 with SHA-1) */
object CryptoNative:
  private val random = SecureRandom()

  def answer[X](op: Crypto[X]): X = op match
    case Crypto.Random(n) =>
      val b = new Array[Byte](n)
      random.nextBytes(b)
      Bytes(b)
    case Crypto.Sha256(data) => Bytes(MessageDigest.getInstance("SHA-256").digest(data.toArray))
    case Crypto.RsaOaep(spki, data) =>
      val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki.toArray))
      val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
      c.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT))
      Bytes(c.doFinal(data.toArray))
    case Crypto.AesCbc(key, iv, data) =>
      val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
      c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toArray, "AES"), IvParameterSpec(iv.toArray))
      Bytes(c.doFinal(data.toArray))
    case Crypto.Seal(key, iv, data) =>
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toArray, "AES"), GCMParameterSpec(128, iv.toArray))
      Bytes(c.doFinal(data.toArray))
    case Crypto.Open(key, iv, boxed) => Try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toArray, "AES"), GCMParameterSpec(128, iv.toArray))
        Bytes(c.doFinal(boxed.toArray))
      }.toOption

  val port: Port[Crypto] = new Port[Crypto]:
    def apply[X](op: Crypto[X]): Future[X] = Future.fromTry(Try(answer(op)))
