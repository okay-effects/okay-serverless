package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.*

import scala.concurrent.ExecutionContext

/** SQL on SQLite, through the cloud's effect: a table, rows, a batch all or nothing */
class SqliteSqlSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext

  test("query, run, and a batch rolled back whole when one statement fails") {
    val p = MemoryCloud().withSql(SqliteSql())
    val db = s"t${System.nanoTime}"
    for
      out <- p.future(for
        _ <- Sql.run[Cloud](db, "create table notes (id integer primary key, text text not null, score real)")
        a <- Sql.run[Cloud](db, "insert into notes (text, score) values (?, ?)", "one", 1.5)
        _ <- Sql.run[Cloud](db, "insert into notes (text, score) values (?, ?)", "two", 2.5)
        rows <- Sql.query[Cloud](db, "select id, text, score from notes where score > ? order by id", 1.0)
      yield (a, rows))
      failed <- p.future(Sql.batch[Cloud](db, Statement("insert into notes (text) values ('three')"),
        Statement("insert into notes (text) values (null)"))).failed
      count <- p.future(Sql.query[Cloud](db, "select count(*) as n from notes"))
    yield
      assertEquals(out._1, Changes(1, 1))
      assertEquals(out._2.map(r => (r("id"), r("text"))), List(SqlValue.Int(1) -> SqlValue.Text("one"),
        SqlValue.Int(2) -> SqlValue.Text("two")))
      assert(failed.getMessage.contains("NOT NULL"), failed.getMessage)
      assertEquals(count, List(Map("n" -> SqlValue.Int(2))))
  }
