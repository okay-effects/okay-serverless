package okay.serverless.cloudflare

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Durable, Json, Storage}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE EXAMPLE: an account, a durable process on a Durable Object — its state a balance and the payments it took;
 * an event a deposit, a payment (refused over the balance), or the alarm's interest. Restarted, evicted, the next
 * event finds the last committed state: nothing replayed.
 */
object Example:
  final case class Account(balance: Long, payments: List[String])

  given okay.serverless.Codec[Account] = okay.serverless.Codec.json(
    a => Json.Obj(List("balance" -> Json.Num(BigDecimal(a.balance)),
      "payments" -> Json.Arr(a.payments.map(Json.Str(_))))),
    j => (j.get("balance"), j.get("payments")) match
      case (Some(Json.Num(b)), Some(Json.Arr(ps))) => Right(Account(b.toLong, ps.collect { case Json.Str(p) => p }))
      case _ => Left(s"not an account: $j"))

  type R[X] = (Storage + Fail % String + Durable)[X]

  def account(event: Json): Account ! (Storage + Fail % String) =
    Durable.machine[Account, Json, R, Storage + Fail % String]("account", Account(0, Nil)) { (a, e) =>
      (e.get("op"), e.get("amount")) match
        case (Some(Json.Str("deposit")), Some(Json.Num(n))) => pure(a.copy(balance = a.balance + n.toLong))
        case (Some(Json.Str("pay")), Some(Json.Num(n))) =>
          if n.toLong > a.balance then Fail.raise[String, R](s"${n.toLong} is more than the balance ${a.balance}")
          else Durable.once[String, R]("payment")(key => pure(s"pay-$key")).map(id =>
            a.copy(balance = a.balance - n.toLong, payments = a.payments :+ id))
        case (Some(Json.Str("interest-in")), Some(Json.Num(ms))) =>
          Durable.wakeAt[R](js.Date.now().toLong + ms.toLong).as(a)
        case (Some(Json.Str("interest")), _) => pure(a.copy(balance = a.balance + a.balance / 100))
        case _ => Fail.raise[String, R](s"an event not known: ${e.render}")
    }(event)

  private def answer(r: Either[String, Account]): String = r match
    case Right(a) => Json.Obj(List("balance" -> Json.Num(BigDecimal(a.balance)),
      "payments" -> Json.Arr(a.payments.map(Json.Str(_))))).render
    case Left(why) => Json.Obj(List("refused" -> Json.Str(why))).render

  /** an event to the object: its answer, the account after it or why it was refused */
  @JSExportTopLevel("accountEvent")
  def event(ctx: js.Dynamic, body: String): String =
    Json.parse(body) match
      case Left(why) => answer(Left(s"not JSON: $why"))
      case Right(e) => answer(run(ObjectStorage(ctx).handler(Fail.run(account(e)))))

  /** the object's alarm: the interest, as an event */
  @JSExportTopLevel("accountAlarm")
  def alarm(ctx: js.Dynamic): String = event(ctx, """{"op":"interest"}""")
