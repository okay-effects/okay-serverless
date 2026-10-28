package okay.serverless.relay

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** A FRAME SEALED AND OPENED on the JVM (the JCA): AES-256-GCM by the pairing's key, its label the associated data */
final class Sealer(pairing: Pairing):
  private val random = new SecureRandom
  private val key = new SecretKeySpec(pairing.enc, "AES")
  private val aad = Hashes.utf8(pairing.aad)
  def seal(plain: String): String =
    val iv = new Array[Byte](12); random.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
    c.updateAAD(aad)
    Hashes.base64url(iv ++ c.doFinal(Hashes.utf8(plain)))
  def open(frame: String): Option[String] = Hashes.unbase64url(frame).filter(_.length > 28).flatMap { b =>
    scala.util.Try {
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, b.take(12)))
      c.updateAAD(aad)
      new String(c.doFinal(b.drop(12)), java.nio.charset.StandardCharsets.UTF_8)
    }.toOption
  }
  def nonce: String = { val b = new Array[Byte](12); random.nextBytes(b); Hashes.hex(b) }

object Sealer:
  def fresh(label: String): Pairing =
    val b = new Array[Byte](32); new SecureRandom().nextBytes(b); Pairing(b, label)
