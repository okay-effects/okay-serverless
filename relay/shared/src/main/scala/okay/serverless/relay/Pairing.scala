package okay.serverless.relay

/** A PAIRING (specs/relay.md): a secret the host made — and what both ends derive from it, under the app's label:
 * the AES key, the room's auth, the room. The room is told the auth; only the two ends ever have the key */
final class Pairing private (val secret: Array[Byte], val label: String):
  val enc: Array[Byte] = Hashes.sha256(Hashes.utf8(s"$label/enc:") ++ secret)
  val auth: String = Hashes.hex(Hashes.sha256(Hashes.utf8(s"$label/auth:") ++ secret))
  val room: String = Pairing.roomOf(auth)
  val aad: String = s"$label/1"
  /** the secret as text: what a pairing link carries in its fragment */
  def text: String = Hashes.base64url(secret)

object Pairing:
  def apply(secret: Array[Byte], label: String): Pairing = new Pairing(secret, label)
  def of(text: String, label: String): Option[Pairing] =
    Hashes.unbase64url(text).filter(_.length == 32).map(Pairing(_, label))
  /** a room is the SHA-256 of its auth: the name a room checks a dialler's auth against */
  def roomOf(auth: String): String = Hashes.hex(Hashes.sha256(Hashes.utf8(auth)))
