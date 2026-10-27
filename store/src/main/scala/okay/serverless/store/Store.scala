package okay.serverless.store

import okay.effects.json.Json
import okay.scala3.*
import okay.serverless.cloud.{Kv, Sql, SqlValue, Statement}
import okay.serverless.Durable
import okay.store.{Data, Dialect, Mapped, Mapping, Order, SqlArg, SqlData, SqlStatement, Where}

/**
 * OKAY-STORE IN THE CLOUD (specs/store.md): okay-store's effects — `Data`, documents by key with a query, and
 * `Mapped[A]`, a type's repository — performed by okay-scala programs as they are (their operations are data), and
 * handled on the cloud's own effects: SQL (D1, Postgres through Hyperdrive or RDS Proxy, an SQLite file), KV, an
 * object's `Durable` state. The SQL is okay-store's (`SqlData`, its dialects); the query's meaning is okay-store's
 * reference (`Where.holds`, `Order.select`) wherever SQL does not say it.
 */
object Docs:
  def get[R[_]](collection: String, key: String)(using In[Data, R]): Option[Json] ! R =
    perform[Data](Data.Get(collection, key, identity))
  def put[R[_]](collection: String, key: String, doc: Json)(using In[Data, R]): Unit ! R =
    perform[Data](Data.Put(collection, key, doc, identity))
  def delete[R[_]](collection: String, key: String)(using In[Data, R]): Unit ! R =
    perform[Data](Data.Delete(collection, key, identity))
  def query[R[_]](collection: String, where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(
    using In[Data, R]): List[(String, Json)] ! R = perform[Data](Data.Query(collection, where, order, limit, identity))
  def count[R[_]](collection: String, where: Where = Where.All)(using In[Data, R]): Long ! R =
    perform[Data](Data.Count(collection, where, identity))
  def fresh[R[_]](collection: String)(using In[Data, R]): String ! R = perform[Data](Data.Fresh(collection, identity))

/** A TYPE'S REPOSITORY, okay-store's `Mapped[A]`: in a row as `Repo.Of[A]` */
object Repo:
  type Of[A] = [X] =>> Mapped[A, X]
  def save[A, R[_]](a: A)(using In[Of[A], R]): Unit ! R = perform[Of[A]](Mapped.Save[A, Unit](a, identity))
  def load[A, R[_]](key: String)(using In[Of[A], R]): Option[A] ! R =
    perform[Of[A]](Mapped.Load[A, Option[A]](key, identity))
  def remove[A, R[_]](key: String)(using In[Of[A], R]): Unit ! R = perform[Of[A]](Mapped.Remove[A, Unit](key, identity))
  def find[A, R[_]](where: Where = Where.All, order: List[Order] = Nil, limit: Option[Int] = None)(
    using In[Of[A], R]): List[A] ! R = perform[Of[A]](Mapped.Find[A, List[A]](where, order, limit, identity))
  def count[A, R[_]](where: Where = Where.All)(using In[Of[A], R]): Long ! R =
    perform[Of[A]](Mapped.Count[A, Long](where, identity))
  /** a value made with a key from the store, and kept */
  def create[A, R[_]](make: String => A)(using In[Of[A], R]): A ! R =
    perform[Of[A]](Mapped.Create[A, A](make, identity))

object StoreOn:
  /** `Mapped[A]` ON `Data`: a value its document by the mapping's shape, under its key — okay-store's
   * `Mappings.onData`, said for okay-scala */
  def data[A, R[_], Rest[_], B](m: Mapping[A])(p: B ! R)(using Split[Repo.Of[A], R, Rest], In[Data, Rest]): B ! Rest =
    handle(p, Handler.general[Repo.Of[A], Rest, B, B](pure)([X] => (op: Mapped[A, X], k: X => B ! Rest) => op match
      case Mapped.Save(a, answer) =>
        Docs.put[Rest](m.collection, m.key(a), m.shape.encode(a)).flatMap(_ => k(answer(())))
      case Mapped.Load(key, answer) =>
        Docs.get[Rest](m.collection, key).flatMap(d => k(answer(d.map(m.decode(key, _)))))
      case Mapped.Remove(key, answer) => Docs.delete[Rest](m.collection, key).flatMap(_ => k(answer(())))
      case Mapped.Find(w, o, l, answer) =>
        Docs.query[Rest](m.collection, w, o, l).flatMap(rows => k(answer(rows.map((key, d) => m.decode(key, d)))))
      case Mapped.Count(w, answer) => Docs.count[Rest](m.collection, w).flatMap(n => k(answer(n)))
      case Mapped.Create(make, answer) => Docs.fresh[Rest](m.collection).flatMap { key =>
          val a = make(key)
          Docs.put[Rest](m.collection, m.key(a), m.shape.encode(a)).flatMap(_ => k(answer(a)))
        }))

  private def value(a: SqlArg): SqlValue = a match
    case SqlArg.Text(s) => SqlValue.Text(s)
    case SqlArg.Whole(n) => SqlValue.Int(n)
    case SqlArg.Real(d) => SqlValue.Real(d)
    case SqlArg.Null => SqlValue.Null
  private def statement(s: SqlStatement): Statement = Statement(s.sql, s.args.map(value))
  private def text(v: SqlValue): String = v match
    case SqlValue.Text(t) => t
    case other => throw IllegalStateException(s"a document's text expected, not $other")
  /** a number as it comes back: an integer, or text (Postgres' `bigint` by `pg`) */
  private def number(v: SqlValue): Long = v match
    case SqlValue.Int(n) => n
    case SqlValue.Real(d) => d.toLong
    case SqlValue.Text(t) => t.toLong
    case other => throw IllegalStateException(s"a number expected, not $other")

  /** `Data` ON SQL: the database `db` by the `Sql` effect — D1 or an SQLite file (`Dialect.sqlite`), Postgres through
   * Hyperdrive or RDS Proxy (`Dialect.postgres`). A collection a table `(key, doc)`, made at first use; the statements
   * okay-store's. A program's operations are statements each: a transaction is the database's, or a step's */
  def sql[R[_], Rest[_], B](db: String, dialect: Dialect = Dialect.sqlite)(p: B ! R)(
    using Split[Data, R, Rest], In[Sql, Rest]): B ! Rest =
    defer {
      val made = scala.collection.mutable.Set.empty[String]
      def ensure(collection: String): Unit ! Rest =
        if made(collection) then pure(())
        else Sql.batch[Rest](db, statement(SqlData.keys(dialect)), statement(SqlData.table(dialect, collection)))
          .map(_ => made += collection)
      def run(s: SqlStatement) = Sql.batch[Rest](db, statement(s)).map(_.head)
      handle(p, Handler.general[Data, Rest, B, B](pure)([X] => (op: Data[X], k: X => B ! Rest) => op match
        case Data.Get(c, key, answer) => ensure(c).andThen(run(SqlData.get(c, key)))
            .flatMap(rows => k(answer(rows.headOption.map(r => SqlData.doc(text(r("doc")))))))
        case Data.Put(c, key, doc, answer) => ensure(c).andThen(run(SqlData.put(dialect, c, key, doc)))
            .flatMap(_ => k(answer(())))
        case Data.Delete(c, key, answer) => ensure(c).andThen(run(SqlData.delete(c, key))).flatMap(_ => k(answer(())))
        case Data.Query(c, w, o, l, answer) => ensure(c).andThen(run(SqlData.query(dialect, c, w, o, l)))
            .flatMap(rows => k(answer(rows.map(r => text(r("key")) -> SqlData.doc(text(r("doc")))))))
        case Data.Count(c, w, answer) => ensure(c).andThen(run(SqlData.count(dialect, c, w)))
            .flatMap(rows => k(answer(number(rows.head.values.head))))
        case Data.Fresh(c, answer) => ensure(c).andThen(run(SqlData.fresh(dialect, Data.named(c))))
            .flatMap(rows => k(answer(Data.key(number(rows.head.values.head)))))))
    }

  /** `Data` ON KV — Cloudflare's KV, DynamoDB: a document a value under `<collection>/<key>`; a query the collection
   * listed and read, the reference's meaning (`Order.select`). Eventually consistent, no sequences (`Fresh`: SQL's or
   * an object's): for what is read far more than written */
  def kv[R[_], Rest[_], B](ns: String)(p: B ! R)(using Split[Data, R, Rest], In[Kv, Rest]): B ! Rest =
    def docKey(c: String, key: String) = s"${Data.named(c)}/$key"
    def all(c: String): List[(String, Json)] ! Rest =
      def page(cursor: Option[String], acc: List[String]): List[String] ! Rest =
        Kv.list[Rest](ns, s"${Data.named(c)}/", cursor).flatMap(pg =>
          if pg.cursor.isEmpty then pure(acc ++ pg.items) else page(pg.cursor, acc ++ pg.items))
      page(None, Nil).flatMap(keys => keys.foldLeft(pure[List[(String, Json)], Rest](Nil))((acc, k) =>
        acc.flatMap(got => Kv.get[Rest](ns, k).map {
          case Some(v) => got :+ (k.stripPrefix(s"${Data.named(c)}/") -> SqlData.doc(v))
          case None => got
        })))
    handle(p, Handler.general[Data, Rest, B, B](pure)([X] => (op: Data[X], k: X => B ! Rest) => op match
      case Data.Get(c, key, answer) => Kv.get[Rest](ns, docKey(c, key)).flatMap(v => k(answer(v.map(SqlData.doc))))
      case Data.Put(c, key, doc, answer) =>
        Kv.put[Rest](ns, docKey(c, key), Json.print(doc)).flatMap(_ => k(answer(())))
      case Data.Delete(c, key, answer) => Kv.delete[Rest](ns, docKey(c, key)).flatMap(_ => k(answer(())))
      case Data.Query(c, w, o, l, answer) => all(c).flatMap(docs => k(answer(Order.select(docs, w, o, l))))
      case Data.Count(c, w, answer) => all(c).flatMap(docs => k(answer(docs.count((_, d) => Where.holds(w, d)).toLong)))
      case Data.Fresh(c, _) =>
        defer(throw UnsupportedOperationException(
          s"$c: no sequences on KV — Data on SQL or in an object gives keys"))))

  /** `Data` IN AN OBJECT'S STATE (`Durable`): a document under `data/<collection>/<key>`, a sequence under
   * `data/_keys/<collection>`; a query the collection read, the reference's meaning. Within a `Durable.step`, the
   * program's operations are one transaction — on Cloudflare the object's SQLite, on AWS its DynamoDB space */
  def durable[R[_], Rest[_], B](p: B ! R)(using Split[Data, R, Rest], In[Durable, Rest]): B ! Rest =
    def docKey(c: String, key: String) = s"data/${Data.named(c)}/$key"
    def all(c: String): List[(String, Json)] ! Rest =
      Durable.keys[Rest](s"data/${Data.named(c)}/").flatMap(keys =>
        keys.foldLeft(pure[List[(String, Json)], Rest](Nil))(
        (acc, key) => acc.flatMap(got => Durable.get[Json, Rest](key).map {
          case Some(d) => got :+ (key.stripPrefix(s"data/${Data.named(c)}/") -> d)
          case None => got
        })))
    handle(p, Handler.general[Data, Rest, B, B](pure)([X] => (op: Data[X], k: X => B ! Rest) => op match
      case Data.Get(c, key, answer) => Durable.get[Json, Rest](docKey(c, key)).flatMap(d => k(answer(d)))
      case Data.Put(c, key, doc, answer) => Durable.put[Json, Rest](docKey(c, key), doc).flatMap(_ => k(answer(())))
      case Data.Delete(c, key, answer) => Durable.delete[Rest](docKey(c, key)).flatMap(_ => k(answer(())))
      case Data.Query(c, w, o, l, answer) => all(c).flatMap(docs => k(answer(Order.select(docs, w, o, l))))
      case Data.Count(c, w, answer) => all(c).flatMap(docs => k(answer(docs.count((_, d) => Where.holds(w, d)).toLong)))
      case Data.Fresh(c, answer) =>
        val counter = s"data/_keys/${Data.named(c)}"
        Durable.get[Long, Rest](counter).flatMap { n =>
          val next = n.getOrElse(0L) + 1
          Durable.put[Long, Rest](counter, next).flatMap(_ => k(answer(Data.key(next))))
        }))
