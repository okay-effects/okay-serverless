package okay.serverless.relay

import okay.scala3.*
import okay.serverless.cloud.*

/** WHAT AN APP SAYS IN ITS ROOMS: the host's tags beyond its role (from its request: a bound chat, checked), its
 * control lines (`ctl:` taken off), and the requests to a room that are not a socket's */
trait Steward:
  def hostTags(room: String, request: Request): List[String] ! InActor = pure(Nil)
  def control(room: String, host: Socket, line: String): Unit ! InActor = pure(())
  def call(room: String, request: Request): Option[Response ! InActor] = None

object Steward:
  val none: Steward = new Steward {}

/**
 * A SEALED ROOM (specs/relay.md): an actor, one a room. One host (the newest), any number of guests, let in only with
 * the room's auth; a guest's frame to the host, the host's to every guest — frames it cannot read; the guests told
 * whether the host is there. The host's control lines to the steward, never passed. Nothing in storage.
 */
final class Rooms(steward: Steward = Steward.none, most: Int = 2800000) extends Actor:
  private def role(s: Socket) = s.tags.find(t => t == "host" || t == "guest").getOrElse("")

  def fetch(room: String, request: Request): Response ! InActor =
    steward.call(room, request).getOrElse {
      val who = request.path.takeWhile(_ != '?').split('/').lastOption.getOrElse("")
      val auth = request.param("auth").getOrElse("")
      if !request.header("upgrade").exists(_.equalsIgnoreCase("websocket")) then pure(Response.text("a WebSocket", 426))
      else if who != "host" && who != "guest" then pure(Response.text("no such side", 404))
      else if auth.isEmpty || !Hashes.same(Pairing.roomOf(auth), room) then pure(Response.text("not this room's", 403))
      else if who == "host" then
        for
          older <- Sockets.open[InActor](Some("host"))
          tags <- steward.hostTags(room, request)
          answer <- Sockets.accept[InActor](request, ("host" :: tags)*)
          _ <- older.foldLeft(pure[Unit, InActor](()))((p, o) =>
            p.andThen(Sockets.close[InActor](o, 4000, "another host took this room")))
          _ <- Sockets.broadcast[InActor]("online", Some("guest"))
        yield answer
      else
        for
          answer <- Sockets.accept[InActor](request, "guest")
          hosts <- Sockets.open[InActor](Some("host"))
          _ <- Sockets.of(answer).fold(pure[Unit, InActor](()))(s =>
            Sockets.send[InActor](s, if hosts.nonEmpty then "online" else "offline"))
        yield answer
    }

  override def message(room: String, socket: Socket, text: String): Unit ! InActor =
    if text == "ping" then Sockets.send[InActor](socket, "pong")
    else if text.length > most then Sockets.close[InActor](socket, 1009, "at most 2.8 MB")
    else role(socket) match
      case "host" =>
        if text.startsWith("ctl:") then steward.control(room, socket, text.drop(4))
        else Sockets.broadcast[InActor](text, Some("guest")).as(())
      case "guest" => Sockets.open[InActor](Some("host")).flatMap { hosts =>
          if hosts.isEmpty then Sockets.send[InActor](socket, "offline")
          else if text.startsWith("ctl:") then pure(())
          else hosts.foldLeft(pure[Unit, InActor](()))((p, h) => p.andThen(Sockets.send[InActor](h, text)))
        }
      case _ => pure(())

  override def closed(room: String, socket: Socket): Unit ! InActor =
    if role(socket) != "host" then pure(())
    else Sockets.open[InActor](Some("host")).flatMap(hosts =>
      if hosts.exists(_.id != socket.id) then pure(())
      else Sockets.broadcast[InActor]("offline", Some("guest")).as(()))

object Rooms:
  /** a request for a room, as an app routes it to its object (`objectFor`): `/r/<room>/<side>` */
  def route(request: Request, namespace: String): Option[(String, String)] =
    request.path.takeWhile(_ != '?').split('/').toList.drop(1) match
      case List("r", room, side) if room.matches("[0-9a-f]{64}") && (side == "host" || side == "guest") =>
        Some((namespace, room))
      case _ => None
