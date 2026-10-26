package okay.serverless.cloud

import okay.effects.json.Json

/** bytes, compared by content: a blob's, a body's */
final class Bytes private (private val a: Array[Byte]):
  def size: Int = a.length
  def toArray: Array[Byte] = a.clone()
  def text: String = String(a, "UTF-8")
  def slice(from: Int, until: Int): Bytes = Bytes(a.slice(from, until))
  override def equals(o: Any): Boolean = o match
    case b: Bytes => java.util.Arrays.equals(a, b.a)
    case _ => false
  override def hashCode: Int = java.util.Arrays.hashCode(a)
  override def toString: String = if a.length <= 64 then s"Bytes($text)" else s"Bytes(${a.length})"

object Bytes:
  val empty: Bytes = Bytes(Array.emptyByteArray)
  def apply(a: Array[Byte]): Bytes = new Bytes(a.clone())
  def of(text: String): Bytes = new Bytes(text.getBytes("UTF-8"))

/** an HTTP request: in to an app or an actor, out by `Fetch`, to another service */
final case class Request(method: String, url: String, headers: Map[String, String] = Map.empty,
                         body: Bytes = Bytes.empty):
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
  def path: String =
    val rest = url.indexOf("://") match
      case -1 => url
      case i => url.indexOf('/', i + 3) match
        case -1 => "/"
        case j => url.substring(j)
    rest.takeWhile(_ != '?')
  def query: Map[String, String] = url.indexOf('?') match
    case -1 => Map.empty
    case i => url.substring(i + 1).split('&').toList.filter(_.nonEmpty).map { kv =>
      kv.split("=", 2) match
        case Array(k, v) => decode(k) -> decode(v)
        case Array(k) => decode(k) -> ""
    }.toMap
  private def decode(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

object Request:
  def get(url: String): Request = Request("GET", url)
  def post(url: String, body: String, headers: Map[String, String] = Map.empty): Request =
    Request("POST", url, headers, Bytes.of(body))

/** an HTTP response */
final case class Response(status: Int, headers: Map[String, String] = Map.empty, body: Bytes = Bytes.empty):
  def text: String = body.text
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

object Response:
  def text(s: String, status: Int = 200): Response =
    Response(status, Map("content-type" -> "text/plain; charset=utf-8"), Bytes.of(s))
  def json(j: Json, status: Int = 200): Response =
    Response(status, Map("content-type" -> "application/json"), Bytes.of(Json.print(j)))
  val notFound: Response = text("not found", 404)
