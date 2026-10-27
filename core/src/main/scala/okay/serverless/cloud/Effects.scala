package okay.serverless.cloud

import okay.scala3.*
import okay.effects.json.Json

/** a value in KV and its metadata (JSON text) */
final case class KvEntry(value: String, metadata: Option[String] = None)
/** a page of keys, and where the next starts */
final case class Page[A](items: List[A], cursor: Option[String])

/** KEY-VALUE, eventually consistent, read everywhere: KV on Cloudflare, DynamoDB on AWS */
enum Kv[X]:
  case Get(ns: String, key: String) extends Kv[Option[KvEntry]]
  case Put(ns: String, key: String, value: String, ttlSeconds: Option[Long], metadata: Option[String])
    extends Kv[Unit]
  case Delete(ns: String, key: String) extends Kv[Unit]
  case List(ns: String, prefix: String, cursor: Option[String], limit: Int) extends Kv[Page[String]]

object Kv:
  def get[R[_]](ns: String, key: String)(using In[Kv, R]): Option[String] ! R =
    perform[Kv](Get(ns, key)).map(_.map(_.value))
  def entry[R[_]](ns: String, key: String)(using In[Kv, R]): Option[KvEntry] ! R = perform[Kv](Get(ns, key))
  def put[R[_]](ns: String, key: String, value: String, ttlSeconds: Option[Long] = None,
                metadata: Option[String] = None)(using In[Kv, R]): Unit ! R =
    perform[Kv](Put(ns, key, value, ttlSeconds, metadata))
  def delete[R[_]](ns: String, key: String)(using In[Kv, R]): Unit ! R = perform[Kv](Delete(ns, key))
  def list[R[_]](ns: String, prefix: String = "", cursor: Option[String] = None, limit: Int = 1000)(
    using In[Kv, R]): Page[String] ! R = perform[Kv](List(ns, prefix, cursor, limit))

/** what is known of a stored object */
final case class BlobInfo(key: String, size: Long, etag: String, contentType: Option[String] = None,
                          metadata: Map[String, String] = Map.empty)

/** OBJECTS by key — files, uploads, exports: R2, S3, GCS */
enum Blobs[X]:
  case Put(bucket: String, key: String, body: Bytes, contentType: Option[String], metadata: Map[String, String])
    extends Blobs[BlobInfo]
  /** the object, or its bytes `from` (inclusive) `until` (exclusive) */
  case Get(bucket: String, key: String, range: Option[(Long, Long)]) extends Blobs[Option[(BlobInfo, Bytes)]]
  case Head(bucket: String, key: String) extends Blobs[Option[BlobInfo]]
  case Delete(bucket: String, keys: scala.List[String]) extends Blobs[Unit]
  case List(bucket: String, prefix: String, cursor: Option[String], limit: Int) extends Blobs[Page[BlobInfo]]

object Blobs:
  def put[R[_]](bucket: String, key: String, body: Bytes, contentType: Option[String] = None,
                metadata: Map[String, String] = Map.empty)(using In[Blobs, R]): BlobInfo ! R =
    perform[Blobs](Put(bucket, key, body, contentType, metadata))
  def get[R[_]](bucket: String, key: String, range: Option[(Long, Long)] = None)(
    using In[Blobs, R]): Option[(BlobInfo, Bytes)] ! R = perform[Blobs](Get(bucket, key, range))
  def head[R[_]](bucket: String, key: String)(using In[Blobs, R]): Option[BlobInfo] ! R =
    perform[Blobs](Head(bucket, key))
  def delete[R[_]](bucket: String, keys: String*)(using In[Blobs, R]): Unit ! R =
    perform[Blobs](Delete(bucket, keys.toList))
  def list[R[_]](bucket: String, prefix: String = "", cursor: Option[String] = None, limit: Int = 1000)(
    using In[Blobs, R]): Page[BlobInfo] ! R = perform[Blobs](List(bucket, prefix, cursor, limit))

/** a value in SQL (SQLite's types) */
enum SqlValue:
  case Null
  case Int(n: Long)
  case Real(d: Double)
  case Text(s: String)
  case Blob(b: Bytes)

object SqlValue:
  /** a parameter as written: a value, or a Scala one of its types */
  type Param = SqlValue | Long | scala.Int | Double | String | Bytes | Null
  def of(p: Param): SqlValue = p match
    case v: SqlValue => v
    case n: Long => Int(n)
    case n: scala.Int => Int(n.toLong)
    case d: Double => Real(d)
    case s: String => Text(s)
    case b: Bytes => Blob(b)
    case null => Null

