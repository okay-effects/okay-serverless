package okay.serverless.relay

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, Executors, TimeUnit}
import okay.effects.json.Json

/**
 * THE HOST AT A SEALED ROOM, on the JVM (specs/relay.md): it dials out (no port open on its machine), keeps the line,
 * dials again when it drops — unless another host with this pairing took the room (4000), which is then that one's.
 * Every frame sealed and opened here; a frame not from a guest, out of time or seen before, dropped. The room's own
 * lines (`ctl:`) plain. Everything given to the callbacks by `deliver` (the caller's thread: its loop).
 */
final class RelayClient(base: String, pairing: Pairing, deliver: Runnable => Unit,
                        got: (String, Json) => Unit, control: String => Unit = _ => (),
                        connected: () => Unit = () => (), said: String => Unit = _ => (),
                        query: () => String = () => ""):
  private val client = HttpClient.newHttpClient()
  private val sealer = Sealer(pairing)
  private val seen = Envelope.Seen()
  private val timer = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "okay-relay-timer"); t.setDaemon(true); t
  }
  @volatile private var socket: Option[WebSocket] = None
  private var sending: CompletableFuture[WebSocket] = CompletableFuture.completedFuture(null)
  private var backoff = 1000
  @volatile private var up = false
  @volatile private var stopped = false
  private var held: Option[String] = None

  private def later(ms: Long)(f: => Unit): Unit = { val _ = timer.schedule((() => deliver(() => f)): Runnable, ms,
    TimeUnit.MILLISECONDS) }
  private def url: String = base.stripSuffix("/").replaceFirst("^http", "ws") +
    s"/r/${pairing.room}/host?auth=${pairing.auth}" + Option(query()).filter(_.nonEmpty).fold("")("&" + _)

  def start(): Unit = dial()
  def isUp: Boolean = up

  /** a sealed frame to the guests, in order */
  def send(kind: String, fields: (String, Json)*): Unit =
    write(sealer.seal(Envelope.wrap("host", System.currentTimeMillis(), sealer.nonce, kind, fields*)))
  /** a control line to the room itself — never sealed, never passed to a guest; with no line, `hold` keeps the
   * newest such for when there is one (a notification), others are dropped */
  def plain(line: String, hold: Boolean = false): Unit =
    if socket.isEmpty && hold then held = Some(line) else write("ctl:" + line)
  /** dial again now (its query asked again: what the room should know of this host has changed) */
  def redial(): Unit = socket.foreach { ws => socket = None; ws.sendClose(1000, "again"); dial() }

  private def write(text: String): Unit = socket.foreach { ws =>
    synchronized { sending = sending.handle((_, _) => ()).thenCompose(_ => ws.sendText(text, true)) }
  }

  private def dial(): Unit = if !stopped then
    client.newWebSocketBuilder().buildAsync(URI.create(url), listener).whenComplete { (ws, e) =>
      if e != null then deliver(() => dropped(s"not reached: ${Option(e.getCause).getOrElse(e).getMessage}"))
      else
        socket = Some(ws)
        deliver { () =>
          backoff = 1000
          if !up then said("the relay is reached")
          up = true
          connected()
          held.foreach(l => write("ctl:" + l)); held = None
        }
        beat(ws)
    }

  /** a word now and then, so nothing on the way takes the line for idle */
  private def beat(ws: WebSocket): Unit = later(30000)(if socket.exists(_ eq ws) then { write("ping"); beat(ws) })

  private def dropped(why: String): Unit =
    socket = None
    if up then said(s"the relay is lost ($why); dialling again")
    up = false
    later(backoff)(dial())
    backoff = math.min(backoff * 2, 30000)

  private val listener = new WebSocket.Listener:
    private val buf = new StringBuilder
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      buf.append(data)
      if last then
        val frame = buf.toString; buf.clear()
        if frame.startsWith("ctl:") then deliver(() => control(frame.drop(4)))
        else if frame != "pong" && frame.length < 3000000 then
          sealer.open(frame).foreach(text => deliver(() =>
            Envelope.open(text, "guest", System.currentTimeMillis(), seen).foreach((kind, j) => got(kind, j))))
      ws.request(1)
      null
    override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
      deliver(() => if socket.exists(_ eq ws) then
        if code == 4000 then
          socket = None; up = false; stopped = true
          said("another host with this pairing took the relay: this one stops using it")
        else dropped(s"closed $code $reason"))
      null
    override def onError(ws: WebSocket, e: Throwable): Unit =
      deliver(() => if socket.exists(_ eq ws) then dropped(Option(e.getMessage).getOrElse(e.toString)))
