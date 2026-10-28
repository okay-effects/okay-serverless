package okay.serverless.cloud

import okay.scala3.*

/** how a connection is secured: not at all, TLS from the start, or TLS after the protocol asks (`STARTTLS`) */
enum Tls:
  case Off, On, StartTls

/** a connection out, by its id */
final case class Conn(id: String)

/**
 * A TCP CONNECTION OUT (specs/cloud.md "Outbound TCP and SMTP"): connect, write, read what came, upgrade to TLS,
 * close. Every answer is an `Either` — a network's refusal a value the program decides on, never a failure
 */
enum Tcp[X]:
  case Connect(host: String, port: Int, tls: Tls) extends Tcp[Either[String, Conn]]
  case Write(conn: Conn, text: String) extends Tcp[Either[String, Unit]]
  /** the next text that came; `None` when the other side has closed */
  case Read(conn: Conn) extends Tcp[Either[String, Option[String]]]
  /** STARTTLS: the same connection, encrypted from here */
  case Upgrade(conn: Conn) extends Tcp[Either[String, Conn]]
  case Close(conn: Conn) extends Tcp[Unit]

object Tcp:
  def connect[R[_]](host: String, port: Int, tls: Tls)(using In[Tcp, R]): Either[String, Conn] ! R =
    perform[Tcp](Connect(host, port, tls))
  def write[R[_]](conn: Conn, text: String)(using In[Tcp, R]): Either[String, Unit] ! R =
    perform[Tcp](Write(conn, text))
  def read[R[_]](conn: Conn)(using In[Tcp, R]): Either[String, Option[String]] ! R = perform[Tcp](Read(conn))
  def upgrade[R[_]](conn: Conn)(using In[Tcp, R]): Either[String, Conn] ! R = perform[Tcp](Upgrade(conn))
  def close[R[_]](conn: Conn)(using In[Tcp, R]): Unit ! R = perform[Tcp](Close(conn))

/** an SMTP server and how to sign in to it */
final case class SmtpServer(host: String, port: Int, tls: Tls, user: String, password: String,
                            helo: String = "localhost")

/**
 * A LETTER BY SMTP, a program over `Tcp` (specs/cloud.md "Outbound TCP and SMTP"): greeting, EHLO, STARTTLS and EHLO
 * again, AUTH LOGIN, MAIL FROM, RCPT TO, DATA (the letter as `Mime.message` makes it, dot-stuffed), QUIT. `Right` when
 * the server took the letter; `Left` with its reply, or the network's word, when not — never a failure
 */
object Smtp:
  /** a reply: its code and its lines' text */
  final case class Reply(code: Int, text: String)

  def deliver[R[_]](server: SmtpServer, mail: Mail, messageId: String, now: Long)(using In[Tcp, R])
    : Either[String, Unit] ! R =
    Tcp.connect[R](server.host, server.port, server.tls).flatMap {
      case Left(why) => pure(Left(s"connect: $why"))
      case Right(c0) =>
        val session = Session[R](c0)
        session.conversation(server, mail, messageId, now).flatMap(r => session.end.as(r))
    }

  /** one conversation: the connection (replaced by STARTTLS) and what was read but not yet taken */
  private final class Session[R[_]](var conn: Conn)(using In[Tcp, R]):
    private var buffer = ""

    def end: Unit ! R = Tcp.close[R](conn)

    /** the next whole reply: lines `NNN-` go on, `NNN ` ends it */
    def reply: Either[String, Reply] ! R =
      val lines = buffer.split("\r\n", -1).toList
      val complete = lines.init
      complete.indexWhere(l => l.length >= 4 && l.charAt(3) == ' ') match
        case -1 => Tcp.read[R](conn).flatMap {
            case Left(why) => pure(Left(why))
            case Right(None) => pure(Left(s"closed by the server${if buffer.nonEmpty then s": $buffer" else ""}"))
            case Right(Some(text)) =>
              buffer += text
              reply
          }
        case last =>
          val taken = complete.take(last + 1)
          buffer = lines.drop(last + 1).mkString("\r\n")
          pure(taken.last.take(3).toIntOption.toRight(s"not SMTP: ${taken.last}")
            .map(code => Reply(code, taken.map(_.drop(4)).mkString("\n"))))

    /** `line` said, its reply expected to be `ok` */
    def say(line: String, ok: Int => Boolean, what: String): Either[String, Reply] ! R =
      Tcp.write[R](conn, line + "\r\n").flatMap {
        case Left(why) => pure(Left(why))
        case Right(()) => expect(ok, what)
      }

    def expect(ok: Int => Boolean, what: String): Either[String, Reply] ! R = reply.map {
      case Right(r) if ok(r.code) => Right(r)
      case Right(r) => Left(s"$what: ${r.code} ${r.text}")
      case Left(why) => Left(s"$what: $why")
    }

    private def base64(s: String) = java.util.Base64.getEncoder.encodeToString(s.getBytes("UTF-8"))

    /** the letter's lines as DATA sends them: a line starting with a dot doubled, the end a dot alone */
    def data(message: String): String =
      message.split("\r\n", -1).map(l => if l.startsWith(".") then "." + l else l).mkString("\r\n")
        .stripSuffix("\r\n") + "\r\n."

    /** each step in turn, until one is refused */
    private def inTurn(steps: List[() => Either[String, Unit] ! R]): Either[String, Unit] ! R = steps match
      case Nil => pure(Right(()))
      case first :: rest => first().flatMap {
        case Left(why) => pure(Left(why))
        case Right(()) => inTurn(rest)
      }

    private def ok(p: Either[String, Reply] ! R): Either[String, Unit] ! R = p.map(_.map(_ => ()))

    /** EHLO; and, asked for, STARTTLS — refused before any credential when the server does not offer it */
    private def hello(server: SmtpServer): Either[String, Unit] ! R =
      val ehlo = s"EHLO ${server.helo}"
      say(ehlo, _ == 250, "EHLO").flatMap {
        case Left(why) => pure(Left(why))
        case Right(_) if server.tls != Tls.StartTls => pure(Right(()))
        case Right(offered) if !offered.text.linesIterator.exists(_.trim.toUpperCase.startsWith("STARTTLS")) =>
          pure(Left("the server does not offer STARTTLS: nothing sent"))
        case Right(_) => say("STARTTLS", _ == 220, "STARTTLS").flatMap {
            case Left(why) => pure(Left(why))
            case Right(_) => Tcp.upgrade[R](conn).flatMap {
                case Left(why) => pure(Left(s"TLS: $why"))
                case Right(c) =>
                  conn = c
                  buffer = ""
                  ok(say(ehlo, _ == 250, "EHLO after STARTTLS"))
              }
          }
      }

    def conversation(server: SmtpServer, mail: Mail, messageId: String, now: Long): Either[String, Unit] ! R =
      inTurn(List(
        () => ok(expect(_ == 220, "greeting")),
        () => hello(server),
        () => ok(say("AUTH LOGIN", _ == 334, "AUTH")),
        () => ok(say(base64(server.user), _ == 334, "AUTH user")),
        () => ok(say(base64(server.password), _ == 235, "AUTH password")),
        () => ok(say(s"MAIL FROM:<${mail.from}>", _ == 250, "MAIL FROM")),
        () => ok(say(s"RCPT TO:<${mail.to}>", c => c == 250 || c == 251, "RCPT TO")),
        () => ok(say("DATA", _ == 354, "DATA")),
        () => ok(say(data(Mime.message(mail, messageId, now)), _ == 250, "the letter")),
        () => Tcp.write[R](conn, "QUIT\r\n")))
