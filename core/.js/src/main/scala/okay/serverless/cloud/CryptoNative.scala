package okay.serverless.cloud

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}
import scala.scalajs.js.typedarray.Uint8Array

/** `Crypto` IN JAVASCRIPT: the Web Crypto API (`crypto.getRandomValues`, `crypto.subtle`) — a Worker's, Node's
 * (20 and newer), a browser's */
object CryptoNative:
  import WebJs.given

  private def subtle: js.Dynamic = g.crypto.subtle
  private def raw(key: Bytes, name: String, use: String): Future[js.Dynamic] =
    WebJs.await(subtle.importKey("raw", WebJs.uint8(key), obj(name = name), false, js.Array(use)))

  def answer[X](op: Crypto[X]): Future[X] = op match
    case Crypto.Random(n) =>
      val b = new Uint8Array(n)
      g.crypto.getRandomValues(b)
      Future.successful(Bytes(new scala.scalajs.js.typedarray.Int8Array(b.buffer).toArray))
    case Crypto.Sha256(data) => WebJs.await(subtle.digest("SHA-256", WebJs.uint8(data))).map(WebJs.bytes)
    case Crypto.RsaOaep(spki, data) =>
      WebJs.await(subtle.importKey("spki", WebJs.uint8(spki), obj(name = "RSA-OAEP", hash = "SHA-256"), false,
        js.Array("encrypt"))).flatMap(k => WebJs.await(subtle.encrypt(obj(name = "RSA-OAEP"), k, WebJs.uint8(data))))
        .map(WebJs.bytes)
    case Crypto.AesCbc(key, iv, data) => raw(key, "AES-CBC", "encrypt").flatMap(k =>
        WebJs.await(subtle.encrypt(obj(name = "AES-CBC", iv = WebJs.uint8(iv)), k, WebJs.uint8(data))))
        .map(WebJs.bytes)
    case Crypto.Seal(key, iv, data) => raw(key, "AES-GCM", "encrypt").flatMap(k =>
        WebJs.await(subtle.encrypt(obj(name = "AES-GCM", iv = WebJs.uint8(iv)), k, WebJs.uint8(data))))
        .map(WebJs.bytes)
    case Crypto.Open(key, iv, boxed) => raw(key, "AES-GCM", "decrypt").flatMap(k =>
        WebJs.await(subtle.decrypt(obj(name = "AES-GCM", iv = WebJs.uint8(iv)), k, WebJs.uint8(boxed))))
        .map(b => Option(WebJs.bytes(b))).recover { case _ => None }
    case Crypto.SignEs256(pkcs8, data) =>
      WebJs.await(subtle.importKey("pkcs8", WebJs.uint8(pkcs8), obj(name = "ECDSA", namedCurve = "P-256"), false,
        js.Array("sign"))).flatMap(k => WebJs.await(subtle.sign(obj(name = "ECDSA", hash = "SHA-256"), k,
        WebJs.uint8(data)))).map(WebJs.bytes)
    case Crypto.SignRs256(pkcs8, data) =>
      WebJs.await(subtle.importKey("pkcs8", WebJs.uint8(pkcs8), obj(name = "RSASSA-PKCS1-v1_5", hash = "SHA-256"),
        false, js.Array("sign"))).flatMap(k => WebJs.await(subtle.sign(obj(name = "RSASSA-PKCS1-v1_5"), k,
        WebJs.uint8(data)))).map(WebJs.bytes)

  val port: Port[Crypto] = new Port[Crypto]:
    def apply[X](op: Crypto[X]): Future[X] = answer(op)
