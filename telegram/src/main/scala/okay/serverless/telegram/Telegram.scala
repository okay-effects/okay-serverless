package okay.serverless.telegram

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.cloud.*

/** A TELEGRAM BOT AS AN EFFECT (specs/relay.md): a message to a chat, with a button that opens a page in Telegram (a
 * Mini App, https only); `false` when the chat cannot be told (it blocked the bot) */
enum Telegram[X]:
  case Send(chat: String, text: String, open: Option[String]) extends Telegram[Boolean]

object Telegram:
  def send[R[_]](chat: String, text: String, open: Option[String] = None)(using In[Telegram, R]): Boolean ! R =
    perform[Telegram](Send(chat, text, open))

  /** a webhook's update: the chat, the text, whether it is a private chat */
  final case class Update(chat: String, text: String, privately: Boolean)

  /** what came to the webhook: refused (403) without Telegram's secret header; an update, or nothing to act on */
  def update(request: Request, secret: String): Either[Response, Option[Update]] =
    val header = request.header("x-telegram-bot-api-secret-token").getOrElse("")
    if secret.isEmpty || !okay.serverless.relay.Hashes.same(header, secret) then
      Left(Response.text("not Telegram", 403))
    else Right(Descent.parse(request.text).toOption.flatMap { u =>
      def at(j: Json, path: String*): Option[Json] = path.foldLeft(Option(j))((o, k) => o.flatMap {
        case x: Json.Obj => x.get(k)
        case _ => None
      })
      for
        chat <- at(u, "message", "chat", "id").map {
          case Json.Num(n) => n.toBigInt.toString
          case Json.Str(s) => s
          case other => Json.print(other)
        }
        text <- at(u, "message", "text").collect { case Json.Str(t) => t }
      yield Update(chat, text, at(u, "message", "chat", "type").contains(Json.Str("private")))
    })

  /** the message's JSON for the Bot API's `sendMessage` */
  def message(chat: String, text: String, open: Option[String]): String =
    val button = open.filter(_.startsWith("https://")).toVector.map(u => Json.Arr(Vector(Json.obj(
      "text" -> Json.Str("Open"), "web_app" -> Json.obj("url" -> Json.Str(u))))))
    Json.print(Json.obj("chat_id" -> Json.Str(chat), "text" -> Json.Str(text),
      "reply_markup" -> Json.obj("inline_keyboard" -> Json.Arr(button))))

  /** BY THE BOT API: each message a `sendMessage`, over `Fetch`; a chat that blocked the bot (403) is `false` */
  def viaBotApi[R[_], Rest[_], A](token: String, api: String = "https://api.telegram.org")(p: A ! R)(
    using Split[Telegram, R, Rest], In[Fetch, Rest]): A ! Rest =
    handle(p, Handler.general[Telegram, Rest, A, A](pure)([X] => (op: Telegram[X], k: X => A ! Rest) => op match
      case Send(chat, text, open) =>
        val request = okay.http.Fetch.request("POST", s"${api.stripSuffix("/")}/bot$token/sendMessage",
          Map("content-type" -> "application/json"), message(chat, text, open))
        Fetch[Rest](okay.http.Fetch.base(api), request).flatMap(r => k(r.status != 403))))

  /** IN MEMORY, for tests: each message kept; the chats in `blocked` cannot be told */
  def inMemory[R[_], Rest[_], A](sent: scala.collection.mutable.Buffer[(String, String, Option[String])],
                                 blocked: Set[String] = Set.empty)(p: A ! R)(using Split[Telegram, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Telegram, Rest, A, A](pure)([X] => (op: Telegram[X]) => op match
      case Send(chat, text, open) =>
        if blocked(chat) then false else { sent += ((chat, text, open)); true }))
