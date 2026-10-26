package okay.serverless.cloud

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Types}
import scala.collection.mutable
import scala.concurrent.Future

/** SQL ON SQLITE FILES (the JVM): D1's dialect, a database a file in `dir` by its name — or in memory, `dir` none */
final class SqliteSql(dir: Option[java.io.File] = None) extends Port[Sql]:
  private val open = mutable.Map.empty[String, Connection]

  private def connection(db: String): Connection = synchronized(open.getOrElseUpdate(db, {
    val url = dir.fold(s"jdbc:sqlite:file:$db?mode=memory&cache=shared")(d => s"jdbc:sqlite:${java.io.File(d, s"$db.sqlite")}")
    DriverManager.getConnection(url)
  }))

  private def prepared(c: Connection, s: Statement): PreparedStatement =
    val ps = c.prepareStatement(s.sql)
    s.params.zipWithIndex.foreach { (v, i) =>
      v match
        case SqlValue.Null => ps.setNull(i + 1, Types.NULL)
        case SqlValue.Int(n) => ps.setLong(i + 1, n)
        case SqlValue.Real(d) => ps.setDouble(i + 1, d)
        case SqlValue.Text(t) => ps.setString(i + 1, t)
        case SqlValue.Blob(b) => ps.setBytes(i + 1, b.toArray)
    }
    ps

  private def rows(rs: ResultSet): List[Row] =
    val meta = rs.getMetaData
    val names = (1 to meta.getColumnCount).map(meta.getColumnLabel)
    val out = mutable.ListBuffer.empty[Row]
    while rs.next() do
      out += names.zipWithIndex.map { (n, i) =>
        n -> (rs.getObject(i + 1) match
          case null => SqlValue.Null
          case l: java.lang.Long => SqlValue.Int(l)
          case i: java.lang.Integer => SqlValue.Int(i.toLong)
          case d: java.lang.Double => SqlValue.Real(d)
          case f: java.lang.Float => SqlValue.Real(f.toDouble)
          case b: Array[Byte] => SqlValue.Blob(Bytes(b))
          case other => SqlValue.Text(other.toString))
      }.toMap
    out.toList

  /** a statement: its rows, if it gives any */
  private def execute(c: Connection, s: Statement): List[Row] =
    val ps = prepared(c, s)
    try if ps.execute() then rows(ps.getResultSet) else Nil finally ps.close()

  private def changes(c: Connection, s: Statement): Changes =
    val ps = prepared(c, s)
    try
      val n = ps.executeUpdate()
      val st = c.createStatement()
      try Changes(n.toLong, st.executeQuery("select last_insert_rowid()").getLong(1)) finally st.close()
    finally ps.close()

  def apply[X](op: Sql[X]): Future[X] = Future.fromTry(scala.util.Try(synchronized[X](op match
    case Sql.Query(db, s) => execute(connection(db), s)
    case Sql.Run(db, s) => changes(connection(db), s)
    case Sql.Batch(db, ss) =>
      val c = connection(db)
      c.setAutoCommit(false)
      try
        val out = ss.map(execute(c, _))
        c.commit()
        out
      catch
        case e: Throwable =>
          c.rollback()
          throw e
      finally c.setAutoCommit(true))))
