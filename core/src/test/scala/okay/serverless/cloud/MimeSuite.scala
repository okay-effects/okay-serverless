package okay.serverless.cloud

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

class MimeSuite extends munit.FunSuite:
  private def decode(words: Seq[String]): String = words.map(w => String(Base64.getDecoder.decode(
    w.trim.stripPrefix("=?UTF-8?B?").stripSuffix("?=")), UTF_8)).mkString

  test("a letter with its HTML: the headers Cloudflare asks, the subject's words, both parts") {
    val m = Mail("kontakt@szykownia.pl", "klient@example.com", "Potwierdzenie zlecenia — Szykownia", "Dziękujemy!",
      Some("<p>Dziękujemy!</p>"), Some("kontakt@szykownia.pl"), Some("Szykownia"))
    val raw = Mime.message(m, "abc123@szykownia.pl", 1793282400000L)
    assert(raw.startsWith("From: Szykownia <kontakt@szykownia.pl>\r\nTo: klient@example.com\r\n" +
      "Reply-To: kontakt@szykownia.pl\r\nSubject: =?UTF-8?B?"), raw)
    assert(raw.contains("Message-ID: <abc123@szykownia.pl>\r\nDate: Thu, 29 Oct 2026 14:00:00 +0000\r\n"))
    val subject = raw.linesIterator.dropWhile(!_.startsWith("Subject:")).takeWhile(l => l.startsWith("Subject:") ||
      l.startsWith(" ")).map(_.stripPrefix("Subject: ")).toSeq
    assertEquals(decode(subject), m.subject)
    assert(subject.forall(_.trim.length <= 75))
    assert(raw.contains("Content-Type: multipart/alternative; boundary=\"okay-abc123szykowniapl\""))
    assert(raw.contains(Mime.base64("<p>Dziękujemy!</p>")) && raw.contains(Mime.base64("Dziękujemy!")))
    assert(raw.endsWith("--okay-abc123szykowniapl--\r\n"))
  }

  test("text alone: one part, as before; an ASCII name and subject as they are") {
    val raw = Mime.message(Mail("a@b.pl", "c@d.pl", "hi", "hello", fromName = Some("Shop")), "x@b.pl", 0L)
    assert(raw.startsWith("From: Shop <a@b.pl>\r\nTo: c@d.pl\r\nSubject: hi\r\n"), raw)
    assert(raw.contains("Date: Thu, 1 Jan 1970 00:00:00 +0000"))
    assert(raw.contains("Content-Type: text/plain; charset=UTF-8") && !raw.contains("multipart"))
    assert(raw.endsWith(Mime.base64("hello") + "\r\n"))
  }

  test("delivered in memory: kept, and told sent; a cloud with no mail tells why not") {
    import okay.scala3.*
    given scala.concurrent.ExecutionContext = okay.scala3.std.Async.defaultContext
    val cloud = MemoryCloud()
    val mail = Mail("a@b.pl", "c@d.pl", "s", "t", Some("<b>t</b>"))
    cloud.platform.future(Mails.deliver[Cloud]("MAIL", mail)).map { r =>
      assertEquals(r, Right(()))
      assertEquals(cloud.sent.toList, List("MAIL" -> mail))
    }.flatMap(_ => Platform().future(Mails.deliver[Cloud]("MAIL", mail))).map(r =>
      assertEquals(r, Left("no mail here")))
  }
