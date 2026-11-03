package okay.serverless.relay

import okay.effects.json.{Descent, Json}
import scala.collection.mutable

/** WHAT A SEALED FRAME SAYS, once opened: who sent it, when, a nonce, its kind and its fields. A frame from the wrong
 * side, out of the window, or seen before, is not taken — no reflection, no replay */
object Envelope:
  val windowMs: Long = 5 * 60 * 1000

  def wrap(from: String, now: Long, nonce: String, kind: String, fields: (String, Json)*): String =
    Json.print(Json.Obj(Vector("v" -> Json.Num(BigDecimal(1)), "from" -> Json.Str(from),
      "t" -> Json.Num(BigDecimal(now)), "n" -> Json.Str(nonce), "kind" -> Json.Str(kind)) ++ fields))

  /** the nonces seen within the window: one per receiving end */
  final class Seen:
    private val at = mutable.LinkedHashMap.empty[String, Long]
    def fresh(n: String, now: Long): Boolean =
      at.filterInPlace((_, t) => now - t <= 2 * windowMs)
      if n.length < 8 || at.contains(n) then false else { at(n) = now; true }

  private def field(j: Json, k: String): Option[Json] = j match
    case o: Json.Obj => o.get(k)
    case _ => None
  private def str(j: Json, k: String) = field(j, k).collect { case Json.Str(s) => s }

  /** a frame's text, opened: its kind and the whole JSON — if it is from `from`, in time, and new */
  def open(text: String, from: String, now: Long, seen: Seen): Either[String, (String, Json)] =
    Descent.parse(text).left.map(_ => "not JSON").flatMap { j =>
      val t = field(j, "t").collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
      if !str(j, "from").contains(from) then Left("from the wrong side")
      else if math.abs(now - t) > windowMs then Left("out of time")
      else if !seen.fresh(str(j, "n").getOrElse(""), now) then Left("seen before")
      else str(j, "kind").toRight("no kind").map(_ -> j)
    }
