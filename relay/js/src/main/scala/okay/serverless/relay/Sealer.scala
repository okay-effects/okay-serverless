package okay.serverless.relay

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.typedarray.{ArrayBuffer, Int8Array, Uint8Array}

/** A FRAME SEALED AND OPENED in a browser (WebCrypto): AES-256-GCM by the pairing's key, its label the associated
 * data — the key imported once, not extractable */
final class Sealer(pairing: Pairing)(using ExecutionContext):
  private val subtle = g.crypto.subtle
  private def bytes(b: Array[Byte]): Uint8Array = new Uint8Array(new Int8Array(js.Array(b.toSeq*)).buffer)
  private val key: Future[js.Dynamic] = subtle.importKey("raw", bytes(pairing.enc), "AES-GCM", false,
    js.Array("encrypt", "decrypt")).asInstanceOf[js.Promise[js.Dynamic]].toFuture
  private def params(iv: Uint8Array) =
    js.Dynamic.literal(name = "AES-GCM", iv = iv, additionalData = bytes(Hashes.utf8(pairing.aad)))
  private def random(n: Int): Array[Byte] =
    val b = new Uint8Array(n); g.crypto.getRandomValues(b); b.toArray.map(_.toByte)

  def seal(plain: String): Future[String] =
    val iv = random(12)
    key.flatMap(k => subtle.encrypt(params(bytes(iv)), k, bytes(Hashes.utf8(plain)))
      .asInstanceOf[js.Promise[ArrayBuffer]].toFuture)
      .map(box => Hashes.base64url(iv ++ new Int8Array(box).toArray))
  def open(frame: String): Future[Option[String]] = Hashes.unbase64url(frame).filter(_.length > 28) match
    case None => Future.successful(None)
    case Some(b) => key.flatMap(k => subtle.decrypt(params(bytes(b.take(12))), k, bytes(b.drop(12)))
        .asInstanceOf[js.Promise[ArrayBuffer]].toFuture)
        .map(plain => Some(new String(new Int8Array(plain).toArray, java.nio.charset.StandardCharsets.UTF_8)))
        .recover { case _ => None }
  def nonce: String = Hashes.hex(random(12))
