package okay.serverless.accounts

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.relay.Hashes

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** NOTIFICATIONS to a person's phones, against Apple's and Google's services in memory: the requests as they would
 * go, APNs' token signed ES256 by the team's key, Google's token taken for a service account's assertion */
class PushSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  val now = 1_800_000_000_000L
  val asked = mutable.ListBuffer.empty[(String, Request)]

  def cloud(fcm: String => Response): MemoryCloud =
    val c = MemoryCloud(() => now)
    Accounts.actors.foreach((k, a) => c.actors(k) = a)
    c.vars("ACCOUNTS_TEST") = "yes"
    c.vars ++= Map("APNS_KEY_ID" -> "KEY123", "APNS_TEAM_ID" -> "TEAM45", "APNS_TOPIC" -> "com.example.app",
      "APNS_SANDBOX" -> "yes")
    c.secrets("APNS_KEY") = TestKeys.ecPrivate
    c.secrets("PUSH_SECRET") = "server-secret"
    c.secrets("FCM_SERVICE_ACCOUNT") = Json.print(Json.obj("client_email" -> Json.Str("push@example.iam"),
      "private_key" -> Json.Str(TestKeys.rsaPrivate), "project_id" -> Json.Str("example-project")))
    c.internet = (url, r) => synchronized {
      asked += ((url, r))
      if url.startsWith("https://oauth2.googleapis.com/token") then
        Response.json(Json.obj("access_token" -> Json.Str("google-access")))
      else if url.startsWith("https://fcm.googleapis.com/") then fcm(url)
      else if url.startsWith("https://api.sandbox.push.apple.com/3/device/") then Response.json(Json.Obj.empty)
      else Response.text("no", 404)
    }
    c
  def field(r: Response, k: String): Option[String] = Descent.parse(r.text).toOption.flatMap {
    case o: Json.Obj => o.get(k).map { case Json.Str(s) => s; case x => Json.print(x) }; case _ => None }
  def post(c: MemoryCloud, path: String, j: Json, bearer: Option[String] = None): Future[Response] =
    c.serve(Accounts())(Request.json("POST", path, j).copy(headers =
      bearer.map(s => "authorization" -> List(s"Bearer $s")).toMap))
  def signIn(c: MemoryCloud, email: String): Future[String] =
    for
      sent <- post(c, "/auth/email", Json.obj("email" -> Json.Str(email)))
      s <- post(c, "/auth/email/code", Json.obj("email" -> Json.Str(email),
        "code" -> Json.Str(field(sent, "code").get)))
    yield field(s, "session").get

  test("one's own phones notified: an iPhone by APNs, an Android phone by FCM; a gone device forgotten") {
    var fcmGone = false
    val c = cloud(_ => if fcmGone then Response.json(Json.obj("error" -> Json.obj("status" -> Json.Str("NOT_FOUND"),
      "details" -> Json.Str("UNREGISTERED"))), 404) else Response.json(Json.obj("name" -> Json.Str("m1"))))
    for
      s <- signIn(c, "ann@example.org")
      _ <- post(c, "/push/device", Json.obj("platform" -> Json.Str("apns"), "token" -> Json.Str("iphone-1")), Some(s))
      _ <- post(c, "/push/device", Json.obj("platform" -> Json.Str("fcm"), "token" -> Json.Str("android-1")), Some(s))
      first <- post(c, "/push", Json.obj("title" -> Json.Str("Notes"), "body" -> Json.Str("Buy milk")), Some(s))
      _ = fcmGone = true
      second <- post(c, "/push", Json.obj("title" -> Json.Str("Notes"), "body" -> Json.Str("again")), Some(s))
      third <- post(c, "/push", Json.obj("title" -> Json.Str("Notes"), "body" -> Json.Str("once more")), Some(s))
    yield
      assertEquals(field(first, "sent"), Some("2"))
      val apns = asked.find(_._1.contains("push.apple.com")).get
      assertEquals(apns._1, "https://api.sandbox.push.apple.com/3/device/iphone-1")
      assertEquals(apns._2.header("apns-topic"), Some("com.example.app"))
      assert(apns._2.text.contains("\"Buy milk\""), apns._2.text)
      val jwt = apns._2.header("authorization").get.stripPrefix("bearer ")
      val Array(h, cl, _) = jwt.split('.')
      assert(new String(Hashes.unbase64url(h).get, "UTF-8").contains("\"kid\":\"KEY123\""))
      assert(new String(Hashes.unbase64url(cl).get, "UTF-8").contains("\"iss\":\"TEAM45\""))
      val fcm = asked.find(_._1.contains("fcm.googleapis.com")).get
      assertEquals(fcm._1, "https://fcm.googleapis.com/v1/projects/example-project/messages:send")
      assertEquals(fcm._2.header("authorization"), Some("Bearer google-access"))
      assert(asked.exists(a => a._1.startsWith("https://oauth2.googleapis.com/token") &&
        a._2.text.contains("assertion=")))
      assert(second.text.contains("\"gone\":true"), second.text)
      assertEquals(field(third, "sent"), Some("1"), "the gone Android phone forgotten")
      assertEquals(asked.count(_._1.startsWith("https://oauth2.googleapis.com/token")), 1, "Google's token kept")
  }

  test("the app's server notifies anyone by its secret; without it, nobody") {
    val c = cloud(_ => Response.json(Json.Obj.empty))
    for
      s <- signIn(c, "bob@example.org")
      _ <- post(c, "/push/device", Json.obj("platform" -> Json.Str("apns"), "token" -> Json.Str("bob-1")), Some(s))
      byServer <- post(c, "/push", Json.obj("to" -> Json.Str("email:bob@example.org"), "title" -> Json.Str("Hi"),
        "body" -> Json.Str("from the server")), Some("server-secret"))
      byNobody <- post(c, "/push", Json.obj("to" -> Json.Str("email:bob@example.org"), "title" -> Json.Str("x")))
    yield
      assertEquals(field(byServer, "sent"), Some("1"))
      assertEquals(byNobody.status, 401)
  }
