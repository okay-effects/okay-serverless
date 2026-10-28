package okay.serverless.cloud

import okay.scala3.*

import scala.concurrent.{ExecutionContext, Future}

/** a letter by SMTP over `Tcp`, against a server in memory: the whole conversation, and what is refused */
class SmtpSuite extends munit.FunSuite:
  given ExecutionContext = okay.scala3.std.Async.defaultContext
  private val relay = SmtpServer("smtp.test", 587, Tls.StartTls, "kontakt@szykownia.pl", "secret", "szykownia.pl")
  private val mail = Mail("kontakt@szykownia.pl", "anna@example.com", "Potwierdzenie — SZY-7K3F2Q", "Dziękujemy",
    Some("<p>Dziękujemy</p>"), Some("kontakt@szykownia.pl"), Some("Szykownia"))

  private def deliver(server: FakeSmtp, to: SmtpServer = relay, m: Mail = mail): Future[Either[String, Unit]] =
    val cloud = MemoryCloud()
    cloud.listen("smtp.test", 587)(server)
    cloud.platform.future(Smtp.deliver[Cloud](to, m, "id1@szykownia.pl", 0L))

  test("the conversation: EHLO, STARTTLS, EHLO again, AUTH LOGIN, MAIL, RCPT, DATA, QUIT; the letter as MIME") {
    val server = FakeSmtp()
    deliver(server).map { r =>
      assertEquals(r, Right(()))
      assertEquals(server.said.take(3), Vector("EHLO szykownia.pl", "STARTTLS", "EHLO szykownia.pl"))
      assert(server.tls)
      assertEquals(server.signedIn, Some("kontakt@szykownia.pl" -> "secret"))
      val (from, to, data) = server.letters.head
      assertEquals((from, to), ("kontakt@szykownia.pl", "anna@example.com"))
      assertEquals(data, Mime.message(mail, "id1@szykownia.pl", 0L))
      assertEquals(server.said.last, "QUIT")
    }
  }

  test("a line starting with a dot is sent doubled and read back as it was") {
    val server = FakeSmtp()
    val dotted = Mail("a@b.pl", "c@d.pl", "s", "x", fromName = None)
    // a text whose base64 line could not start with a dot: the stuffing shown on a header instead
    deliver(server, m = dotted.copy(subject = ".hidden")).map { r =>
      assertEquals(r, Right(()))
      assert(server.letters.head._3.contains("Subject: .hidden"))
    }
  }

  test("refused: a recipient, a server without STARTTLS (no credential sent), no server at all") {
    val refused = FakeSmtp()
    val noTls = FakeSmtp(starttls = false)
    for
      r1 <- deliver(refused, m = mail.copy(to = "x@refused.example"))
      r2 <- deliver(noTls)
      r3 <- MemoryCloud().platform.future(Smtp.deliver[Cloud](relay, mail, "id", 0L))
    yield
      assertEquals(r1, Left("RCPT TO: 550 5.1.1 no such user"))
      assertEquals(r2, Left("the server does not offer STARTTLS: nothing sent"))
      assert(noTls.signedIn.isEmpty && !noTls.said.exists(_.startsWith("AUTH")))
      assert(r3.left.exists(_.startsWith("connect: connection refused")), r3)
  }
