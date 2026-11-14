package okay.serverless.relay

import okay.effects.json.{Descent, Json}

/** WHAT A SEALED FRAME SAYS, once opened: who sent it, when, a nonce, its kind and its fields. A frame from the wrong
 * side, out of the window, or seen before, is not taken — no reflection, no replay */
object Envelope:
  val windowMs: Long = 5 * 60 * 1000

  def wrap(from: String, now: Long, nonce: String, kind: String, fields: (String, Json)*): String =
    Json.print(Json.Obj(Vector("v" -> Json.Num(BigDecimal(1)), "from" -> Json.Str(from),
      "t" -> Json.Num(BigDecimal(now)), "n" -> Json.Str(nonce), "kind" -> Json.Str(kind)) ++ fields))

  /** the nonces seen within the window, a value: a receiving end keeps the last one (`check`) */
  final case class Nonces(at: Map[String, Long] = Map.empty):
    /** with `n` seen at `now`, those out of the window dropped — none when `n` is too short or was seen */
    def fresh(n: String, now: Long): Option[Nonces] =
      val kept = at.filter((_, t) => now - t <= 2 * windowMs)
      Option.when(n.length >= 8 && !kept.contains(n))(Nonces(kept.updated(n, now)))

  /** the nonces seen within the window, kept for one receiving end (`open`) */
  final class Seen:
    private var nonces = Nonces()
    def fresh(n: String, now: Long): Boolean = nonces.fresh(n, now) match
      case Some(next) => nonces = next; true
      case None => false

  private def field(j: Json, k: String): Option[Json] = j match
    case o: Json.Obj => o.get(k)
    case _ => None
  private def str(j: Json, k: String) = field(j, k).collect { case Json.Str(s) => s }

  /** a frame's text, opened: its kind and the whole JSON — if it is from `from`, in time, and new */
  def open(text: String, from: String, now: Long, seen: Seen): Either[String, (String, Json)] =
    framed(text, from, now).flatMap(j =>
      if !seen.fresh(str(j, "n").getOrElse(""), now) then Left("seen before")
      else str(j, "kind").toRight("no kind").map(_ -> j))

  /** the same with the nonces as a value: the kind, the JSON and the nonces after (the receiving end keeps them) */
  def check(text: String, from: String, now: Long, nonces: Nonces): Either[String, ((String, Json), Nonces)] =
    framed(text, from, now).flatMap(j => nonces.fresh(str(j, "n").getOrElse(""), now).toRight("seen before")
      .flatMap(next => str(j, "kind").toRight("no kind").map(k => ((k, j), next))))

  /** the frame's JSON, if it is from `from` and in time */
  private def framed(text: String, from: String, now: Long): Either[String, Json] =
    Descent.parse(text).left.map(_ => "not JSON").flatMap { j =>
      val t = field(j, "t").collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
      if !str(j, "from").contains(from) then Left("from the wrong side")
      else if math.abs(now - t) > windowMs then Left("out of time")
      else Right(j)
    }