type Row = Map[String, SqlValue]
final case class Statement(sql: String, params: List[SqlValue] = Nil)
object Statement:
  def of(sql: String, params: SqlValue.Param*): Statement = Statement(sql, params.toList.map(SqlValue.of))
/** what a statement changed */
final case class Changes(changes: Long, lastRowId: Long)

/** SQL — a database by its name, in its own dialect (the program knows its database): SQLite's (D1, an SQLite file)
 * or Postgres' (through Hyperdrive on Cloudflare, RDS Proxy on AWS); parameters `?` everywhere */
enum Sql[X]:
  case Query(db: String, statement: Statement) extends Sql[List[Row]]
  case Run(db: String, statement: Statement) extends Sql[Changes]
  /** the statements in one transaction: each one's rows */
  case Batch(db: String, statements: List[Statement]) extends Sql[List[List[Row]]]

object Sql:
  def query[R[_]](db: String, sql: String, params: SqlValue.Param*)(using In[Sql, R]): List[Row] ! R =
    perform[Sql](Query(db, Statement.of(sql, params*)))
  def run[R[_]](db: String, sql: String, params: SqlValue.Param*)(using In[Sql, R]): Changes ! R =
    perform[Sql](Run(db, Statement.of(sql, params*)))
  def batch[R[_]](db: String, statements: Statement*)(using In[Sql, R]): List[List[Row]] ! R =
    perform[Sql](Batch(db, statements.toList))

  /** `?` parameters as Postgres numbers them (`$1`, `$2`, …): those in quotes, in quoted names and in comments
   * left */
  def numbered(sql: String): String =
    val b = StringBuilder()
    var i = 0
    var n = 0
    while i < sql.length do
      sql(i) match
        case q @ ('\'' | '"') =>
          val end = sql.indexOf(q, i + 1) match
            case -1 => sql.length
            case j => j + 1
          b ++= sql.substring(i, end)
          i = end
        case '-' if sql.startsWith("--", i) =>
          val end = sql.indexOf('\n', i) match
            case -1 => sql.length
            case j => j
          b ++= sql.substring(i, end)
          i = end
        case '?' =>
          n += 1
          b ++= s"$$$n"
          i += 1
        case c =>
          b += c
          i += 1
    b.toString

/** a message to a queue: its body (text, JSON as a rule) and how long it waits first */
final case class Outgoing(body: String, delaySeconds: Long = 0)
/** a message as a consumer gets it */
final case class Message(id: String, body: String, attempts: Int, timestamp: Long)
/** what a consumer says of a message */
enum Settle:
  case Ack
  case Retry(delaySeconds: Long = 0)

/** QUEUES, producing: Cloudflare Queues, SQS, Pub/Sub. Consuming: `App.queue` */
enum Queues[X]:
  case Send(queue: String, messages: List[Outgoing]) extends Queues[Unit]

object Queues:
  def send[R[_]](queue: String, body: String, delaySeconds: Long = 0)(using In[Queues, R]): Unit ! R =
    perform[Queues](Send(queue, List(Outgoing(body, delaySeconds))))
  def sendAll[R[_]](queue: String, messages: List[Outgoing])(using In[Queues, R]): Unit ! R =
    perform[Queues](Send(queue, messages))

/** A CACHE OF RESPONSES by URL, near the caller: the Cache API on Cloudflare */
enum Cache[X]:
  case Match(url: String) extends Cache[Option[Response]]
  case Put(url: String, response: Response, ttlSeconds: Long) extends Cache[Unit]
  case Delete(url: String) extends Cache[Boolean]

object Cache:
  def get[R[_]](url: String)(using In[Cache, R]): Option[Response] ! R = perform[Cache](Match(url))
  def put[R[_]](url: String, response: Response, ttlSeconds: Long)(using In[Cache, R]): Unit ! R =
    perform[Cache](Put(url, response, ttlSeconds))
  def delete[R[_]](url: String)(using In[Cache, R]): Boolean ! R = perform[Cache](Delete(url))
  /** the cached response for `url`, or `make`'s, cached for `ttlSeconds` if it is a 200 */
  def through[R[_]](url: String, ttlSeconds: Long)(make: => Response ! R)(using In[Cache, R]): Response ! R =
    get[R](url).flatMap {
      case Some(r) => pure(r)
      case None => make.flatMap(r => if r.status == 200 then put[R](url, r, ttlSeconds).as(r) else pure(r))
    }

/** ANOTHER SERVICE, by name: service bindings on Cloudflare, a Lambda or a URL on AWS */
enum Services[X]:
  case Call(service: String, request: Request) extends Services[Response]

object Services:
  def call[R[_]](service: String, request: Request)(using In[Services, R]): Response ! R =
    perform[Services](Call(service, request))

