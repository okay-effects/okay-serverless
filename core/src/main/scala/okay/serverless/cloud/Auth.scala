package okay.serverless.cloud

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Env

/**
 * WHO CALLS: a request's bearer token (`Authorization: Bearer …`) taken to its caller by `who` — a secret of the
 * environment, tokens kept in KV, an identity provider's check — before anything else (`App.admit`): without a token,
 * or with one `who` does not know, a 401 and nothing more is done; known, the request goes on with its caller in
 * `okay-caller` (and one sent from outside under that name always dropped). The paths `open` say are let in as they
 * are.
 */
object Auth:
  val callerHeader = "okay-caller"

  /** the caller a request was admitted with */
  def caller(request: Request): Option[String] = request.header(callerHeader)

  /** the bearer token of a request */
  def token(request: Request): Option[String] =
    request.header("authorization").map(_.trim).collect {
      case h if h.regionMatches(true, 0, "Bearer ", 0, 7) => h.substring(7).trim
    }.filter(_.nonEmpty)

  /** two texts equal, in a time that does not tell where they differ */
  def same(a: String, b: String): Boolean =
    val (x, y) = (a.getBytes("UTF-8"), b.getBytes("UTF-8"))
    var diff = x.length ^ y.length
    var i = 0
    while i < math.max(x.length, y.length) do
      diff |= (if i < x.length then x(i) else 0) ^ (if i < y.length then y(i) else 0)
      i += 1
    diff == 0

  /** one token, the environment's secret `name`: its caller `as` */
  def secret(name: String, as: String = "owner")(token: String): Option[String] ! Cloud =
    Env.secret[Cloud](name).map(_.filter(s => s.nonEmpty && same(s, token)).map(_ => as))

  /** tokens kept in KV under `token/<token>`, each its caller's name */
  def kv(ns: String)(token: String): Option[String] ! Cloud = Kv.get[Cloud](ns, s"token/$token")

  private def refused(why: String): Response =
    Response(401, Map("WWW-Authenticate" -> "Bearer", "Content-Type" -> "application/json"),
      Json.print(Json.obj("error" -> Json.Str(why))))

  /** `app` behind tokens: admitted by `who`, the paths `open` let in as they are */
  def guarded(app: App, who: String => Option[String] ! Cloud, open: String => Boolean = _ => false): App = new App:
    override def admit(request: Request): Either[Response, Request] ! Cloud =
      val clean = request.copy(headers = request.headers - callerHeader)
      if open(clean.path) then app.admit(clean)
      else token(clean) match
        case None => pure(Left(refused("a bearer token is required")))
        case Some(t) => who(t).flatMap {
            case None => pure(Left(refused("the token is not known")))
            case Some(c) => app.admit(clean.copy(headers = clean.headers + (callerHeader -> List(c))))
          }
    override def objectFor(request: Request): Option[(String, String)] = app.objectFor(request)
    override def fetch(request: Request): Response ! Cloud = app.fetch(request)
    override def scheduled(event: Scheduled): Unit ! Cloud = app.scheduled(event)
    override def queue(queue: String, message: Message): Settle ! Cloud = app.queue(queue, message)
    override def mail(mail: IncomingMail): Unit ! Cloud = app.mail(mail)
