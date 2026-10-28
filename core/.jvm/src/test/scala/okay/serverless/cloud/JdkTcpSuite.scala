package okay.serverless.cloud

import okay.scala3.*

import java.net.ServerSocket
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext

/** SMTP over the JDK's real sockets, to a test's server on a free port (plain: the conversation, not TLS) */
class JdkTcpSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.global

  test("a letter delivered over a real socket; a port nobody listens on refused as a value") {
    val listening = ServerSocket(0)
    val fake = FakeSmtp(starttls = false)
    val serving = Thread(() => {
      val s = listening.accept()
      val session = fake.open(false)
      val in = s.getInputStream
      val out = s.getOutputStream
      def flush(): Unit = session.answer().foreach(t => { out.write(t.getBytes(UTF_8)); out.flush() })
      flush()
      val buf = new Array[Byte](8192)
      var n = in.read(buf)
      while n >= 0 && !fake.said.lastOption.contains("QUIT") do
        session.receive(String(buf, 0, n, UTF_8))
        flush()
        if !fake.said.lastOption.contains("QUIT") then n = in.read(buf)
      s.close()
    })
    serving.start()
    val tcp = JdkTcp()
    val platform = Platform(tcp = tcp.port)
    val server = SmtpServer("127.0.0.1", listening.getLocalPort, Tls.Off, "u", "p")
    val mail = Mail("a@b.pl", "c@d.pl", "subject", "text", Some("<p>html</p>"))
    for
      r <- platform.future(Smtp.deliver[Cloud](server, mail, "id@b.pl", 0L))
      refused <- platform.future(Smtp.deliver[Cloud](server.copy(port = 1), mail, "id@b.pl", 0L))
    yield
      assertEquals(r, Right(()))
      assertEquals(fake.letters.map(l => (l._1, l._2)), Vector(("a@b.pl", "c@d.pl")))
      assertEquals(fake.signedIn, Some("u" -> "p"))
      assert(refused.isLeft)
      listening.close()
  }
