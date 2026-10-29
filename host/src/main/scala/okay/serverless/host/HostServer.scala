package okay.serverless.host

import okay.http.{Bytes, Query, Request, Response}
import okay.scala3.std.Level
import okay.serverless.cloud.App

import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.net.{InetAddress, ServerSocket, Socket, SocketException}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * THE HOST'S HTTP SERVER (specs/cloud.md "The host"): HTTP/1.1 over a server socket, a virtual thread a connection,
 * keep-alive; the static directory first (as a Worker's assets), then the app — admitted, to its object or to
 * `fetch`. An answer naming an object's socket (`okay-socket`) keeps the connection: a WebSocket (RFC 6455: text
 * frames, ping, close) when it is 101, an event stream when it is 200.
 */
final class HostServer(cloud: HostCloud, app: App):
  private val config = cloud.config
  private val server = ServerSocket(config.port, 128, InetAddress.getByName(config.host))
  /** the port it listens on: the configuration's, or the one given when that was 0 */
  val port: Int = server.getLocalPort
  @volatile private var running = true

  private val acceptor = Thread.ofPlatform().name("okay-host-accept").start(() =>
    while running do
      try
        val s = server.accept()
        Thread.ofVirtual().start(() => connection(s)): Unit
      catch case _: SocketException => ())

  def stop(): Unit =
    running = false
    server.close()
    acceptor.join(2000)

  // ---- one connection: requests until it closes, or until it becomes a socket's

  private final case class Raw(method: String, target: String, headers: Map[String, List[String]], body: Array[Byte])

  private def connection(s: Socket): Unit =
    try
      val in = BufferedInputStream(s.getInputStream)
      val out = BufferedOutputStream(s.getOutputStream)
      var open = true
      while open do
        read(in) match
          case None => open = false
          case Some(raw) =>
            val keep = !raw.headers.get("connection").exists(_.exists(_.equalsIgnoreCase("close")))
            static(raw) match
              case Some(r) =>
                write(out, r, raw.method == "HEAD", keep)
                open = keep
              case None =>
                val r = answer(raw)
                r.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("okay-socket") => v }
                  .flatMap(cloud.socket) match
                  case Some(socket) =>
                    if r.status == 101 then upgrade(raw, socket, in, out)
                    else stream(socket, r, in, out)
                    open = false
                  case None =>
                    write(out, r, raw.method == "HEAD", keep)
                    open = keep
    catch case _: java.io.IOException => ()
    finally s.close()

  private def answer(raw: Raw): Response =
    val (path, q) = raw.target.indexOf('?') match
      case -1 => (raw.target, "")
      case i => (raw.target.substring(0, i), raw.target.substring(i + 1))
    val request = Request(raw.method, path, Query.parse(q), raw.headers).withContent(Bytes(raw.body))
    try Await.result(cloud.serve(app)(request), 5.minutes)
    catch case e: Exception =>
      config.log(Level.Error, s"${raw.method} ${raw.target}: ${Option(e.getMessage).getOrElse(e.toString)}")
      Response.text("internal error", 500)

  // ---- reading a request

  private def line(in: InputStream): Option[String] =
    val b = ByteArrayOutputStream()
    var c = in.read()
    if c < 0 then None
    else
      while c >= 0 && c != '\n' do
        if c != '\r' then b.write(c)
        c = in.read()
      Some(String(b.toByteArray, ISO_8859_1))

  private def read(in: InputStream): Option[Raw] =
    line(in).filter(_.nonEmpty).flatMap { first =>
      first.split(' ') match
        case Array(method, target, _*) =>
          val headers = Iterator.continually(line(in).getOrElse("")).takeWhile(_.nonEmpty).flatMap { h =>
            h.indexOf(':') match
              case -1 => None
              case i => Some(h.substring(0, i).trim.toLowerCase -> h.substring(i + 1).trim)
          }.toVector.groupMap(_._1)(_._2).view.mapValues(_.toList).toMap
          val body =
            if headers.get("transfer-encoding").exists(_.exists(_.toLowerCase.contains("chunked"))) then chunked(in)
            else headers.get("content-length").flatMap(_.headOption).flatMap(_.toIntOption) match
              case Some(n) if n > 0 => in.readNBytes(n)
              case _ => Array.emptyByteArray
          Some(Raw(method, target, headers, body))
        case _ => None
    }

  private def chunked(in: InputStream): Array[Byte] =
    val out = ByteArrayOutputStream()
    var size = line(in).map(l => Integer.parseInt(l.takeWhile(_ != ';').trim, 16)).getOrElse(0)
    while size > 0 do
      out.write(in.readNBytes(size))
      line(in): Unit
      size = line(in).map(l => Integer.parseInt(l.takeWhile(_ != ';').trim, 16)).getOrElse(0)
    while line(in).exists(_.nonEmpty) do ()
    out.toByteArray

  // ---- writing a response

  private val reasons = Map(200 -> "OK", 201 -> "Created", 204 -> "No Content", 301 -> "Moved Permanently",
    302 -> "Found", 303 -> "See Other", 304 -> "Not Modified", 307 -> "Temporary Redirect", 400 -> "Bad Request",
    401 -> "Unauthorized", 403 -> "Forbidden", 404 -> "Not Found", 405 -> "Method Not Allowed", 409 -> "Conflict",
    429 -> "Too Many Requests", 500 -> "Internal Server Error", 502 -> "Bad Gateway", 503 -> "Service Unavailable")

  private val hop = Set("content-length", "transfer-encoding", "connection", "keep-alive", "okay-socket")

  private def write(out: OutputStream, r: Response, head: Boolean, keep: Boolean): Unit =
    val body = r.content.toArray
    val b = StringBuilder(s"HTTP/1.1 ${r.status} ${reasons.getOrElse(r.status, "")}\r\n")
    r.headers.foreach((k, v) => if !hop(k.toLowerCase) then b.append(s"$k: $v\r\n"))
    b.append(s"Content-Length: ${body.length}\r\n")
    b.append(if keep then "Connection: keep-alive\r\n" else "Connection: close\r\n")
    b.append("\r\n")
    out.write(b.toString.getBytes(UTF_8))
    if !head then out.write(body)
    out.flush()

  // ---- the static directory: a file under it, `/` its index.html

  private val types = Map("html" -> "text/html; charset=utf-8", "js" -> "text/javascript; charset=utf-8",
    "mjs" -> "text/javascript; charset=utf-8", "css" -> "text/css; charset=utf-8", "json" -> "application/json",
    "svg" -> "image/svg+xml", "png" -> "image/png", "jpg" -> "image/jpeg", "jpeg" -> "image/jpeg",
    "gif" -> "image/gif", "webp" -> "image/webp", "ico" -> "image/x-icon", "txt" -> "text/plain; charset=utf-8",
    "wasm" -> "application/wasm", "woff2" -> "font/woff2", "map" -> "application/json", "pdf" -> "application/pdf",
    "webmanifest" -> "application/manifest+json", "xml" -> "application/xml")

  private def static(raw: Raw): Option[Response] =
    config.static.filter(_ => raw.method == "GET" || raw.method == "HEAD").flatMap { root =>
      val base = root.toAbsolutePath.normalize
      val path = Query.decode(raw.target.takeWhile(_ != '?').stripPrefix("/"))
      val f = base.resolve(path).normalize
      val file = if Files.isDirectory(f) then f.resolve("index.html") else f
      Option.when(file.startsWith(base) && Files.isRegularFile(file)) {
        val ext = file.getFileName.toString.split('.').lastOption.getOrElse("").toLowerCase
        Response.binary(Bytes(Files.readAllBytes(file)), types.getOrElse(ext, "application/octet-stream"))
      }
    }

  // ---- a WebSocket: the handshake, then frames both ways until either side closes

  private def upgrade(raw: Raw, socket: cloud.HostSocket, in: InputStream, out: OutputStream): Unit =
    raw.headers.get("sec-websocket-key").flatMap(_.headOption) match
      case None =>
        write(out, Response.text("a WebSocket is asked by an upgrade", 400), head = false, keep = false)
        socket.ended(): Unit
      case Some(key) =>
        val accept = Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-1")
          .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(ISO_8859_1)))
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
          s"Sec-WebSocket-Accept: $accept\r\n\r\n").getBytes(ISO_8859_1))
        out.flush()
        val wire = new cloud.Wire:
          def text(t: String): Unit = HostServer.frame(out, 0x1, t.getBytes(UTF_8))
          def close(code: Int, reason: String): Unit =
            HostServer.frame(out, 0x8, Array((code >> 8).toByte, code.toByte) ++ reason.getBytes(UTF_8))
        socket.bind(wire)
        try HostServer.frames(in, out)(text => Await.result(socket.received(text), 5.minutes))
        finally Await.ready(socket.ended(), 5.minutes): Unit

  // ---- an event stream: its head, then an event a text sent, until the client goes or the object closes it

  private def stream(socket: cloud.HostSocket, r: Response, in: InputStream, out: OutputStream): Unit =
    val b = StringBuilder(s"HTTP/1.1 ${r.status} OK\r\n")
    r.headers.foreach((k, v) => if !hop(k.toLowerCase) then b.append(s"$k: $v\r\n"))
    b.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
    out.write(b.toString.getBytes(UTF_8))
    out.flush()
    val done = java.util.concurrent.CountDownLatch(1)
    socket.bind(new cloud.Wire:
      def text(t: String): Unit = out.synchronized {
        out.write(t.split("\n", -1).map("data: " + _).mkString("", "\n", "\n\n").getBytes(UTF_8))
        out.flush()
      }
      def close(code: Int, reason: String): Unit = done.countDown())
    val reader = Thread.ofVirtual().start(() =>
      try while in.read() >= 0 do () catch case _: java.io.IOException => ()
      done.countDown())
    try done.await()
    finally
      reader.interrupt()
      Await.ready(socket.ended(), 5.minutes): Unit

