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

  def request(r: js.Dynamic): Future[Request] =
    val method = r.method.asInstanceOf[String]
    val body = if method == "GET" || method == "HEAD" then Future.successful(Bytes.empty)
      else await(r.arrayBuffer()).map(bytes)
    body.map(b => Request(method, r.url.asInstanceOf[String], headers(r.headers), b))

  def toRequest(r: Request): js.Dynamic =
    val init = obj(method = r.method, headers = r.headers.toJSDictionary)
    if r.body.size > 0 then init.body = uint8(r.body)
    js.Dynamic.newInstance(g.Request)(r.url, init)

  def response(r: js.Dynamic): Future[Response] =
    await(r.arrayBuffer()).map(b => Response(r.status.asInstanceOf[Int], headers(r.headers), bytes(b)))

  def toResponse(r: Response): js.Dynamic =
    js.Dynamic.newInstance(g.Response)(if r.body.size == 0 then null else uint8(r.body),
      obj(status = r.status, headers = r.headers.toJSDictionary))

  /** `fetch`, the runtime's */
  val fetch: Port[Fetch] = new Port[Fetch]:
    def apply[X](op: Fetch[X]): Future[X] = op match
      case Fetch.Call(r) => await(g.fetch(toRequest(r))).flatMap(response)
