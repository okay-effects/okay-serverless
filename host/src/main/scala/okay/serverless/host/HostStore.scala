package okay.serverless.host

import okay.serverless.Versioned

import java.nio.file.{Files, Path}
import java.sql.{Connection, PreparedStatement, ResultSet}
import scala.collection.mutable

/**
 * THE HOST'S STATE IN ONE SQLITE FILE (specs/cloud.md "The host"): KV with its expiry, each object's values with
 * their versions and its alarm, the queues and the messages given up, vectors. One connection, one writer at a time
 * (SQLite's own rule); a commit of an object's step one transaction.
 */
final class HostStore(file: Path):
  Files.createDirectories(file.toAbsolutePath.getParent)
  private val c: Connection = org.sqlite.JDBC().connect(s"jdbc:sqlite:$file", java.util.Properties())

  locally {
    val s = c.createStatement()
    try
      s.execute("pragma journal_mode=wal")
      s.execute("create table if not exists kv (ns text not null, key text not null, value text not null, " +
        "meta text, expires integer, primary key (ns, key))")
      s.execute("create table if not exists object_values (ns text not null, name text not null, key text not null, " +
        "value text not null, version integer not null, primary key (ns, name, key))")
      s.execute("create table if not exists object_alarms (ns text not null, name text not null, " +
        "at integer not null, primary key (ns, name))")
      s.execute("create table if not exists queue (id integer primary key autoincrement, queue text not null, " +
        "body text not null, attempts integer not null, visible_at integer not null, ts integer not null)")
      s.execute("create table if not exists dead (id integer not null, queue text not null, body text not null, " +
        "attempts integer not null)")
      s.execute("create table if not exists vectors (idx text not null, id text not null, vals text not null, " +
        "meta text not null, primary key (idx, id))")
    finally s.close()
  }

  private def prepared(sql: String, params: Any*): PreparedStatement =
    val ps = c.prepareStatement(sql)
    params.zipWithIndex.foreach {
      case (null, i) => ps.setNull(i + 1, java.sql.Types.NULL)
      case (None, i) => ps.setNull(i + 1, java.sql.Types.NULL)
      case (Some(v), i) => ps.setObject(i + 1, v)
      case (v: Long, i) => ps.setLong(i + 1, v)
      case (v: Int, i) => ps.setLong(i + 1, v.toLong)
      case (v, i) => ps.setObject(i + 1, v)
    }
    ps

  private def query[A](sql: String, params: Any*)(row: ResultSet => A): Vector[A] = synchronized {
    val ps = prepared(sql, params*)
    try
      val rs = ps.executeQuery()
      val out = Vector.newBuilder[A]
      while rs.next() do out += row(rs)
      out.result()
    finally ps.close()
  }

  private def update(sql: String, params: Any*): Int = synchronized {
    val ps = prepared(sql, params*)
    try ps.executeUpdate() finally ps.close()
  }

  private def transaction[A](f: => A): A = synchronized {
    c.setAutoCommit(false)
    try
      val a = f
      c.commit()
      a
    catch case e: Throwable =>
      c.rollback()
      throw e
    finally c.setAutoCommit(true)
  }

  // ---- KV

  def kvGet(ns: String, key: String, now: Long): Option[(String, Option[String])] =
    query("select value, meta, expires from kv where ns = ? and key = ?", ns, key)(rs =>
      (rs.getString(1), Option(rs.getString(2)), Option(rs.getObject(3)).map(_ => rs.getLong(3))))
      .collectFirst { case (v, m, exp) if exp.forall(_ > now) => (v, m) }

  def kvPut(ns: String, key: String, value: String, meta: Option[String], expires: Option[Long]): Unit =
    update("insert into kv (ns, key, value, meta, expires) values (?, ?, ?, ?, ?) on conflict (ns, key) do update " +
      "set value = excluded.value, meta = excluded.meta, expires = excluded.expires",
      ns, key, value, meta, expires): Unit

  def kvDelete(ns: String, key: String): Unit = update("delete from kv where ns = ? and key = ?", ns, key): Unit

  /** the live keys under a prefix, after a cursor, in order: at most `limit`, and whether there are more */
  def kvList(ns: String, prefix: String, after: Option[String], limit: Int, now: Long): (Vector[String], Boolean) =
    val keys = query("select key from kv where ns = ? and key >= ? and key > ? and (expires is null or expires > ?) " +
      "order by key limit ?", ns, prefix, after.getOrElse(""), now, limit + 1)(_.getString(1))
      .filter(_.startsWith(prefix))
    (keys.take(limit), keys.size > limit)

  // ---- objects: values with versions, an alarm

  def read(ns: String, name: String, keys: List[String]): Map[String, Versioned] =
    if keys.isEmpty then Map.empty
    else query(s"select key, value, version from object_values where ns = ? and name = ? and key in " +
      keys.map(_ => "?").mkString("(", ", ", ")"), (ns :: name :: keys)*)(rs =>
      rs.getString(1) -> Versioned(rs.getString(2), rs.getLong(3))).toMap

  def keys(ns: String, name: String, prefix: String): List[String] =
    query("select key from object_values where ns = ? and name = ? and key >= ? order by key", ns, name, prefix)(
      _.getString(1)).filter(_.startsWith(prefix)).toList

  /** a step's commit: if what it read is unchanged, its writes and its alarm (the earlier of two) — or nothing */
  def commit(ns: String, name: String, read: Map[String, Long], writes: Map[String, Option[String]],
             alarm: Option[Long]): Boolean = transaction {
    val now = this.read(ns, name, read.keys.toList)
    val same = read.forall((k, v) => now.get(k).fold(0L)(_.version) == v)
    if same then
      val versions = this.read(ns, name, writes.keys.toList)
      for (k, w) <- writes do w match
        case Some(v) => update("insert into object_values (ns, name, key, value, version) values (?, ?, ?, ?, ?) " +
            "on conflict (ns, name, key) do update set value = excluded.value, version = excluded.version",
            ns, name, k, v, versions.get(k).fold(1L)(_.version + 1))
        case None => update("delete from object_values where ns = ? and name = ? and key = ?", ns, name, k)
      alarm.foreach(a => update("insert into object_alarms (ns, name, at) values (?, ?, ?) on conflict (ns, name) " +
        "do update set at = min(at, excluded.at)", ns, name, a))
    same
  }

  /** the objects whose alarm is due, each alarm cleared (as a cloud clears it before running it) */
  def dueAlarms(now: Long): Vector[(String, String)] = transaction {
    val due = query("select ns, name from object_alarms where at <= ?", now)(rs => (rs.getString(1), rs.getString(2)))
    due.foreach((ns, name) => update("delete from object_alarms where ns = ? and name = ?", ns, name))
    due
  }

  def alarm(ns: String, name: String): Option[Long] =
    query("select at from object_alarms where ns = ? and name = ?", ns, name)(_.getLong(1)).headOption

  // ---- queues

  def send(queue: String, body: String, visibleAt: Long, now: Long): Unit =
    update("insert into queue (queue, body, attempts, visible_at, ts) values (?, ?, 0, ?, ?)", queue, body,
      visibleAt, now): Unit

  /** the messages due, taken off the queue: (id, queue, body, attempts, timestamp) */
  def due(now: Long): Vector[(Long, String, String, Int, Long)] = transaction {
    val d = query("select id, queue, body, attempts, ts from queue where visible_at <= ? order by id", now)(rs =>
      (rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5)))
    d.foreach(m => update("delete from queue where id = ?", m._1))
    d
  }

  def retry(id: Long, queue: String, body: String, attempts: Int, visibleAt: Long, ts: Long): Unit =
    update("insert into queue (id, queue, body, attempts, visible_at, ts) values (?, ?, ?, ?, ?, ?)", id, queue, body,
      attempts, visibleAt, ts): Unit

  def bury(id: Long, queue: String, body: String, attempts: Int): Unit =
    update("insert into dead (id, queue, body, attempts) values (?, ?, ?, ?)", id, queue, body, attempts): Unit

  def pending: Int = query("select count(*) from queue")(_.getInt(1)).head
  def dead: Vector[(String, String)] = query("select queue, body from dead order by id")(rs =>
    (rs.getString(1), rs.getString(2)))

  // ---- vectors

  def upsert(index: String, id: String, values: String, meta: String): Unit =
    update("insert into vectors (idx, id, vals, meta) values (?, ?, ?, ?) on conflict (idx, id) do update " +
      "set vals = excluded.vals, meta = excluded.meta", index, id, values, meta): Unit

  def removeVector(index: String, id: String): Unit = update("delete from vectors where idx = ? and id = ?", index, id)
    : Unit

  def vectors(index: String): Vector[(String, String, String)] =
    query("select id, vals, meta from vectors where idx = ?", index)(rs =>
      (rs.getString(1), rs.getString(2), rs.getString(3)))

  def close(): Unit = synchronized(c.close())

object HostStore:
  /** a table of names to text, as the store writes metadata: one line `k=v` each, escaped */
  def lines(m: Map[String, String]): String =
    m.toVector.sortBy(_._1).map((k, v) => s"${esc(k)}=${esc(v)}").mkString("\n")
  def unlines(s: String): Map[String, String] =
    s.split('\n').toVector.filter(_.nonEmpty).flatMap { l =>
      val i = l.indexOf('=')
      Option.when(i > 0)(unesc(l.substring(0, i)) -> unesc(l.substring(i + 1)))
    }.toMap
  private def esc(s: String): String = s.flatMap {
    case '\\' => "\\\\"; case '\n' => "\\n"; case '=' => "\\e"; case c => c.toString
  }
  private def unesc(s: String): String =
    val b = mutable.StringBuilder()
    var i = 0
    while i < s.length do
      if s.charAt(i) == '\\' && i + 1 < s.length then
        b.append(s.charAt(i + 1) match { case 'n' => '\n'; case 'e' => '='; case c => c })
        i += 2
      else { b.append(s.charAt(i)); i += 1 }
    b.toString
