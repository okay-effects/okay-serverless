package okay.serverless.cloud

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}

/** Web Crypto's verification, for the tests */
object SignCheck:
  import ExecutionContext.Implicits.global
  private def check(algorithm: js.Dynamic, sign: js.Dynamic, spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] =
    WebJs.await(g.crypto.subtle.importKey("spki", WebJs.uint8(spki), algorithm, false, js.Array("verify")))
      .flatMap(k => WebJs.await(g.crypto.subtle.verify(sign, k, WebJs.uint8(sig), WebJs.uint8(data))))
      .map(_.asInstanceOf[Boolean])
  def es256(spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] =
    check(obj(name = "ECDSA", namedCurve = "P-256"), obj(name = "ECDSA", hash = "SHA-256"), spki, data, sig)
  def rs256(spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] =
    check(obj(name = "RSASSA-PKCS1-v1_5", hash = "SHA-256"), obj(name = "RSASSA-PKCS1-v1_5"), spki, data, sig)
