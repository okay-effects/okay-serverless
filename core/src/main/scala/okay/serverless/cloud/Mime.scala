package okay.serverless.cloud

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/**
 * A LETTER AS A RAW MIME MESSAGE — what Cloudflare's `send_email` takes in an `EmailMessage` (specs/cloud.md
 * "Mail"): the headers it asks for (`From`, `To`, `Subject`, `Message-ID`, `Date`), names and the subject as RFC 2047
 * words, the text alone or the text and the HTML as one multipart/alternative, each part UTF-8 in base64 (lines of
 * 76). Pure: the same on every platform
 */
object Mime:
  def message(mail: Mail, messageId: String, now: Long): String =
    val headers = Vector("From: " + mail.fromName.fold(mail.from)(n => s"${word(n)} <${mail.from}>"),
      "To: " + mail.to) ++ mail.replyTo.map("Reply-To: " + _) ++ Vector(
      "Subject: " + word(mail.subject), s"Message-ID: <$messageId>", "Date: " + date(now), "MIME-Version: 1.0")
    mail.html match
      case None => (headers ++ part("text/plain")).mkString("\r\n") + "\r\n\r\n" + base64(mail.text) + "\r\n"
      case Some(html) =>
        val boundary = "okay-" + messageId.filter(_.isLetterOrDigit).take(24)
        def body(kind: String, text: String) =
          s"--$boundary\r\n${part(kind).mkString("\r\n")}\r\n\r\n${base64(text)}\r\n"
        (headers :+ s"Content-Type: multipart/alternative; boundary=\"$boundary\"").mkString("\r\n") + "\r\n\r\n" +
          body("text/plain", mail.text) + body("text/html", html) + s"--$boundary--\r\n"

  private def part(kind: String) =
    Vector(s"Content-Type: $kind; charset=UTF-8", "Content-Transfer-Encoding: base64")

  /** a header's text: as it is when plain ASCII, else RFC 2047 words of at most 45 bytes each */
  def word(s: String): String =
    if s.forall(c => c >= ' ' && c < 127) then s
    else
      val words = Vector.newBuilder[String]
      val cur = StringBuilder()
      var bytes = 0
      var i = 0
      while i < s.length do
        val cp = s.codePointAt(i)
        val ch = String(Character.toChars(cp))
        val n = ch.getBytes(UTF_8).length
        if bytes + n > 45 then
          words += cur.toString
          cur.clear()
          bytes = 0
        cur.append(ch)
        bytes += n
        i += Character.charCount(cp)
      if cur.nonEmpty then words += cur.toString
      words.result().map(w => "=?UTF-8?B?" + Base64.getEncoder.encodeToString(w.getBytes(UTF_8)) + "?=")
        .mkString("\r\n ")

  def base64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes(UTF_8)).grouped(76).mkString("\r\n")

  private val days = Vector("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
  private val months = Vector("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  /** RFC 5322's date of an instant, in UTC — no java.time, which Scala.js does not have */
  def date(millis: Long): String =
    val day = Math.floorDiv(millis, 86400000L)
    // days since 1970-01-01 as a civil date (Howard Hinnant's algorithm)
    val z = day + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val y = yoe + era * 400 + (if m <= 2 then 1 else 0)
    val secs = Math.floorMod(millis / 1000, 86400L)
    val dow = days(Math.floorMod(day, 7L).toInt)
    f"$dow, $d%d ${months(m.toInt - 1)} $y%04d ${secs / 3600}%02d:${secs / 60 % 60}%02d:${secs % 60}%02d +0000"
