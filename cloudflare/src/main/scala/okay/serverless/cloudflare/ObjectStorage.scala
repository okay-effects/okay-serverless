package okay.serverless.cloudflare

import okay.scala3.*
import okay.serverless.cloud.Port
import okay.serverless.{Storage, Versioned}

import scala.concurrent.Future
import scala.scalajs.js

/**
 * `Storage` ON A DURABLE OBJECT: its SQLite storage (`ctx.storage.sql`, synchronous), one table of values with their
 * versions; a commit one `transactionSync` — atomic, and, the object being one thread, never in conflict (the
 * versions are checked all the same, as the contract says); the alarm the object's (`ctx.storage.setAlarm`, kept
 * by the object's output gate before its answer leaves).
 */
final class ObjectStorage(ctx: js.Dynamic):
  private val storage = ctx.storage
  private val sql = storage.sql
  sql.exec("CREATE TABLE IF NOT EXISTS okay_values " +
    "(key TEXT PRIMARY KEY, value TEXT NOT NULL, version INTEGER NOT NULL)")

  private def rows(query: String, args: js.Any*): js.Array[js.Dynamic] =
    sql.exec.applyDynamic("call")((sql: js.Any) +: (query: js.Any) +: args*).toArray()
      .asInstanceOf[js.Array[js.Dynamic]]

  def read(keys: List[String]): Map[String, Versioned] =
    keys.flatMap { k =>
      rows("SELECT value, version FROM okay_values WHERE key = ?", k).headOption
        .map(r => k -> Versioned(r.value.asInstanceOf[String], r.version.asInstanceOf[Double].toLong))
    }.toMap

  def keys(prefix: String): List[String] =
    rows("SELECT key FROM okay_values WHERE substr(key, 1, ?) = ? ORDER BY key", prefix.length, prefix)
      .map(_.key.asInstanceOf[String]).toList

  def commit(read: Map[String, Long], writes: Map[String, Option[String]], alarm: Option[Long]): Boolean =
    val ok = storage.transactionSync((() => {
      val current = this.read(read.keys.toList)
      val same = read.forall((k, v) => current.get(k).fold(0L)(_.version) == v)
      if same then
        for (k, w) <- writes do w match
          case Some(v) =>
            sql.exec("INSERT INTO okay_values (key, value, version) VALUES (?, ?, 1) " +
              "ON CONFLICT(key) DO UPDATE SET value = excluded.value, version = okay_values.version + 1", k, v)
          case None => sql.exec("DELETE FROM okay_values WHERE key = ?", k)
      same
    }): js.Function0[Boolean]).asInstanceOf[Boolean]
    if ok then alarm.foreach(at => storage.setAlarm(at.toDouble))
    ok

  /** `Storage` on this object, as a port */
  val port: Port[Storage] = new Port[Storage]:
    def apply[X](op: Storage[X]): Future[X] = Future.fromTry(scala.util.Try[X](op match
      case Storage.Read(keys) => read(keys)
      case Storage.Keys(prefix) => keys(prefix)
      case Storage.Commit(r, w, a) => commit(r, w, a)))

  /** the handler of `Storage` on this object */
  def handler[R[_], Rest[_], A](p: A ! R)(using Split[Storage, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Storage, Rest, A, A](pure)([X] => (op: Storage[X]) => op match
      case Storage.Read(keys) => read(keys)
      case Storage.Keys(prefix) => keys(prefix)
      case Storage.Commit(r, w, a) => commit(r, w, a)))
