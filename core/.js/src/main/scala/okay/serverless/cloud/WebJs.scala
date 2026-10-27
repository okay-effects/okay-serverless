package okay.serverless.cloud

import okay.effects.json.{Descent, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.{AB2TA, ArrayBuffer, Int8Array, Uint8Array}

/** between Scala's values and a JavaScript runtime's of the web's API — Workers, Node, Deno */
object WebJs:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  def await(p: js.Dynamic): Future[js.Dynamic] = p.asInstanceOf[js.Promise[js.Dynamic]].toFuture
  def bytes(buffer: js.Dynamic): Bytes = Bytes(new Int8Array(buffer.asInstanceOf[ArrayBuffer]).toArray)
  def uint8(b: Bytes): Uint8Array = new Uint8Array(b.toArray.toTypedArray.buffer)
  def str(d: js.Dynamic): Option[String] = if js.isUndefined(d) || d == null then None else Some(d.toString)
  def toJson(d: js.Any): Json = Descent.parse(g.JSON.stringify(d).asInstanceOf[String]).getOrElse(Json.Null)
  def fromJson(j: Json): js.Dynamic = g.JSON.parse(Json.print(j))
  def headers(h: js.Dynamic): Map[String, String] =
    val out = scala.collection.mutable.Map.empty[String, String]
    h.forEach(((v: String, k: String) => out(k) = v): js.Function2[String, String, Unit])
    out.toMap

  /** a request of the runtime's: its path and query, its headers (names lowercased), its body as it came */
  def request(r: js.Dynamic): Future[Request] =
    val method = r.method.asInstanceOf[String]
    val url = js.Dynamic.newInstance(g.URL)(r.url)
    val head = Request(method, url.pathname.toString + url.search.toString)
      .copy(headers = headers(r.headers).map((k, v) => k.toLowerCase -> List(v)))
    if method == "GET" || method == "HEAD" then Future.successful(head)
    else await(r.arrayBuffer()).map(b => head.withContent(bytes(b)))

  /** a request to `base`: the runtime's */
  def toRequest(base: String, r: Request): js.Dynamic =
    val init = obj(method = r.method, headers = r.headers.map((k, vs) => k -> vs.mkString(", ")).toJSDictionary)
    if r.bytes.isDefined || r.body.nonEmpty then init.body = uint8(r.content)
    js.Dynamic.newInstance(g.Request)(okay.http.Fetch.target(base, r), init)

  def response(r: js.Dynamic): Future[Response] =
    await(r.arrayBuffer()).map(b => Response(r.status.asInstanceOf[Int], headers(r.headers)).withContent(bytes(b)))

  def toResponse(r: Response): js.Dynamic =
    val body = r.content
    js.Dynamic.newInstance(g.Response)(if body.size == 0 then null else uint8(body),
      obj(status = r.status, headers = r.headers.toJSDictionary))

  /** `fetch`, the runtime's */
  val fetch: Port[Fetch] = new Port[Fetch]:
    def apply[X](op: Fetch[X]): Future[X] = op match
      case okay.http.Fetch.Send(base, r, answer) => await(g.fetch(toRequest(base, r))).flatMap(response).map(answer)
      case okay.http.Fetch.Call(m, url, h, body, answer) =>
        await(g.fetch(toRequest(okay.http.Fetch.base(url), okay.http.Fetch.request(m, url, h, body))))
          .flatMap(response).map(answer)
