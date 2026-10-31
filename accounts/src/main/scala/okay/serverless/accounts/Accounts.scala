package okay.serverless.accounts

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.{Clock, Env}
import okay.serverless.Durable
import okay.serverless.cloud.*
import okay.serverless.relay.Hashes

/**
 * A PHONE APP'S BACKEND (specs/accounts.md): signing in — a code by mail, Apple's or Google's identity token — into a
 * session, and what the person's program marks as shared, kept in their own object. `Accounts()` the app, `actors`
 * its objects; KV `ACCOUNTS` its sessions and the issuers' keys; `MAIL`/`MAIL_FROM` its mail; `APPLE_CLIENT_IDS`,
 * `GOOGLE_CLIENT_IDS` the audiences it takes.
 */
final class Accounts extends App:
  import Accounts.*

  override def fetch(r: Request): Response ! Cloud =
    (r.method, r.path.takeWhile(_ != '?').split('/').toList.drop(1)) match
      case ("POST", List("auth", "email")) => email(r)
      case ("POST", List("auth", "email", "code")) => code(r)
      case ("POST", List("auth", "apple")) => identity(r, apple)
      case ("POST", List("auth", "google")) => identity(r, google)
      case ("POST", List("auth", "out")) => Auth.token(r).fold(pure(Response.json(Json.Obj.empty)))(t =>
          Kv.delete[Cloud](ns, s"session/$t").as(Response.json(Json.Obj.empty)))
      case (_, "shared" :: rest) if rest.length <= 1 => person(r).flatMap {
          case None => pure(Response.json(Json.obj("error" -> Json.Str("not signed in")), 401))
          case Some(who) => Objects.call[Cloud]("SHARED", who, r)
        }
      case _ => pure(Response.json(Json.obj("error" -> Json.Str("no such path")), 404))

  /** the person a request's session names */
  private def person(r: Request): Option[String] ! Cloud =
    Auth.token(r).fold(pure[Option[String], Cloud](None))(t => Kv.get[Cloud](ns, s"session/$t"))

  /** a code to an address: asked of the address's own object, one request at a time */
  private def email(r: Request): Response ! Cloud = body(r).flatMap(b => text(b, "email").map(_.trim.toLowerCase)) match
    case Some(a) if a.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") =>
      Objects.call[Cloud]("SIGNIN", a, Request.json("POST", "/send", Json.obj("email" -> Json.Str(a))))
    case _ => pure(bad("an email address"))

  private def code(r: Request): Response ! Cloud =
    val b = body(r)
    (b.flatMap(text(_, "email")).map(_.trim.toLowerCase), b.flatMap(text(_, "code"))) match
      case (Some(a), Some(c)) =>
        Objects.call[Cloud]("SIGNIN", a, Request.json("POST", "/check", Json.obj("code" -> Json.Str(c)))).flatMap {
          res => if res.status != 200 then pure(res) else session(s"email:$a")
        }
      case _ => pure(bad("an email and its code"))

  /** an identity token, checked by its issuer's keys — fetched again once when its key is not among them */
  private def identity(r: Request, issuer: Issuer): Response ! Cloud =
    body(r).flatMap(text(_, "token")).flatMap(Jwt.parse) match
      case None => pure(bad("a token"))
      case Some(t) =>
        for
          audiences <- Env.variable[Cloud](issuer.audiences).map(_.toList.flatMap(_.split(',')).map(_.trim).toSet)
          now <- Clock.now[Cloud]
          kept <- keys(issuer, fresh = false)
          ks <- if t.kid.exists(k => kept.exists(_.kid == k)) then pure[Vector[Jwk], Cloud](kept)
            else keys(issuer, fresh = true)
          answer <- Jwt.check(t, ks, issuer.issuers, audiences, now / 1000) match
            case Left(why) => pure[Response, Cloud](Response.json(Json.obj("error" -> Json.Str(why)), 401))
            case Right(sub) => session(s"${issuer.name}:$sub")
        yield answer

  private def keys(issuer: Issuer, fresh: Boolean): Vector[Jwk] ! Cloud =
    val key = s"keys/${issuer.name}"
    (if fresh then pure[Option[String], Cloud](None) else Kv.get[Cloud](ns, key)).flatMap {
      case Some(set) => pure(Descent.parse(set).toOption.map(Jwt.keys).getOrElse(Vector.empty))
      case None => Fetch.get[Cloud](issuer.keys).flatMap { res =>
          if res.status != 200 then pure(Vector.empty)
          else Kv.put[Cloud](ns, key, res.text, Some(3600)).as(Descent.parse(res.text).toOption.map(Jwt.keys)
            .getOrElse(Vector.empty))
        }
    }

  /** a session for a person: 32 random bytes, kept 90 days */
  private def session(who: String): Response ! Cloud =
    Crypto.run[Cloud + Crypto, Cloud, Bytes](Crypto.random[Cloud + Crypto](32)).flatMap { b =>
      val token = Hashes.hex(b.toArray)
      Kv.put[Cloud](ns, s"session/$token", who, Some(90L * 24 * 3600))
        .as(Response.json(Json.obj("session" -> Json.Str(token), "who" -> Json.Str(who))))
    }

