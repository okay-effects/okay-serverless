package okay.serverless.cloudflare

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.{Codec, Durable, field}

import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE EXAMPLE WORKER: each of a cloud's effects used once, through its binding (wrangler.toml) — and three object
 * classes: an account (a durable machine), a chat room (WebSockets), an order (a workflow). Nothing here names
 * Cloudflare but the last line.
 */
object Example:
  final case class Account(balance: Long, payments: List[String])

  given Codec[Account] = Codec.json(
    a => Json.obj("balance" -> Json.num(a.balance), "payments" -> Json.Arr(a.payments.toVector.map(Json.Str(_)))),
    j => (j.field("balance"), j.field("payments")) match
      case (Some(Json.Num(b)), Some(Json.Arr(ps))) => Right(Account(b.toLong, ps.collect { case Json.Str(p) => p }
          .toList))
      case _ => Left(s"not an account: $j"))

  type A[X] = (InActor + Fail % String)[X]
  type AD[X] = (A + Durable)[X]

  /** AN ACCOUNT: a durable process — a deposit, a payment (refused over the balance), the alarm's interest */
  object Accounts extends Actor:
    def event(name: String, e: Json): Account ! A =
      Durable.machine[Account, Json, AD, A]("account", Account(0, Nil)) { (a, e) =>
        (e.field("op"), e.field("amount")) match
          case (Some(Json.Str("deposit")), Some(Json.Num(n))) => pure(a.copy(balance = a.balance + n.toLong))
          case (Some(Json.Str("pay")), Some(Json.Num(n))) =>
            if n.toLong > a.balance then Fail.raise[String, AD](s"${n.toLong} is more than the balance ${a.balance}")
            else Durable.once[String, AD]("payment")(key => pure(s"pay-$key")).map(id =>
              a.copy(balance = a.balance - n.toLong, payments = a.payments :+ id))
          case (Some(Json.Str("interest-in")), Some(Json.Num(ms))) =>
            Clock.now[AD].flatMap(t => Durable.wakeAt[AD](t + ms.toLong)).as(a)
          case (Some(Json.Str("interest")), _) => pure(a.copy(balance = a.balance + a.balance / 100))
          case _ => Fail.raise[String, AD](s"an event not known: ${Json.print(e)}")
      }(e)

    private def answer(r: Either[String, Account]): Response = r match
      case Right(a) => Response.json(Json.obj("balance" -> Json.num(a.balance),
        "payments" -> Json.Arr(a.payments.toVector.map(Json.Str(_)))))
      case Left(why) => Response.json(Json.obj("refused" -> Json.Str(why)))

    def fetch(name: String, request: Request): Response ! InActor =
      Descent.parse(request.text) match
        case Left(e) => pure(answer(Left(s"not JSON: ${e.message}")))
        case Right(e) => Fail.run[String, A, InActor, Account](event(name, e)).map(answer)

    override def alarm(name: String): Unit ! InActor =
      Fail.run[String, A, InActor, Account](event(name, Json.obj("op" -> Json.Str("interest")))).as(())

  /** A CHAT ROOM: sockets by member; a line broadcast, the history durable */
  object Rooms extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      if request.header("upgrade").exists(_.equalsIgnoreCase("websocket")) then
        Sockets.accept[InActor](request, request.param("who").getOrElse("guest"))
      else Durable.step[ActorStep, InActor, List[String]](name)(Durable.get[List[String], ActorStep]("history")
        .map(_.getOrElse(Nil))).map(h => Response.text(h.mkString("\n")))
    override def message(name: String, socket: Socket, text: String): Unit ! InActor =
      val line = s"${socket.tags.headOption.getOrElse("?")}: $text"
      Durable.step[ActorStep, InActor, Unit](name)(Durable.get[List[String], ActorStep]("history")
        .flatMap(h => Durable.put[List[String], ActorStep]("history", h.getOrElse(Nil) :+ line)))
        .andThen(Sockets.broadcast[InActor](line)).as(())

  /** AN ORDER: a workflow — charged, a pause, approved, a mail */
  val Orders: Workflow[Json] = Workflow[Json](identity)(
    Stage.Do("charge", o => Kv.put[InActor + Fail % String]("KV",
      s"charged:${o.field("id").fold("?")(j => Json.print(j))}", "yes").as(o)),
    Stage.Sleep("pause", 1000),
    Stage.Wait("approval", "approved", (o, j) => o match
      case Json.Obj(fs) => Json.Obj(fs :+ ("approvedBy" -> j.field("by").getOrElse(Json.Null)))
      case other => other),
    Stage.Do("mail", o => Mails.send[InActor + Fail % String]("MAIL", Mail("shop@okay.example", "ann@okay.example",
      "approved", Json.print(o))).as(o)))

  /** THE APP: a route an effect */
  object Shop extends App:
    override def objectFor(r: Request): Option[(String, String)] = r.path.split('/').toList match
      case List("", "room", name) => Some(("ROOMS", name))
      case List("", "order", id, _*) => Some(("ORDERS", id))
      case _ => None

    def q(r: Request, k: String): String = r.param(k).getOrElse("")

    override def fetch(r: Request): Response ! Cloud = r.path match
      case "/" => Objects.call[Cloud]("ACCOUNTS", r.param("account").getOrElse("main"), r)
      case "/kv" =>
        if r.method == "POST" then Kv.put[Cloud]("KV", q(r, "key"), r.text).as(Response.text("ok"))
        else Kv.get[Cloud]("KV", q(r, "key")).map(v => Response.text(v.getOrElse("(none)")))
      case "/kv/list" => Kv.list[Cloud]("KV", q(r, "prefix")).map(p => Response.text(p.items.mkString(",")))
      case "/blob" =>
        if r.method == "PUT" then Blobs.put[Cloud]("BUCKET", q(r, "key"), r.content, r.header("content-type"))
          .map(i => Response.text(s"${i.size}"))
        else
          val range = r.param("range").map(_.split('-')).collect { case Array(a, b) => (a.toLong, b.toLong) }
          Blobs.get[Cloud]("BUCKET", q(r, "key"), range).map {
            case Some((info, b)) => Response.binary(b, info.contentType.getOrElse("application/octet-stream"))
            case None => Response.notFound()
          }
      case "/sql" =>
        def text(v: SqlValue) = v match
          case SqlValue.Text(t) => t
          case other => other.toString
        Sql.run[Cloud]("DB", "create table if not exists notes (id integer primary key, text text not null)")
          .andThen(Sql.run[Cloud]("DB", "insert into notes (text) values (?)", r.text)).flatMap(c =>
            Sql.query[Cloud]("DB", "select id, text from notes order by id")
              .map(rows => Response.text(s"${c.changes} " + rows.map(row => text(row("text"))).mkString(","))))
      case "/queue" => Queues.send[Cloud]("JOBS", q(r, "body")).as(Response.text("queued"))
      case "/cached" => Cache.through[Cloud]("https://okay.example/cached", 60)(
          Clock.now[Cloud].map(t => Response.text(s"made at $t")))
      case "/limit" => Limits.take[Cloud]("LIMITER", q(r, "key")).map(ok => Response.text(ok.toString))
      case "/env" => Env.variable[Cloud]("GREETING").flatMap(g => Env.secret[Cloud]("API_KEY")
          .map(k => Response.text(s"${g.getOrElse("?")} key:${k.isDefined}")))
      case "/analytics" => Analytics.write[Cloud]("VIEWS", DataPoint(List("ann"), List(r.path), List(1)))
          .as(Response.text("written"))
      case "/service" => Services.call[Cloud]("SELF", Request.get("/hello"))
          .map(s => Response.text(s"via service: ${s.text}"))
      case "/hello" => pure(Response.text("hello"))
      case "/mail" => Mails.send[Cloud]("MAIL", Mail("shop@okay.example", "ann@okay.example", "hi", "hello"))
          .as(Response.text("sent"))
      case _ => pure(Response.notFound())

    override def queue(queue: String, m: Message): Settle ! Cloud =
      Kv.put[Cloud]("KV", s"job:${m.body}", s"done after ${m.attempts}").andThen(Log.info[Cloud](s"job ${m.body}"))
        .as(Settle.Ack)

    override def scheduled(e: Scheduled): Unit ! Cloud = Kv.put[Cloud]("KV", "cron", e.cron)

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Shop, Map("ACCOUNTS" -> Accounts, "ROOMS" -> Rooms, "ORDERS" -> Orders))
