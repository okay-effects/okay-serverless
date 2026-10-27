package okay.serverless.faces

import okay.effects.cek.OnCek
import okay.effects.json.{Descent, Json}
import okay.effects.shape.Shape
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field
import okay.serverless.store.{Repo, StoreOn}
import okay.store.{Data, Mapping}

import scala.concurrent.{ExecutionContext, Future}

/** A MAPPED TYPE SERVED BY A WORKER: okay-faces' REST resources and MCP tools of it, its operations on the cloud's
 * store — here the memory cloud's KV */
class FacesSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  import FacesSuite.*

  def json(r: Response): Json = Descent.parse(r.text).getOrElse(Json.Null)
  def rpc(method: String, params: Json = Json.Obj.empty) =
    Request.post("/mcp", Json.print(Json.obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.num(1),
      "method" -> Json.Str(method), "params" -> params)))

  test("REST: the description, a save, a load, a count — on KV") {
    val c = MemoryCloud()
    def call(r: Request): Future[Response] = c.platform.future(OnKv.fetch(r))
    for
      described <- call(Request.get("/sales"))
      saved <- call(Request.post("/sales/save", """{"id":"a","item":"tea","qty":3}"""))
      _ <- call(Request.post("/sales/save", """{"id":"b","item":"cup","qty":1}"""))
      loaded <- call(Request.get("/sales/load?key=a"))
      counted <- call(Request.post("/sales/count", "{}"))
      bad <- call(Request.post("/sales/save", """{"id":"c","item":"pot"}"""))
    yield
      val ops = json(described).field("data").flatMap(_.field("operations"))
        .collect { case Json.Arr(xs) => xs.flatMap(_.field("name")) }
      assertEquals(ops.map(_.toList), Some(List("save", "load", "remove", "find", "count", "create").map(Json.Str(_))))
      assertEquals(saved.status, 200)
      assertEquals(json(loaded).field("item"), Some(Json.Str("tea")))
      assertEquals(json(counted), Json.num(2))
      assertEquals(bad.status, 400) // qty missing: the shape says so, before the store is touched
  }

  test("MCP over HTTP: initialize, the tools, a call — the same type, the same store") {
    val c = MemoryCloud()
    def call(r: Request): Future[Response] = c.platform.future(OnKv.fetch(r))
    for
      init <- call(rpc("initialize"))
      tools <- call(rpc("tools/list"))
      _ <- call(rpc("tools/call", Json.obj("name" -> Json.Str("sales_save"),
        "arguments" -> Json.obj("id" -> Json.Str("x"), "item" -> Json.Str("tea"), "qty" -> Json.num(2)))))
      loaded <- call(Request.get("/sales/load?key=x"))
      notified <- call(Request.post("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    yield
      assertEquals(json(init).field("result").flatMap(_.field("serverInfo")).flatMap(_.field("name")),
        Some(Json.Str("shop")))
      val names = json(tools).field("result").flatMap(_.field("tools")).collect { case Json.Arr(xs) =>
        xs.flatMap(_.field("name")).toList }
      assert(names.exists(_.contains(Json.Str("sales_save"))), names.toString)
      assertEquals(json(loaded).field("qty"), Some(Json.num(2)), "saved through MCP, read through REST")
      assertEquals(notified.status, 202)
  }

object FacesSuite:
  final case class Sale(id: String, item: String, qty: Int)
  val sales: Mapping[Sale] = Mapping.keyed("sales", Shape.derived[Sale])(_.id)

  type CD[X] = (Cloud + Data)[X]
  type CS[X] = (CD + Repo.Of[Sale])[X]
  val rest = Faces.rest(Mapping.describe(sales), "/sales")
  val mcp = Faces.mcp(Mapping.describe(sales), "shop")
  /** the face a request is for, put into the cloud's row */
  def face(r: Request): Response ! CS = OnCek.into[CS](if r.path == "/mcp" then mcp(r) else rest(r))

  /** the type on KV */
  object OnKv extends App:
    override def fetch(r: Request): Response ! Cloud =
      StoreOn.kv[CD, Cloud, Response]("docs")(StoreOn.data[Sale, CS, CD, Response](sales)(face(r)))
