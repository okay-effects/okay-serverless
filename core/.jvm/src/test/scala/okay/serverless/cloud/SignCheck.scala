package okay.serverless.cloud

import java.security.{KeyFactory, Signature}
import java.security.spec.X509EncodedKeySpec
import scala.concurrent.Future

/** the JDK's verification, for the tests */
object SignCheck:
  private def check(alg: String, kind: String, spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] =
    Future.successful {
      val s = Signature.getInstance(alg)
      s.initVerify(KeyFactory.getInstance(kind).generatePublic(X509EncodedKeySpec(spki.toArray)))
      s.update(data.toArray)
      s.verify(sig.toArray)
    }
  def es256(spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] =
    check("SHA256withECDSAinP1363Format", "EC", spki, data, sig)
  def rs256(spki: Bytes, data: Bytes, sig: Bytes): Future[Boolean] = check("SHA256withRSA", "RSA", spki, data, sig)