object Accounts:
  def apply(): Accounts = new Accounts
  val ns = "ACCOUNTS"
  /** the app's objects, by namespace */
  val actors: Map[String, Actor] = Map("SIGNIN" -> SignIn, "SHARED" -> Shared)

  /** an issuer of identity tokens: its name, its keys' address, the issuers it writes, the audiences' variable */
  final case class Issuer(name: String, keys: String, issuers: Set[String], audiences: String)
  val apple: Issuer = Issuer("apple", "https://appleid.apple.com/auth/keys", Set("https://appleid.apple.com"),
    "APPLE_CLIENT_IDS")
  val google: Issuer = Issuer("google", "https://www.googleapis.com/oauth2/v3/certs",
    Set("accounts.google.com", "https://accounts.google.com"), "GOOGLE_CLIENT_IDS")

  private[accounts] def body(r: Request): Option[Json] = Descent.parse(r.text).toOption
  private[accounts] def text(j: Json, k: String): Option[String] =
    j match { case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s }; case _ => None }
  private[accounts] def bad(what: String): Response =
    Response.json(Json.obj("error" -> Json.Str(s"$what, please")), 400)

  /**
   * AN ADDRESS'S SIGNING IN: six digits made, kept as their SHA-256 ten minutes, sent by mail (or answered, in a
   * test); checked at most five times
   */
  object SignIn extends Actor:
    private val minutes = 10L
    def fetch(email: String, r: Request): Response ! InActor =
      r.path.takeWhile(_ != '?') match
        case "/send" =>
          for
            b <- Crypto.run[InActor + Crypto, InActor, Bytes](Crypto.random[InActor + Crypto](4))
            code = f"${(BigInt(1, b.toArray) % 1000000).toInt}%06d"
            now <- Clock.now[InActor]
            _ <- Durable.step[ActorStep, InActor, Unit](email)(
              Durable.put[String, ActorStep]("code", Hashes.hex(Hashes.sha256(Hashes.utf8(code))))
                .andThen(Durable.put[Long, ActorStep]("until", now + minutes * 60000))
                .andThen(Durable.put[Int, ActorStep]("tries", 0)))
            test <- Env.variable[InActor]("ACCOUNTS_TEST").map(_.contains("yes"))
            from <- Env.variable[InActor]("MAIL_FROM").map(_.getOrElse("no-reply@okay-effects.github.io"))
            sent <- if test then pure[Either[String, Unit], InActor](Right(()))
              else Mails.deliver[InActor]("MAIL", Mail(from, email, s"Your code: $code",
                s"$code\n\nThe code to sign in, for $minutes minutes. If you did not ask for it, do nothing."))
          yield sent match
            case Left(why) => Response.json(Json.obj("error" -> Json.Str(s"not sent: $why")), 502)
            case Right(_) => Response.json(Json.Obj(Vector("sent" -> Json.Bool(true)) ++
                Option.when(test)("code" -> Json.Str(code))))
        case "/check" =>
          val typed = body(r).flatMap(text(_, "code")).getOrElse("")
          Clock.now[InActor].flatMap(now => Durable.step[ActorStep, InActor, Either[String, Unit]](email)(
            for
              hash <- Durable.get[String, ActorStep]("code")
              until <- Durable.get[Long, ActorStep]("until")
              tries <- Durable.get[Int, ActorStep]("tries").map(_.getOrElse(0))
              answer <-
                if hash.isEmpty || until.forall(_ < now) then pure[Either[String, Unit], ActorStep](Left("no code"))
                else if tries >= 5 then pure[Either[String, Unit], ActorStep](Left("too many tries"))
                else if Auth.same(hash.get, Hashes.hex(Hashes.sha256(Hashes.utf8(typed.trim)))) then
                  Durable.put[Long, ActorStep]("until", 0L).as(Right(()))
                else Durable.put[Int, ActorStep]("tries", tries + 1).as(Left("not the code"))
            yield answer)).map {
            case Right(_) => Response.json(Json.obj("ok" -> Json.Bool(true)))
            case Left(why) => Response.json(Json.obj("error" -> Json.Str(why)), 401)
          }
        case _ => pure(Response.json(Json.obj("error" -> Json.Str("no such path")), 404))

  /** A PERSON'S SHARED VALUES: each with its version; the last write wins */
  object Shared extends Actor:
    def fetch(who: String, r: Request): Response ! InActor =
      val key = r.path.takeWhile(_ != '?').split('/').toList.drop(2).headOption.map(java.net.URLDecoder.decode(_,
        "UTF-8"))
      (r.method, key) match
        case ("GET", None) => Durable.step[ActorStep, InActor, List[(String, Long)]](who)(
            Durable.keys[ActorStep]("v/").flatMap(ks => ks.foldLeft(pure[List[(String, Long)], ActorStep](Nil))(
              (acc, k) => acc.flatMap(l => Durable.get[Long, ActorStep](s"n/${k.drop(2)}").map(n =>
                l :+ (k.drop(2) -> n.getOrElse(0L)))))))
          .map(l => Response.json(Json.obj("keys" -> Json.Obj(l.toVector.map((k, n) => k -> Json.num(n))))))
        case ("GET", Some(k)) => Durable.step[ActorStep, InActor, Option[(String, Long)]](who)(
            Durable.get[String, ActorStep](s"v/$k").flatMap(v => Durable.get[Long, ActorStep](s"n/$k")
              .map(n => v.map(_ -> n.getOrElse(0L))))).map {
            case Some((v, n)) => Response.json(Json.obj("value" -> Json.Str(v), "version" -> Json.num(n)))
            case None => Response.json(Json.obj("error" -> Json.Str("none")), 404)
          }
        case ("PUT", Some(k)) => body(r).flatMap(text(_, "value")) match
            case None => pure(bad("a value"))
            case Some(v) => Durable.step[ActorStep, InActor, Long](who)(
                Durable.get[Long, ActorStep](s"n/$k").flatMap { n =>
                  val next = n.getOrElse(0L) + 1
                  Durable.put[String, ActorStep](s"v/$k", v).andThen(Durable.put[Long, ActorStep](s"n/$k", next))
                    .as(next)
                }).map(n => Response.json(Json.obj("version" -> Json.num(n))))
        case ("DELETE", Some(k)) => Durable.step[ActorStep, InActor, Unit](who)(
            Durable.delete[ActorStep](s"v/$k").andThen(Durable.delete[ActorStep](s"n/$k")))
          .as(Response.json(Json.Obj.empty))
        case _ => pure(Response.json(Json.obj("error" -> Json.Str("no such path")), 404))
