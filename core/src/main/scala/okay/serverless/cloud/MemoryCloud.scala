package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.*
import okay.effects.json.Json
import okay.serverless.{Memory, Storage}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * A CLOUD IN MEMORY, for tests and for running locally: every effect's port; apps (services by name), actors (objects
 * by namespace) with their storage, alarms and sockets; queues delivered when told (`deliver`), alarms fired when told
 * (`fireAlarms`), time by `clock`. SQL is not here: on the JVM `SqliteSql` gives it, on a cloud the cloud's.
 */
final class MemoryCloud(val clock: () => Long = () => System.currentTimeMillis())(using ec: ExecutionContext):
  private def now[X](x: => X): Future[X] = Future.fromTry(scala.util.Try(x))

  // ---- KV
  private val kvs = mutable.Map.empty[(String, String), (KvEntry, Option[Long])]
  val kv: Port[Kv] = new Port[Kv]:
    def apply[X](op: Kv[X]): Future[X] = now[X](synchronized[X](op match
      case Kv.Get(ns, key) => kvs.get((ns, key)).collect { case (e, exp) if exp.forall(_ > clock()) => e }
      case Kv.Put(ns, key, value, ttl, meta) => kvs((ns, key)) = (KvEntry(value, meta), ttl.map(clock() + _ * 1000))
      case Kv.Delete(ns, key) => kvs.remove((ns, key)); ()
      case Kv.List(ns, prefix, cursor, limit) =>
        val all = kvs.collect { case ((n, k), (_, exp)) if n == ns && k.startsWith(prefix) &&
            exp.forall(_ > clock()) && cursor.forall(k > _) => k }.toList.sorted
        val (page, rest) = all.splitAt(limit)
        Page(page, if rest.isEmpty then None else page.lastOption)))

  // ---- blobs
  private val blobs = mutable.Map.empty[(String, String), (BlobInfo, Bytes)]
  val blobsPort: Port[Blobs] = new Port[Blobs]:
    def apply[X](op: Blobs[X]): Future[X] = now[X](synchronized[X](op match
      case Blobs.Put(bucket, key, body, ct, meta) =>
        val info = BlobInfo(key, body.size.toLong, f"${body.hashCode}%08x", ct, meta)
        blobs((bucket, key)) = (info, body)
        info
      case Blobs.Get(bucket, key, range) => blobs.get((bucket, key)).map((info, b) =>
        (info, range.fold(b)((from, until) => b.slice(from.toInt, math.min(until, b.size.toLong).toInt))))
      case Blobs.Head(bucket, key) => blobs.get((bucket, key)).map(_._1)
      case Blobs.Delete(bucket, keys) => keys.foreach(k => blobs.remove((bucket, k)))
      case Blobs.List(bucket, prefix, cursor, limit) =>
        val all = blobs.collect { case ((b, k), (info, _)) if b == bucket && k.startsWith(prefix) &&
            cursor.forall(k > _) => info }.toList.sortBy(_.key)
        val (page, rest) = all.splitAt(limit)
        Page(page, if rest.isEmpty then None else page.lastOption.map(_.key))))

  // ---- queues: sent, then delivered to the app's consumer when told
  private final case class Queued(queue: String, message: Message, visibleAt: Long)
  private val queued = mutable.ListBuffer.empty[Queued]
  private var ids = 0L
  /** messages given up after `maxAttempts`: a dead-letter queue */
  val dead: mutable.ListBuffer[(String, Message)] = mutable.ListBuffer.empty
  var maxAttempts = 3
  val queues: Port[Queues] = new Port[Queues]:
    def apply[X](op: Queues[X]): Future[X] = now[X](synchronized[X](op match
      case Queues.Send(queue, messages) => messages.foreach { m =>
          ids += 1
          queued += Queued(queue, Message(s"m$ids", m.body, 0, clock()), clock() + m.delaySeconds * 1000)
        }))

  /** each message due, to `app`'s consumer once: acknowledged, gone; retried (or failed), again later — until
   * `maxAttempts`, then dead. How many were delivered */
  def deliver(app: App): Future[Int] =
    val due = synchronized {
      val d = queued.filter(_.visibleAt <= clock()).toList
      queued --= d
      d
    }
    Future.traverse(due) { q =>
      val m = q.message.copy(attempts = q.message.attempts + 1)
      platform.future(app.queue(q.queue, m)).recover { case _ => Settle.Retry() }.map {
        case Settle.Ack => ()
        case Settle.Retry(delay) => synchronized {
            if m.attempts >= maxAttempts then dead += q.queue -> m
            else queued += Queued(q.queue, m, clock() + delay * 1000)
          }
      }
    }.map(_.size)

  def pending: Int = synchronized(queued.size)

  // ---- HTTP out: answered by `internet`, given the URL and the request
  @volatile var internet: (String, Request) => Response = (_, _) => Response.text("no internet in memory", 502)
  val fetch: Port[Fetch] = new Port[Fetch]:
    def apply[X](op: Fetch[X]): Future[X] = op match
      case okay.http.Fetch.Send(base, r, answer) => now(answer(internet(okay.http.Fetch.target(base, r), r)))
      case okay.http.Fetch.Call(m, url, h, body, answer) =>
        now(answer(internet(url, okay.http.Fetch.request(m, url, h, body))))

  // ---- cache
  private val cached = mutable.Map.empty[String, (Response, Long)]
  val cache: Port[Cache] = new Port[Cache]:
    def apply[X](op: Cache[X]): Future[X] = now[X](synchronized[X](op match
      case Cache.Match(url) => cached.get(url).collect { case (r, exp) if exp > clock() => r }
      case Cache.Put(url, r, ttl) => cached(url) = (r, clock() + ttl * 1000)
      case Cache.Delete(url) => cached.remove(url).isDefined))

  // ---- configuration
  val vars: mutable.Map[String, String] = mutable.Map.empty
  val secrets: mutable.Map[String, String] = mutable.Map.empty
  val env: Port[Env] = new Port[Env]:
    def apply[X](op: Env[X]): Future[X] = now[X](op match
      case Env.Var(name) => vars.get(name)
      case Env.Secret(name) => secrets.get(name))

  // ---- other services: apps by name, on this cloud
  val apps: mutable.Map[String, App] = mutable.Map.empty
  val services: Port[Services] = new Port[Services]:
    def apply[X](op: Services[X]): Future[X] = op match
      case Services.Call(name, r) => apps.get(name) match
        case Some(app) => platform.future(app.fetch(r))
        case None => Future.failed(java.util.NoSuchElementException(s"no service $name"))

  // ---- rate limits: `limit` a key in each window of `periodSeconds`, by name
  val limitsConfig: mutable.Map[String, (Int, Long)] = mutable.Map.empty
  private val windows = mutable.Map.empty[(String, String), (Long, Int)]
  val limits: Port[Limits] = new Port[Limits]:
    def apply[X](op: Limits[X]): Future[X] = now[X](synchronized[X](op match
      case Limits.Take(name, key) =>
        val (limit, period) =
          limitsConfig.getOrElse(name, throw java.util.NoSuchElementException(s"no rate limit $name"))
        val window = clock() / (period * 1000)
        val used = windows.get((name, key)).collect { case (w, n) if w == window => n }.getOrElse(0)
        if used < limit then { windows((name, key)) = (window, used + 1); true } else false))

  // ---- a model: answered by `model`
  @volatile var model: (String, Json) => Json = (m, _) => throw UnsupportedOperationException(s"no model $m")
  val ai: Port[Ai] = new Port[Ai]:
    def apply[X](op: Ai[X]): Future[X] = op match
      case Ai.Run(m, input) => now(model(m, input))

  // ---- vectors: cosine, every one compared
  private val vectorsStored = mutable.Map.empty[(String, String), Embedding]
  val vectors: Port[Vectors] = new Port[Vectors]:
    def apply[X](op: Vectors[X]): Future[X] = now[X](synchronized[X](op match
      case Vectors.Upsert(index, vs) => vs.foreach(v => vectorsStored((index, v.id)) = v)
      case Vectors.Delete(index, ids) => ids.foreach(id => vectorsStored.remove((index, id)))
      case Vectors.Query(index, values, topK, filter) =>
        def norm(xs: List[Double]) = math.sqrt(xs.map(x => x * x).sum)
        vectorsStored.collect { case ((i, _), v) if i == index && filter.forall((k, x) => v.metadata.get(k)
            .contains(x)) => Nearest(v.id, v.values.zip(values).map(_ * _).sum / (norm(v.values) * norm(values)),
            v.metadata) }.toList.sortBy(-_.score).take(topK)))

  // ---- analytics, mail: kept, to be looked at
  val points: mutable.ListBuffer[(String, DataPoint)] = mutable.ListBuffer.empty
  val analytics: Port[Analytics] = new Port[Analytics]:
    def apply[X](op: Analytics[X]): Future[X] = now[X](synchronized[X](op match
      case Analytics.Write(dataset, point) => points += dataset -> point; ()))
  val sent: mutable.ListBuffer[(String, Mail)] = mutable.ListBuffer.empty
  val mails: Port[Mails] = new Port[Mails]:
    def apply[X](op: Mails[X]): Future[X] = now[X](synchronized[X](op match
      case Mails.Send(binding, mail) => sent += binding -> mail; ()
      case Mails.Deliver(binding, mail) => sent += binding -> mail; Right(())))

  val logged: mutable.ListBuffer[(Level, String)] = mutable.ListBuffer.empty

  // ---- objects: an actor class by namespace; an instance a name — its storage, its sockets, its requests one by one
  val actors: mutable.Map[String, Actor] = mutable.Map.empty
  private final class Instance(val ns: String, val name: String):
    val memory = Memory()
    var queue: Future[Any] = Future.unit
    val sockets = mutable.LinkedHashMap.empty[String, (Socket, MemorySocket)]
    /** every socket's client end, open or closed: a test finds it after the fact too */
    val ends = mutable.Map.empty[String, MemorySocket]
    def actor: Actor = actors.getOrElse(ns, throw java.util.NoSuchElementException(s"no actor class $ns"))
    def one[A](p: A ! InActor): Future[A] =
      val promise = Promise[A]()
      MemoryCloud.this.synchronized {
        queue = queue.recover(_ => ()).flatMap(_ =>
          Async.toFuture(hosting.run(platform)(p)).andThen { case t => promise.complete(t) })
      }
      promise.future
    val hosting: Hosting = new Hosting:
      val storage: Port[Storage] = new Port[Storage]:
        def apply[X](op: Storage[X]): Future[X] = now[X](op match
          case Storage.Read(keys) => memory.read(keys)
          case Storage.Keys(prefix) => memory.keys(prefix)
          case Storage.Commit(r, w, a) => memory.commit(r, w, a))
      val sockets: Port[Sockets] = new Port[Sockets]:
        def apply[X](op: Sockets[X]): Future[X] = now[X](Instance.this.synchronized[X](op match
          case Sockets.Accept(_, tags) => opened(tags, stream = false)
          case Sockets.Stream(_, tags) => opened(tags, stream = true)
          case Sockets.Send(s, text) => Instance.this.sockets.get(s.id).foreach(_._2.received += text)
          case Sockets.Close(s, code, _) => Instance.this.sockets.remove(s.id).foreach { (_, m) =>
              m.closedWith = Some(code)
              m.ended.trySuccess(())
            }
          case Sockets.Open(tag) => Instance.this.sockets.values.collect {
              case (s, m) if !m.stream && tag.forall(s.tags.contains) => s
            }.toList
          case Sockets.Later(_) => throw IllegalStateException("later is the host's")))
      private def opened(tags: List[String], stream: Boolean): Response =
        ids += 1
        val s = Socket(s"s$ids", tags)
        val end = MemorySocket(s, Instance.this, stream)
        Instance.this.sockets(s.id) = (s, end)
        Instance.this.ends(s.id) = end
        if stream then Response(200, Map("okay-socket" -> s.id, "content-type" -> "text/event-stream"))
        else Response(101, Map("okay-socket" -> s.id))
      override def keep(work: Future[Unit]): Unit = MemoryCloud.this.synchronized(working += work)
  private val instances = mutable.Map.empty[(String, String), Instance]
  private def instance(ns: String, name: String): Instance = synchronized(instances.getOrElseUpdate((ns, name),
    Instance(ns, name)))

  /** work after answers, started (`Sockets.later`) */
  private val working = mutable.ListBuffer.empty[Future[Unit]]
  /** every work started after an answer, done */
  def settled(): Future[Unit] =
    val all = synchronized(working.toList)
    Future.sequence(all).flatMap(_ => if synchronized(working.size) > all.size then settled() else Future.unit)

  /** a client's end of a socket — a WebSocket's, or an event stream's (`stream`): what it was sent; what it sends,
   * to the actor; `ended` when the actor closed it */
  final class MemorySocket private[MemoryCloud] (val socket: Socket, private val at: Instance, val stream: Boolean):
    val received: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    @volatile var closedWith: Option[Int] = None
    val ended: Promise[Unit] = Promise()
    def send(text: String): Future[Unit] = at.one(at.actor.message(at.name, socket, text))
    def close(): Future[Unit] =
      at.synchronized(at.sockets.remove(socket.id))
      at.one(at.actor.closed(at.name, socket))

  val objects: Port[Objects] = new Port[Objects]:
    def apply[X](op: Objects[X]): Future[X] = op match
      case Objects.Call(ns, name, r) => instance(ns, name).pipe(i => i.one(i.actor.fetch(name, r)))
      case Objects.Unique(ns) => now(synchronized { ids += 1; s"${ns.toLowerCase}-$ids-${clock()}" })

  /** a socket opened to an object: its request answered by the actor's `Sockets.accept` */
  def connect(ns: String, name: String, request: Request): Future[MemorySocket] =
    objects(Objects.Call(ns, name, request)).map { r =>
      val id = r.headerOf("okay-socket").getOrElse(throw IllegalStateException(s"not accepted: ${r.status}"))
      instance(ns, name).pipe(i => i.synchronized(i.ends(id)))
    }

  /** the socket of a response an object gave (`accept`, `stream`): its client's end */
  def socketOf(ns: String, name: String, response: Response): MemorySocket =
    val id = response.headerOf("okay-socket").getOrElse(throw IllegalStateException(s"no socket: ${response.status}"))
    instance(ns, name).pipe(i => i.synchronized(i.ends(id)))

  /** A REQUEST TO AN APP, as a platform serves it: admitted (`App.admit`), then to its object as it is
   * (`objectFor`) or to `fetch` */
  def serve(app: App)(request: Request): Future[Response] =
    platform.future(app.admit(request)).flatMap {
      case Left(refused) => Future.successful(refused)
      case Right(r) => app.objectFor(r) match
        case Some((ns, name)) => objects(Objects.Call(ns, name, r))
        case None => platform.future(app.fetch(r))
    }

  /** an object's storage, as committed */
  def stored(ns: String, name: String): Map[String, String] = instance(ns, name).memory.snapshot

  /** each object whose alarm is due, its `alarm` run (the alarm cleared first, as a cloud does) */
  def fireAlarms(): Future[Int] =
    val due = synchronized(instances.values.filter(_.memory.alarm.exists(_ <= clock())).toList)
    Future.traverse(due) { i =>
      i.memory.alarm = None
      i.one(i.actor.alarm(i.name))
    }.map(_.size)

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  // ---- TCP: servers of the test's, by host and port; each connection a session of one

  private val servers = mutable.Map.empty[(String, Int), TcpServer]
  private val sessions = mutable.Map.empty[String, TcpSession]
  private var connections = 0
  /** a server of the test's at `host:port`: each connection to it a new session */
  def listen(host: String, port: Int)(server: TcpServer): Unit = synchronized(servers((host, port)) = server)
  val tcp: Port[Tcp] = new Port[Tcp]:
    def apply[X](op: Tcp[X]): Future[X] = now[X](synchronized[X](op match
      case Tcp.Connect(host, port, tls) => servers.get((host, port)) match
        case None => Left(s"connection refused: $host:$port")
        case Some(server) =>
          connections += 1
          val id = s"conn-$connections"
          sessions(id) = server.open(tls == Tls.On)
          Right(Conn(id))
      case Tcp.Write(c, text) => sessions.get(c.id).toRight("closed").map(_.receive(text))
      case Tcp.Read(c) => sessions.get(c.id).toRight("closed").map(_.answer())
      case Tcp.Upgrade(c) => sessions.get(c.id).toRight("closed").flatMap(s =>
        if s.upgrade() then Right(c) else Left("the server would not start TLS"))
      case Tcp.Close(c) => sessions.remove(c.id).foreach(_.closed()); ()))

  val platform: Platform = Platform(kv = kv, blobs = blobsPort, queues = queues, fetch = fetch, cache = cache,
    env = env, services = services, limits = limits, ai = ai, vectors = vectors, analytics = analytics, mails = mails,
    objects = objects, tcp = tcp, log = (l, m) => synchronized(logged += l -> m), now = clock)

  /** the platform with SQL by `sql` */
  def withSql(sql: Port[Sql]): Platform = platform.copy(sql = sql)

/** A SERVER OF A TEST'S, in memory (`MemoryCloud.listen`): each connection a session */
trait TcpServer:
  /** a session for a connection; `tls` when it was TLS from the start */
  def open(tls: Boolean): TcpSession

/** one connection to a server in memory: what it is sent, what it answers, its TLS */
trait TcpSession:
  /** what was written to it */
  def receive(text: String): Unit
  /** what it says now: the next text, `None` when it has closed */
  def answer(): Option[String]
  /** STARTTLS asked: whether it agrees */
  def upgrade(): Boolean = true
  def closed(): Unit = ()
