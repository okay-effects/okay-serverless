package okay.serverless.cloud

import okay.scala3.std.*

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

/** the old names (0.1.0: in the core, this package) still compile and are the moved handlers */
@nowarn("cat=deprecation")
class MovedSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext

  test("JdkFetch, JdkTcp, SqliteSql by their old names: okay.serverless.jvm's"):
    val fetch: JdkFetch = JdkFetch(1000)
    val sql: SqliteSql = SqliteSql()
    val tcp: JdkTcp = JdkTcp()
    assert(fetch.isInstanceOf[okay.serverless.jvm.JdkFetch])
    assert(sql.isInstanceOf[okay.serverless.jvm.SqliteSql])
    assert(tcp.port ne null)
    assertEquals(new SqliteSql(None).getClass, classOf[okay.serverless.jvm.SqliteSql])
