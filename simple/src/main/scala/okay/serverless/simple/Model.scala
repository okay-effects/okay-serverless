package okay.serverless.simple

/** a stored value and its version: what a commit checks (0: absent) */
final case class Versioned(value: String, version: Long)

/**
 * WHAT A PLATFORM'S STORAGE DOES, as on okay-scala (`okay.serverless.Storage`): values by key with their versions, keys
 * by prefix, and a commit of a step's writes, all at once, if what it read is as it was. Each operation carries its
 * answer (Scala 2 does not refine a type parameter by a match).
 */
sealed trait Storage[X]
object Storage {
  final case class Read[X](keys: List[String], answer: Map[String, Versioned] => X) extends Storage[X]
  final case class Keys[X](prefix: String, answer: List[String] => X) extends Storage[X]
  /** the writes (`None`: deleted) and the alarm, atomically — if each key read is still at the version read (0:
   * absent); `false`: something read has changed, nothing written */
  final case class Commit[X](read: Map[String, Long], writes: Map[String, Option[String]], alarm: Option[Long],
                             answer: Boolean => X) extends Storage[X]
}

/** THE PROGRAM'S WORDS for durable state, as on okay-scala (`okay.serverless.Durable`) */
sealed trait Durable[X]
object Durable {
  final case class Get[X](key: String, answer: Option[String] => X) extends Durable[X]
  final case class Put[X](key: String, value: String, answer: Unit => X) extends Durable[X]
  final case class Delete[X](key: String, answer: Unit => X) extends Durable[X]
  final case class Keys[X](prefix: String, answer: List[String] => X) extends Durable[X]
  final case class WakeAt[X](ms: Long, answer: Unit => X) extends Durable[X]
  final case class Key[X](name: String, answer: String => X) extends Durable[X]
  final case class Kept[X](key: String, answer: Option[String] => X) extends Durable[X]
  final case class Keep[X](key: String, value: String, answer: Unit => X) extends Durable[X]
}

/** an instance gone mid-step: what the platform would see as the process dying */
final class Crash(where: String) extends RuntimeException(s"crashed $where", null, false, false)