/** RATE LIMITS: may `key` go now, under the limit configured as `name` */
enum Limits[X]:
  case Take(name: String, key: String) extends Limits[Boolean]

object Limits:
  def take[R[_]](name: String, key: String)(using In[Limits, R]): Boolean ! R = perform[Limits](Take(name, key))

/** A MODEL: JSON in, JSON out — Workers AI, Bedrock, Vertex AI */
enum Ai[X]:
  case Run(model: String, input: Json) extends Ai[Json]

object Ai:
  def run[R[_]](model: String, input: Json)(using In[Ai, R]): Json ! R = perform[Ai](Run(model, input))

final case class Embedding(id: String, values: List[Double], metadata: Map[String, String] = Map.empty)
final case class Nearest(id: String, score: Double, metadata: Map[String, String])

/** VECTORS: Vectorize, S3 Vectors, pgvector — cosine similarity */
enum Vectors[X]:
  case Upsert(index: String, vectors: List[Embedding]) extends Vectors[Unit]
  /** the `topK` nearest, of those whose metadata has each `filter` pair */
  case Query(index: String, values: List[Double], topK: Int, filter: Map[String, String]) extends Vectors[List[Nearest]]
  case Delete(index: String, ids: List[String]) extends Vectors[Unit]

object Vectors:
  def upsert[R[_]](index: String, vectors: Embedding*)(using In[Vectors, R]): Unit ! R =
    perform[Vectors](Upsert(index, vectors.toList))
  def query[R[_]](index: String, values: List[Double], topK: Int = 5, filter: Map[String, String] = Map.empty)(
    using In[Vectors, R]): List[Nearest] ! R = perform[Vectors](Query(index, values, topK, filter))
  def delete[R[_]](index: String, ids: String*)(using In[Vectors, R]): Unit ! R =
    perform[Vectors](Delete(index, ids.toList))

final case class DataPoint(indexes: List[String] = Nil, blobs: List[String] = Nil, doubles: List[Double] = Nil)

/** ANALYTICS: data points written, queried elsewhere — Analytics Engine, CloudWatch, BigQuery */
enum Analytics[X]:
  case Write(dataset: String, point: DataPoint) extends Analytics[Unit]

object Analytics:
  def write[R[_]](dataset: String, point: DataPoint)(using In[Analytics, R]): Unit ! R =
    perform[Analytics](Write(dataset, point))

final case class Mail(from: String, to: String, subject: String, text: String)
/** a mail received: `App.mail` */
final case class IncomingMail(from: String, to: String, raw: String)

/** MAIL OUT: Email Routing's `send_email`, SES, SMTP */
enum Mails[X]:
  case Send(binding: String, mail: Mail) extends Mails[Unit]

object Mails:
  def send[R[_]](binding: String, mail: Mail)(using In[Mails, R]): Unit ! R = perform[Mails](Send(binding, mail))

/** A REQUEST TO AN OBJECT — an actor of a namespace, by its name, one in the world: Durable Objects */
enum Objects[X]:
  case Call(namespace: String, name: String, request: Request) extends Objects[Response]

object Objects:
  def call[R[_]](namespace: String, name: String, request: Request)(using In[Objects, R]): Response ! R =
    perform[Objects](Call(namespace, name, request))

/** an open connection to an actor: its id and its tags */
final case class Socket(id: String, tags: List[String])

/** WEBSOCKETS, in an actor: accepted from a request (the answer to give it), sent to, closed, found by tag */
enum Sockets[X]:
  case Accept(request: Request, tags: List[String]) extends Sockets[Response]
  case Send(socket: Socket, text: String) extends Sockets[Unit]
  case Close(socket: Socket, code: Int, reason: String) extends Sockets[Unit]
  case Open(tag: Option[String]) extends Sockets[List[Socket]]

object Sockets:
  def accept[R[_]](request: Request, tags: String*)(using In[Sockets, R]): Response ! R =
    perform[Sockets](Accept(request, tags.toList))
  def send[R[_]](socket: Socket, text: String)(using In[Sockets, R]): Unit ! R = perform[Sockets](Send(socket, text))
  def close[R[_]](socket: Socket, code: Int = 1000, reason: String = "")(using In[Sockets, R]): Unit ! R =
    perform[Sockets](Close(socket, code, reason))
  def open[R[_]](tag: Option[String] = None)(using In[Sockets, R]): List[Socket] ! R = perform[Sockets](Open(tag))
  /** `text` to each open socket (of `tag`) */
  def broadcast[R[_]](text: String, tag: Option[String] = None)(using In[Sockets, R]): Int ! R =
    open[R](tag).flatMap(all => all.foldLeft(pure[Unit, R](()))((p, s) => p.andThen(send[R](s, text))).as(all.size))
