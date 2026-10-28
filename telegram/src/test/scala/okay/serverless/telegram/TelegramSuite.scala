package okay.serverless.telegram

import okay.effects.json.Descent
import okay.scala3.*
import okay.serverless.cloud.*
import scala.concurrent.ExecutionContext.Implicits.global

class TelegramSuite extends munit.FunSuite:
  def hook(secret: String, body: String) =
    Request("POST", "/tg").copy(headers = Map("x-telegram-bot-api-secret-token" -> List(secret)), body = body)

  test("a webhook's update: refused without Telegram's secret; the chat, the text, whether private") {
    val body = """{"update_id":1,"message":{"chat":{"id":4242,"type":"private"},"text":"/start abc"}}"""
    assertEquals(Telegram.update(hook("wrong", body), "s3cret").left.map(_.status), Left(403))
    assertEquals(Telegram.update(hook("s3cret", body), "s3cret"), Right(Some(Telegram.Update("4242", "/start abc",
      true))))
    assertEquals(Telegram.update(hook("s3cret", """{"update_id":2}"""), "s3cret"), Right(None))
  }

  test("by the Bot API: sendMessage over Fetch, a Mini App's button only for https; a blocked chat is false") {
    val cloud = MemoryCloud()
    val asked = scala.collection.mutable.Buffer.empty[(String, String)]
    cloud.internet = (url, r) =>
      asked += url -> r.body
      if r.body.contains("\"666\"") then Response.text("blocked", 403)
      else Response.json(Descent.parse("""{"ok":true}""").toOption.get)
    type R[X] = (Cloud + Telegram)[X]
    val program = for
      a <- Telegram.send[R]("4242", "a call waits", Some("https://relay.example/"))
      b <- Telegram.send[R]("666", "hi")
      c <- Telegram.send[R]("7", "hi", Some("http://insecure/"))
    yield (a, b, c)
    cloud.platform.future(Telegram.viaBotApi[R, Cloud, (Boolean, Boolean, Boolean)]("t0k", "https://api.example")(
      program)).map { r =>
      assertEquals(r, (true, false, true))
      assertEquals(asked.head._1, "https://api.example/bott0k/sendMessage")
      assert(asked.head._2.contains("web_app") && asked.head._2.contains("https://relay.example/"))
      assert(!asked(2)._2.contains("web_app"))
    }
  }

  test("in memory: each message kept, a blocked chat false") {
    val sent = scala.collection.mutable.Buffer.empty[(String, String, Option[String])]
    val r = okay.scala3.run(Telegram.inMemory[Telegram, Pure, (Boolean, Boolean)](sent, Set("9"))(
      Telegram.send[Telegram]("1", "x").flatMap(a => Telegram.send[Telegram]("9", "y").map(a -> _))))
    assertEquals(r, (true, false))
    assertEquals(sent.toList, List(("1", "x", None)))
  }
