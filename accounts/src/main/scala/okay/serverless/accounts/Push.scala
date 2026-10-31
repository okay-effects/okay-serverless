package okay.serverless.accounts

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.{Clock, Env}
import okay.serverless.cloud.*
import okay.serverless.relay.Hashes

/**
 * A NOTIFICATION TO A PERSON'S PHONES (specs/accounts.md, "Push"): to each device they registered, by its platform's
 * service — APNs for an iPhone (a JWT signed ES256 by the team's `.p8` key), FCM for Android (HTTP v1, an OAuth token
 * a service account signs RS256 for). Each service's token kept in KV a while; a device the service no longer knows
 * (APNs 410, FCM `UNREGISTERED`) forgotten.
 */
object Push:
  type C[X] = (Cloud + Crypto)[X]
  final case class Device(platform: String, token: String)
  /** what a send did: each device, and its service's word */
  final case class Sent(device: Device, status: Int, gone: Boolean)

  private def call(method: String, url: String, headers: Map[String, String], body: String): Response ! C =
    Fetch[C](okay.http.Fetch.base(url), okay.http.Fetch.request(method, url, headers, body))
  private def b64(s: String): String = Hashes.base64url(Hashes.utf8(s))
  private def field(j: Json, k: String): Option[String] =
    j match { case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s }; case _ => None }

  /** a token of a service, kept in KV `ACCOUNTS` under `key` for `seconds`, made by `make` when there is none */
  private def kept(key: String, seconds: Long)(make: Option[String] ! C): Option[String] ! C =
    Kv.get[C](Accounts.ns, key).flatMap {
      case Some(t) => pure(Some(t))
      case None => make.flatMap {
          case Some(t) => Kv.put[C](Accounts.ns, key, t, Some(seconds)).as(Some(t))
          case None => pure(None)
        }
    }

  /** APNs' provider token: `{alg: ES256, kid}.{iss: team, iat}`, by the key `APNS_KEY` (a `.p8`'s PEM) */
  private def apnsToken: Option[String] ! C =
    kept("push/apns-token", 50 * 60)(for
      key <- Env.secret[C]("APNS_KEY")
      kid <- Env.variable[C]("APNS_KEY_ID")
      team <- Env.variable[C]("APNS_TEAM_ID")
      now <- Clock.now[C]
      signed <- (key, kid, team) match
        case (Some(k), Some(i), Some(t)) =>
          val unsigned = b64(Json.print(Json.obj("alg" -> Json.Str("ES256"), "kid" -> Json.Str(i)))) + "." +
            b64(Json.print(Json.obj("iss" -> Json.Str(t), "iat" -> Json.num(now / 1000))))
          Crypto.signEs256[C](Crypto.pem(k), Bytes(Hashes.utf8(unsigned)))
            .map(s => Some(s"$unsigned.${Hashes.base64url(s.toArray)}"))
        case _ => pure[Option[String], C](None)
    yield signed)

  /** FCM's access token: a service account's (`FCM_SERVICE_ACCOUNT`, its JSON) assertion for the messaging scope,
   * exchanged at Google's token endpoint */
  private def fcmToken(account: Json): Option[String] ! C =
    kept("push/fcm-token", 55 * 60)((field(account, "client_email"), field(account, "private_key")) match
      case (Some(email), Some(key)) => Clock.now[C].flatMap { now =>
          val unsigned = b64(Json.print(Json.obj("alg" -> Json.Str("RS256"), "typ" -> Json.Str("JWT")))) + "." +
            b64(Json.print(Json.obj("iss" -> Json.Str(email),
              "scope" -> Json.Str("https://www.googleapis.com/auth/firebase.messaging"),
              "aud" -> Json.Str("https://oauth2.googleapis.com/token"),
              "iat" -> Json.num(now / 1000), "exp" -> Json.num(now / 1000 + 3600))))
          Crypto.signRs256[C](Crypto.pem(key), Bytes(Hashes.utf8(unsigned))).flatMap { s =>
            call("POST", "https://oauth2.googleapis.com/token",
              Map("content-type" -> "application/x-www-form-urlencoded"),
              "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" +
                s"$unsigned.${Hashes.base64url(s.toArray)}")
              .map(r => Descent.parse(r.text).toOption.flatMap(field(_, "access_token")).filter(_ => r.status == 200))
          }
        }
      case _ => pure(None))

  /** one notification to one device: the service's status, and whether the device is gone */
  def send(d: Device, title: String, body: String): Sent ! C = d.platform match
    case "apns" =>
      for
        token <- apnsToken
        topic <- Env.variable[C]("APNS_TOPIC")
        sandbox <- Env.variable[C]("APNS_SANDBOX").map(_.contains("yes"))
        r <- token.fold(pure[Response, C](Response.text("no APNs key", 503)))(t =>
          call("POST", s"https://api${if sandbox then ".sandbox" else ""}.push.apple.com/3/device/${d.token}",
            Map("authorization" -> s"bearer $t", "apns-topic" -> topic.getOrElse(""), "apns-push-type" -> "alert",
              "content-type" -> "application/json"),
            Json.print(Json.obj("aps" -> Json.obj("alert" -> Json.obj("title" -> Json.Str(title),
              "body" -> Json.Str(body)), "sound" -> Json.Str("default"))))))
      yield Sent(d, r.status, r.status == 410)
    case "fcm" =>
      for
        account <- Env.secret[C]("FCM_SERVICE_ACCOUNT").map(_.flatMap(a => Descent.parse(a).toOption))
        token <- account.fold(pure[Option[String], C](None))(fcmToken)
        project = account.flatMap(field(_, "project_id")).getOrElse("")
        r <- token.fold(pure[Response, C](Response.text("no FCM account", 503)))(t =>
          call("POST", s"https://fcm.googleapis.com/v1/projects/$project/messages:send",
            Map("authorization" -> s"Bearer $t", "content-type" -> "application/json"),
            Json.print(Json.obj("message" -> Json.obj("token" -> Json.Str(d.token),
              "notification" -> Json.obj("title" -> Json.Str(title), "body" -> Json.Str(body)))))))
      yield Sent(d, r.status, r.status == 404 && r.text.contains("UNREGISTERED"))
    case other => pure(Sent(d, 400, true))
