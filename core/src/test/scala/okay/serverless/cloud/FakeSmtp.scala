package okay.serverless.cloud

/** AN SMTP SERVER OF A TEST'S: it says what a relay says, keeps what it was sent, refuses a recipient of
 * `refused.example`, offers STARTTLS unless told not to */
final class FakeSmtp(starttls: Boolean = true) extends TcpServer:
  @volatile var letters: Vector[(String, String, String)] = Vector.empty // from, to, the data
  @volatile var said: Vector[String] = Vector.empty
  @volatile var tls = false
  /** the user and password it was given, decoded */
  @volatile var signedIn: Option[(String, String)] = None

  def open(fromStart: Boolean): TcpSession = new TcpSession:
    private val out = StringBuilder("220 fake.smtp ESMTP\r\n")
    private val in = StringBuilder()
    private var data: Option[StringBuilder] = None
    private var from = ""
    private var to = ""
    /** AUTH LOGIN: the user's line next (1), then the password's (2) */
    private var auth = 0
    private var user = ""
    private def decoded(s: String) = String(java.util.Base64.getDecoder.decode(s), "UTF-8")
    tls = fromStart
    def receive(text: String): Unit =
      in.append(text)
      var i = in.indexOf("\r\n")
      while i >= 0 do
        val line = in.substring(0, i)
        in.delete(0, i + 2)
        handle(line)
        i = in.indexOf("\r\n")
    private def reply(s: String): Unit = out.append(s + "\r\n")
    private def handle(line: String): Unit = data match
      case _ if auth == 1 => user = decoded(line); auth = 2; reply("334 UGFzc3dvcmQ6")
      case _ if auth == 2 => signedIn = Some(user -> decoded(line)); auth = 0; reply("235 2.7.0 authenticated")
      case Some(d) =>
        if line == "." then
          letters :+= (from, to, d.toString)
          data = None
          reply("250 2.0.0 queued")
        else d.append((if line.startsWith("..") then line.drop(1) else line) + "\r\n")
      case None =>
        said :+= line
        line.takeWhile(_ != ' ').toUpperCase match
          case "EHLO" =>
            reply("250-fake.smtp")
            if starttls && !tls then reply("250-STARTTLS")
            reply("250 AUTH LOGIN")
          case "STARTTLS" => reply("220 2.0.0 ready")
          case "AUTH" => auth = 1; reply("334 VXNlcm5hbWU6")
          case "MAIL" => from = line.drop(line.indexOf('<') + 1).takeWhile(_ != '>'); reply("250 ok")
          case "RCPT" =>
            to = line.drop(line.indexOf('<') + 1).takeWhile(_ != '>')
            if to.endsWith("@refused.example") then reply("550 5.1.1 no such user") else reply("250 ok")
          case "DATA" => data = Some(StringBuilder()); reply("354 go on")
          case "QUIT" => reply("221 bye")
          case _ => reply("500 5.5.1 what?")
    def answer(): Option[String] =
      if out.nonEmpty then
        val s = out.toString
        out.clear()
        Some(s)
      else None // nothing more to say: in memory every answer is there as soon as it is asked
    override def upgrade(): Boolean =
      tls = true
      true
