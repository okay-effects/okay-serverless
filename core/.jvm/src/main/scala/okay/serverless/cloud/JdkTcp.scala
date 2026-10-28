package okay.serverless.cloud

import java.io.{InputStream, OutputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import javax.net.ssl.{SSLSocket, SSLSocketFactory}
import scala.concurrent.{ExecutionContext, Future}

/** `Tcp` ON THE JVM: the JDK's sockets, TLS by `SSLSocket` — from the start, or over the plain socket when STARTTLS
 * asks. Each call blocking, on `ec`; a refusal a value */
final class JdkTcp(timeoutMs: Int = 30000)(using ec: ExecutionContext):
  private final case class Open(socket: Socket, in: InputStream, out: OutputStream, host: String, port: Int)
  private val open = scala.collection.concurrent.TrieMap.empty[String, Open]
  private val counter = java.util.concurrent.atomic.AtomicLong()

  private def opened(s: Socket, host: String, port: Int): Conn =
    val id = s"jdk-${counter.incrementAndGet()}"
    open(id) = Open(s, s.getInputStream, s.getOutputStream, host, port)
    Conn(id)

  private def attempt[A](f: => Either[String, A]): Future[Either[String, A]] =
    Future(try f catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString)))

  val port: Port[Tcp] = new Port[Tcp]:
    def apply[X](op: Tcp[X]): Future[X] = op match
      case Tcp.Connect(host, p, tls) => attempt {
          val plain = Socket()
          plain.connect(InetSocketAddress(host, p), timeoutMs)
          plain.setSoTimeout(timeoutMs)
          val s = if tls == Tls.On then
            val t = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory].createSocket(plain, host, p, true)
              .asInstanceOf[SSLSocket]
            t.startHandshake()
            t
          else plain
          Right(opened(s, host, p))
        }
      case Tcp.Write(c, text) => attempt(open.get(c.id).toRight("closed").map { o =>
          o.out.write(text.getBytes(UTF_8))
          o.out.flush()
        })
      case Tcp.Read(c) => attempt(open.get(c.id).toRight("closed").map { o =>
          val buf = new Array[Byte](8192)
          val n = o.in.read(buf)
          Option.when(n >= 0)(String(buf, 0, n, UTF_8))
        })
      case Tcp.Upgrade(c) => attempt(open.remove(c.id).toRight("closed").map { o =>
          val t = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory].createSocket(o.socket, o.host, o.port,
            true).asInstanceOf[SSLSocket]
          t.startHandshake()
          opened(t, o.host, o.port)
        })
      case Tcp.Close(c) => Future(open.remove(c.id).foreach(_.socket.close()))
