package okay.serverless.relay

import okay.effects.json.Json
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}

/**
 * A GUEST AT A SEALED ROOM, in a browser (specs/relay.md): a WebSocket to the room its pairing names, at `origin`;
 * every frame sealed and opened here (WebCrypto); a frame not from the host, out of time or seen before, dropped. The
 * room's plain words — the host there or not — to `present`; it dials again when the line drops.
 */
final class Guest(origin: String, pairing: Pairing, got: (String, Json) => Unit, present: Boolean => Unit)(
  using ExecutionContext):
  private val sealer = Sealer(pairing)
  private val seen = Envelope.Seen()
  private var ws: Option[js.Dynamic] = None
  private var backoff = 1000

  def start(): Unit = dial()
  def open: Boolean = ws.exists(_.readyState.asInstanceOf[Int] == 1)
  def close(): Unit = { val w = ws; ws = None; w.foreach(_.close()) }

  /** a sealed frame to the host */
  def send(kind: String, fields: (String, Json)*): Unit =
    val text = Envelope.wrap("guest", js.Date.now().toLong, sealer.nonce, kind, fields*)
    sealer.seal(text).foreach(frame => ws.filter(_.readyState.asInstanceOf[Int] == 1).foreach(_.send(frame)))

  private def dial(): Unit =
    val s = js.Dynamic.newInstance(g.WebSocket)(
      origin.replaceFirst("^http", "ws").stripSuffix("/") + s"/r/${pairing.room}/guest?auth=${pairing.auth}")
    ws = Some(s)
    s.onopen = (_: js.Any) => backoff = 1000
    s.onmessage = (e: js.Dynamic) => e.data.asInstanceOf[Any] match
      case "online" => present(true)
      case "offline" => present(false)
      case "pong" => ()
      case frame: String => sealer.open(frame).foreach(_.foreach(text =>
          Envelope.open(text, "host", js.Date.now().toLong, seen).foreach((kind, j) => got(kind, j))))
      case _ => ()
    s.onclose = (_: js.Any) =>
      if ws.exists(_ eq s) then
        present(false)
        val _ = js.timers.setTimeout(backoff.toDouble)(dial())
        backoff = math.min(backoff * 2, 30000)
    val beat = js.timers.setInterval(30000)(if s.readyState.asInstanceOf[Int] == 1 then s.send("ping"))
    s.addEventListener("close", (_: js.Any) => js.timers.clearInterval(beat))