object HostServer:
  /** a frame out: FIN, the opcode, the length, unmasked (a server's) */
  def frame(out: OutputStream, opcode: Int, payload: Array[Byte]): Unit = out.synchronized {
    out.write(0x80 | opcode)
    val n = payload.length
    if n < 126 then out.write(n)
    else if n < 65536 then { out.write(126); out.write(n >> 8); out.write(n & 0xff) }
    else
      out.write(127)
      (7 to 0 by -1).foreach(i => out.write(((n.toLong >> (8 * i)) & 0xff).toInt))
    out.write(payload)
    out.flush()
  }

  /** frames in until a close (answered) or the end: each whole text — its fragments joined — to `text`; a ping
   * answered by a pong */
  def frames(in: InputStream, out: OutputStream)(text: String => Unit): Unit =
    val message = ByteArrayOutputStream()
    var go = true
    while go do
      val b0 = in.read()
      if b0 < 0 then go = false
      else
        val fin = (b0 & 0x80) != 0
        val opcode = b0 & 0x0f
        val b1 = in.read()
        val masked = (b1 & 0x80) != 0
        var n = (b1 & 0x7f).toLong
        if n == 126 then n = ((in.read() << 8) | in.read()).toLong
        else if n == 127 then n = (0 until 8).foldLeft(0L)((acc, _) => (acc << 8) | in.read())
        val mask = if masked then in.readNBytes(4) else Array.emptyByteArray
        val payload = in.readNBytes(n.toInt)
        if masked then payload.indices.foreach(i => payload(i) = (payload(i) ^ mask(i % 4)).toByte)
        opcode match
          case 0x8 =>
            frame(out, 0x8, payload.take(2))
            go = false
          case 0x9 => frame(out, 0xA, payload)
          case 0xA => ()
          case 0x0 | 0x1 | 0x2 =>
            message.write(payload)
            if fin then
              text(String(message.toByteArray, UTF_8))
              message.reset()
          case _ => go = false
