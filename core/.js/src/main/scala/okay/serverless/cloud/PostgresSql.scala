package okay.serverless.cloud

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}
import scala.scalajs.js.JSConverters.*

/**
 * SQL ON POSTGRES by node-postgres (`pg`, given: a Worker imports it, a Lambda requires it) — a database by its name
 * at the URL `url` gives (Hyperdrive's `connectionString` on Cloudflare; RDS Proxy's on AWS). `?` parameters numbered;
 * a batch one transaction on one connection. `pooled`: a pool a URL, kept (a Lambda); else a connection a call, as a
 * Worker must (Hyperdrive pools them). Values: integers and floats numbers, `int8`/`numeric` text (as `pg` gives
 * them), booleans 1 and 0, `bytea` bytes, times ISO text, JSON its text. A run's `lastRowId` 0: Postgres says it by
 * `RETURNING`.
 */
final class PostgresSql(pg: js.Dynamic, url: String => Option[String], pooled: Boolean = false)(
  using ExecutionContext) extends Port[Sql]:
  import WebJs.{await, bytes, uint8}

  private val pools = scala.collection.mutable.Map.empty[String, js.Dynamic]

  /** a connection to `db`, and how to give it back */
  private def connect(db: String): Future[(js.Dynamic, () => Unit)] =
    url(db) match
      case None => Future.failed(java.util.NoSuchElementException(s"no Postgres for $db"))
      case Some(u) if pooled =>
        val pool = pools.getOrElseUpdate(u, js.Dynamic.newInstance(pg.Pool)(obj(connectionString = u)))
        await(pool.connect()).map(c => (c, () => { c.release(); () }))
      case Some(u) =>
        val c = js.Dynamic.newInstance(pg.Client)(obj(connectionString = u))
        await(c.connect()).map(_ => (c, () => { c.end(); () }))

  private def param(v: SqlValue): js.Any = v match
    case SqlValue.Null => null
    case SqlValue.Int(n) => if math.abs(n) < (1L << 53) then n.toDouble else n.toString
    case SqlValue.Real(d) => d
    case SqlValue.Text(t) => t
    case SqlValue.Blob(b) => g.Buffer.from(uint8(b))

  private def value(d: js.Dynamic): SqlValue =
    if d == null || js.isUndefined(d) then SqlValue.Null
    else js.typeOf(d) match
      case "number" =>
        val n = d.asInstanceOf[Double]
        if n.isWhole && math.abs(n) < 9.007199254740992e15 then SqlValue.Int(n.toLong) else SqlValue.Real(n)
      case "string" => SqlValue.Text(d.asInstanceOf[String])
      case "boolean" => SqlValue.Int(if d.asInstanceOf[Boolean] then 1 else 0)
      case "bigint" => SqlValue.Text(d.toString)
      case _ =>
        if g.Buffer.isBuffer(d).asInstanceOf[Boolean] then SqlValue.Blob(bytes(d.buffer.slice(d.byteOffset,
          d.byteOffset.asInstanceOf[Double] + d.byteLength.asInstanceOf[Double])))
        else if d.isInstanceOf[js.Date] then SqlValue.Text(d.toISOString().asInstanceOf[String])
        else SqlValue.Text(g.JSON.stringify(d).asInstanceOf[String])

  private def run(c: js.Dynamic, s: Statement): Future[js.Dynamic] =
    await(c.query(Sql.numbered(s.sql), s.params.map(param).toJSArray))

  private def rows(r: js.Dynamic): List[Row] =
    r.rows.asInstanceOf[js.Array[js.Dictionary[js.Dynamic]]].toList.map(_.toMap.map((k, v) => k -> value(v)))

  private def using[A](db: String)(f: js.Dynamic => Future[A]): Future[A] =
    connect(db).flatMap { (c, release) =>
      f(c).transform { t =>
        release()
        t
      }
    }

  def apply[X](op: Sql[X]): Future[X] = op match
    case Sql.Query(db, s) => using(db)(c => run(c, s).map(rows))
    case Sql.Run(db, s) => using(db)(c => run(c, s).map(r =>
      Changes(if r.rowCount == null then 0L else r.rowCount.asInstanceOf[Double].toLong, 0L)))
    case Sql.Batch(db, ss) => using(db) { c =>
        val all = ss.foldLeft(await(c.query("BEGIN")).map(_ => List.empty[List[Row]]))((f, s) =>
          f.flatMap(done => run(c, s).map(r => rows(r) :: done)))
        all.flatMap(done => await(c.query("COMMIT")).map(_ => done.reverse)).recoverWith { case e =>
          await(c.query("ROLLBACK")).transformWith(_ => Future.failed(e))
        }
      }
