package okay.serverless.store

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.store.{Data, Dialect}

import scala.concurrent.ExecutionContext

/** okay-store's effects on SQL through the cloud's `Sql`: SQLite here (D1's dialect); Postgres in `aws`'s tests */
class SqlStoreSuite extends munit.FunSuite:
  import StorePrograms.*
  given ExecutionContext = Async.defaultContext

  type D[X] = (Cloud + Data)[X]
  type P[X] = (D + S)[X]

  test("on SQLite: the program's answers; the documents in a table, the keys in _keys") {
    val p = MemoryCloud().withSql(SqliteSql())
    val db = s"store${System.nanoTime}"
    for
      got <- p.future(StoreOn.sql[D, Cloud, Product](db, Dialect.sqlite)(StoreOn.data[Sale, P, D, Product](sales)(
        program[P])))
      rows <- p.future(Sql.query[Cloud](db, "select key from sales order by key"))
    yield
      assertEquals(got, expected)
      assertEquals(rows.map(_("key")), List(SqlValue.Text("000000000001"), SqlValue.Text("000000000002")))
  }
