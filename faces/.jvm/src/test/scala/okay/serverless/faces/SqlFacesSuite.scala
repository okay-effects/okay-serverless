package okay.serverless.faces

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field
import okay.serverless.store.StoreOn
import okay.store.Dialect

import scala.concurrent.ExecutionContext

/** the same type's REST on SQLite: `create` with a key from the database, `find` by a `Where` as JSON */
class SqlFacesSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  import FacesSuite.*

  object OnSql extends App:
    override def fetch(r: Request): Response ! Cloud =
      StoreOn.sql[CD, Cloud, Response]("shop", Dialect.sqlite)(StoreOn.data[Sale, CS, CD, Response](sales)(face(r)))

  test("REST on SQLite: create (the key the database's), find by a condition sent as JSON") {
    val p = MemoryCloud().withSql(SqliteSql())
    def json(r: Response) = Descent.parse(r.text).getOrElse(Json.Null)
    for
      a <- p.future(OnSql.fetch(Request.post("/sales/create", """{"item":"tea","qty":3}""")))
      _ <- p.future(OnSql.fetch(Request.post("/sales/create", """{"item":"cup","qty":1}""")))
      found <- p.future(OnSql.fetch(Request.post("/sales/find",
        """{"where":{"type":"greater","path":["qty"],"than":2}}""")))
    yield
      assertEquals(json(a).field("id"), Some(Json.Str("000000000001")))
      assertEquals(found.status, 200, found.text)
      assertEquals(json(found) match { case Json.Arr(xs) => xs.flatMap(_.field("item")).toList; case o => List(o) },
        List(Json.Str("tea")))
  }
