package okay.serverless.host

import okay.effects.json.{Descent, Json}
import okay.http.{Bytes, Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.Storage
import okay.serverless.cloud.*

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * WHAT THE HOST IS GIVEN: where its state lives, what it serves, its configuration. `vars` over the process's
 * environment; `crons` the triggers given to `scheduled`; `ai` a server with OpenAI's API; `smtp` a mail binding's
 * server; `limits` a rate limit's `limit` a window of `periodSeconds`; `services` other apps by name; `adjust` the
 * platform as an app wants it (a port wrapped: a model's failure answered as a value); `testScheduled` a cron run by a
 * request, `/cdn-cgi/handler/scheduled?cron=…` — wrangler's own, so one end-to-end script drives both.
 */
final case class HostConfig(dir: Path, port: Int = 8787, host: String = "127.0.0.1", static: Option[Path] = None,
                            vars: Map[String, String] = Map.empty, crons: List[String] = Nil,
                            ai: Option[AiServer] = None, smtp: Map[String, SmtpServer] = Map.empty,
                            limits: Map[String, (Int, Long)] = Map.empty, services: Map[String, App] = Map.empty,
                            tickMillis: Long = 1000, maxAttempts: Int = 3,
                            adjust: Platform => Platform = identity, testScheduled: Boolean = false,
                            log: (Level, String) => Unit = (l, m) => System.err.println(s"[$l] $m"),
                            clock: () => Long = () => System.currentTimeMillis())

/**
 * A CLOUD ON ONE JVM PROCESS AND ONE DIRECTORY (specs/cloud.md "The host" — the fallback beside Cloudflare): every
 * effect's port on local things (an SQLite file, files, the JDK's HTTP and sockets, a model server of OpenAI's API),
 * objects in the process — one instance a name, its requests one at a time, its state and alarm in the file — and
 * their sockets, bound to real connections by the host's server.
 */
final class HostCloud(val config: HostConfig, actors: Map[String, Actor])(using ec: ExecutionContext):
  private def clock(): Long = config.clock()
  private def now[X](x: => X): Future[X] = Future(x)

  Files.createDirectories(config.dir)
  val store: HostStore = HostStore(config.dir.resolve("okay.sqlite"))

  // ---- KV, in the store
  val kv: Port[Kv] = new Port[Kv]:
    def apply[X](op: Kv[X]): Future[X] = now[X](op match
      case Kv.Get(ns, key) => store.kvGet(ns, key, clock()).map((v, m) => KvEntry(v, m))
      case Kv.Put(ns, key, value, ttl, meta) => store.kvPut(ns, key, value, meta, ttl.map(clock() + _ * 1000))
      case Kv.Delete(ns, key) => store.kvDelete(ns, key)
      case Kv.List(ns, prefix, cursor, limit) =>
        val (keys, more) = store.kvList(ns, prefix, cursor, limit, clock())
        Page(keys.toList, if more then keys.lastOption else None))

  // ---- blobs, as files: `<key>` escaped, its info beside it
  private val blobsDir = config.dir.resolve("blobs")
  private def blobFile(bucket: String, key: String): Path =
    blobsDir.resolve(okay.http.Query.encode(bucket)).resolve(okay.http.Query.encode(key))
  private def info(bucket: String, key: String): Option[BlobInfo] =
    val f = blobFile(bucket, key)
    val meta = f.resolveSibling(f.getFileName.toString + ".info")
    Option.when(Files.isRegularFile(f) && Files.isRegularFile(meta)) {
      val m = HostStore.unlines(Files.readString(meta))
      BlobInfo(key, Files.size(f), m.getOrElse("etag", ""), m.get("content-type"),
        m.collect { case (k, v) if k.startsWith("meta.") => k.stripPrefix("meta.") -> v })
    }
  val blobs: Port[Blobs] = new Port[Blobs]:
    def apply[X](op: Blobs[X]): Future[X] = now[X](synchronized[X](op match
      case Blobs.Put(bucket, key, body, ct, meta) =>
        val f = blobFile(bucket, key)
        Files.createDirectories(f.getParent)
        val bytes = body.toArray
        val etag = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).take(8)
          .map(b => f"${b & 0xff}%02x").mkString
        Files.write(f, bytes)
        Files.writeString(f.resolveSibling(f.getFileName.toString + ".info"), HostStore.lines(
          Map("etag" -> etag) ++ ct.map("content-type" -> _) ++ meta.map((k, v) => s"meta.$k" -> v)))
        BlobInfo(key, bytes.length.toLong, etag, ct, meta)
      case Blobs.Get(bucket, key, range) => info(bucket, key).map { i =>
          val all = Files.readAllBytes(blobFile(bucket, key))
          (i, Bytes(range.fold(all)((from, until) => all.slice(from.toInt, math.min(until, all.length.toLong).toInt))))
        }
      case Blobs.Head(bucket, key) => info(bucket, key)
      case Blobs.Delete(bucket, keys) => keys.foreach { k =>
          val f = blobFile(bucket, k)
          Files.deleteIfExists(f)
          Files.deleteIfExists(f.resolveSibling(f.getFileName.toString + ".info"))
        }
      case Blobs.List(bucket, prefix, cursor, limit) =>
        val dir = blobsDir.resolve(okay.http.Query.encode(bucket))
        val keys =
          if !Files.isDirectory(dir) then Vector.empty
          else
            val s = Files.list(dir)
            try s.iterator().asScala.map(_.getFileName.toString).filterNot(_.endsWith(".info"))
              .map(okay.http.Query.decode).toVector.sorted
            finally s.close()
        val all = keys.filter(k => k.startsWith(prefix) && cursor.forall(k > _)).flatMap(info(bucket, _))
        val (page, rest) = all.splitAt(limit)
        Page(page.toList, if rest.isEmpty then None else page.lastOption.map(_.key))))

  // ---- queues: sent into the store; due ones delivered by the host's tick
  val queues: Port[Queues] = new Port[Queues]:
    def apply[X](op: Queues[X]): Future[X] = now[X](op match
      case Queues.Send(queue, messages) =>
        messages.foreach(m => store.send(queue, m.body, clock() + m.delaySeconds * 1000, clock())))

  /** each message due, to `app`'s consumer once: acknowledged, gone; retried, again later — until `maxAttempts`,
   * then given up into the dead table. How many were delivered */
  def deliver(app: App): Future[Int] =
    val due = store.due(clock())
    Future.traverse(due) { (id, queue, body, attempts, ts) =>
      val m = Message(s"m$id", body, attempts + 1, ts)
      platform.future(app.queue(queue, m)).recover { case _ => Settle.Retry() }.map {
        case Settle.Ack => ()
        case Settle.Retry(delay) =>
          if m.attempts >= config.maxAttempts then store.bury(id, queue, body, m.attempts)
          else store.retry(id, queue, body, m.attempts, clock() + delay * 1000, ts)
      }
    }.map(_.size)

  val fetch: JdkFetch = JdkFetch()

  // ---- cache, in memory
  private val cached = mutable.Map.empty[String, (Response, Long)]
  val cache: Port[Cache] = new Port[Cache]:
    def apply[X](op: Cache[X]): Future[X] = now[X](synchronized[X](op match
      case Cache.Match(url) => cached.get(url).collect { case (r, exp) if exp > clock() => r }
      case Cache.Put(url, r, ttl) => cached(url) = (r, clock() + ttl * 1000)
      case Cache.Delete(url) => cached.remove(url).isDefined))

  // ---- configuration: the host's variables, then the process's environment
  val env: Port[Env] = new Port[Env]:
    def apply[X](op: Env[X]): Future[X] = now[X](op match
      case Env.Var(name) => config.vars.get(name).orElse(sys.env.get(name))
      case Env.Secret(name) => config.vars.get(name).orElse(sys.env.get(name)))

  val services: Port[Services] = new Port[Services]:
    def apply[X](op: Services[X]): Future[X] = op match
      case Services.Call(name, r) => config.services.get(name) match
        case Some(app) => serve(app)(r)
        case None => Future.failed(java.util.NoSuchElementException(s"no service $name"))

  // ---- rate limits, in memory
  private val windows = mutable.Map.empty[(String, String), (Long, Int)]
  val limits: Port[Limits] = new Port[Limits]:
    def apply[X](op: Limits[X]): Future[X] = now[X](synchronized[X](op match
      case Limits.Take(name, key) =>
        val (limit, period) =
          config.limits.getOrElse(name, throw java.util.NoSuchElementException(s"no rate limit $name"))
        val window = clock() / (period * 1000)
        val used = windows.get((name, key)).collect { case (w, n) if w == window => n }.getOrElse(0)
        if used < limit then { windows((name, key)) = (window, used + 1); true } else false))

  val ai: Port[Ai] = config.ai.fold(Port.missing[Ai]("AI: no model server configured"))(OpenAiAi(_, fetch))

  // ---- vectors, in the store: every one compared
  private def numbers(j: String): List[Double] =
    Descent.parse(j).toOption.collect { case Json.Arr(xs) => xs.collect { case Json.Num(n) => n.toDouble }.toList }
      .getOrElse(Nil)
  val vectors: Port[Vectors] = new Port[Vectors]:
    def apply[X](op: Vectors[X]): Future[X] = now[X](op match
      case Vectors.Upsert(index, vs) => vs.foreach(v => store.upsert(index, v.id,
          Json.print(Json.Arr(v.values.toVector.map(Json.num))), HostStore.lines(v.metadata)))
      case Vectors.Delete(index, ids) => ids.foreach(store.removeVector(index, _))
      case Vectors.Query(index, values, topK, filter) =>
        def norm(xs: List[Double]) = math.sqrt(xs.map(x => x * x).sum)
        store.vectors(index).flatMap { (id, vs, m) =>
          val meta = HostStore.unlines(m)
          val v = numbers(vs)
          Option.when(filter.forall((k, x) => meta.get(k).contains(x)))(
            Nearest(id, v.zip(values).map(_ * _).sum / (norm(v) * norm(values)), meta))
        }.toList.sortBy(-_.score).take(topK))

  val analytics: Port[Analytics] = new Port[Analytics]:
    def apply[X](op: Analytics[X]): Future[X] = now[X](op match
      case Analytics.Write(dataset, point) => config.log(Level.Info, s"analytics $dataset $point"))

  val tcp: Port[Tcp] = JdkTcp().port

  // ---- mail: by SMTP over the JDK's sockets, a binding's server the configuration's
  val mails: Port[Mails] = new Port[Mails]:
    private def deliver(binding: String, mail: Mail): Future[Either[String, Unit]] =
      config.smtp.get(binding) match
        case None => Future.successful(Left(s"no SMTP server for $binding"))
        case Some(server) =>
          val id = s"<${java.util.UUID.randomUUID()}@${server.helo}>"
          Async.toFuture(Port.handle(tcp)(Smtp.deliver[Async + Tcp](server, mail, id, clock())))
    def apply[X](op: Mails[X]): Future[X] = op match
      case Mails.Deliver(binding, mail) => deliver(binding, mail)
      case Mails.Send(binding, mail) => deliver(binding, mail).flatMap {
          case Right(()) => Future.unit
          case Left(why) => Future.failed(RuntimeException(s"mail not sent: $why"))
        }

  // ---- objects: an instance a name, its requests one at a time, its state in the store
  private final class Instance(val ns: String, val name: String):
    private var queue: Future[Any] = Future.unit
    def actor: Actor = actors.getOrElse(ns, throw java.util.NoSuchElementException(s"no actor class $ns"))
    def one[A](p: A ! InActor): Future[A] =
      val promise = Promise[A]()
      synchronized {
        queue = queue.recover(_ => ()).flatMap(_ =>
          Async.toFuture(hosting.run(platform)(p)).andThen { case t => promise.complete(t) })
      }
      promise.future
    val hosting: Hosting = new Hosting:
      val storage: Port[Storage] = new Port[Storage]:
        def apply[X](op: Storage[X]): Future[X] = now[X](op match
          case Storage.Read(keys) => store.read(ns, name, keys)
          case Storage.Keys(prefix) => store.keys(ns, name, prefix)
          case Storage.Commit(r, w, a) => store.commit(ns, name, r, w, a))
      val sockets: Port[Sockets] = new Port[Sockets]:
        def apply[X](op: Sockets[X]): Future[X] = now[X](op match
          case Sockets.Accept(_, tags) => opened(tags, stream = false)
          case Sockets.Stream(_, tags) => opened(tags, stream = true)
          case Sockets.Send(s, text) => Option(open.get(s.id)).foreach(_.send(text))
          case Sockets.Close(s, code, reason) => Option(open.remove(s.id)).foreach(_.close(code, reason))
          case Sockets.Open(tag) => open.values.asScala.collect {
              case h if (h.at eq Instance.this) && !h.stream && tag.forall(h.socket.tags.contains) => h.socket
            }.toList
          case Sockets.Later(_) => throw IllegalStateException("later is the host's"))
      private def opened(tags: List[String], stream: Boolean): Response =
        val s = Socket(s"$ns:$name:${java.util.UUID.randomUUID()}", tags)
        open.put(s.id, HostSocket(s, Instance.this, stream)): Unit
        if stream then Response(200, Map("okay-socket" -> s.id, "content-type" -> "text/event-stream"))
        else Response(101, Map("okay-socket" -> s.id))
      override def keep(work: Future[Unit]): Unit =
        work.failed.foreach(e => config.log(Level.Error, s"$ns/$name: work after the answer: ${e.getMessage}"))

  private val instances = ConcurrentHashMap[(String, String), Instance]()
  private def instance(ns: String, name: String): Instance =
    instances.computeIfAbsent((ns, name), _ => Instance(ns, name))

  /**
   * A SOCKET AN OBJECT ACCEPTED: what it is sent waits until the host's server binds a connection to it (`bind`),
   * then goes out as it is sent; closed by the object, its connection closed.
   */
  final class HostSocket private[HostCloud] (val socket: Socket, private[HostCloud] val at: Instance,
                                             val stream: Boolean):
    private var out: Option[Wire] = None
    private val waiting = mutable.ListBuffer.empty[String]
    def bind(wire: Wire): Unit = synchronized {
      out = Some(wire)
      waiting.foreach(wire.text)
      waiting.clear()
    }
    def send(text: String): Unit = synchronized(out match
      case Some(w) => w.text(text)
      case None => waiting += text)
    def close(code: Int, reason: String): Unit = synchronized(out.foreach(_.close(code, reason)))
    /** a text from the client, to the object */
    def received(text: String): Future[Unit] = at.one(at.actor.message(at.name, socket, text))
    /** the client gone, to the object */
    def ended(): Future[Unit] =
      open.remove(socket.id)
      at.one(at.actor.closed(at.name, socket))

  /** where a socket's texts go: the host's connection */
  trait Wire:
    def text(t: String): Unit
    def close(code: Int, reason: String): Unit

  private val open = ConcurrentHashMap[String, HostSocket]()
  /** the socket an object's answer named (`okay-socket`) */
  def socket(id: String): Option[HostSocket] = Option(open.get(id))

  val objects: Port[Objects] = new Port[Objects]:
    def apply[X](op: Objects[X]): Future[X] = op match
      case Objects.Call(ns, name, r) =>
        val i = instance(ns, name)
        i.one(i.actor.fetch(name, r))
      case Objects.Unique(ns) => now(s"${ns.toLowerCase}-${java.util.UUID.randomUUID()}")

  /** each object whose alarm is due, its `alarm` run (cleared first, as a cloud does) */
  def fireAlarms(): Future[Int] =
    val due = store.dueAlarms(clock())
    Future.traverse(due)((ns, name) => instance(ns, name).pipe(i => i.one(i.actor.alarm(name)))).map(_.size)

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  /** A REQUEST TO AN APP: admitted (`App.admit`), then to its object as it is (`objectFor`) or to `fetch` */
  def serve(app: App)(request: Request): Future[Response] =
    platform.future(app.admit(request)).flatMap {
      case Left(refused) => Future.successful(refused)
      case Right(r) => app.objectFor(r) match
        case Some((ns, name)) => objects(Objects.Call(ns, name, r))
        case None => platform.future(app.fetch(r))
    }

  lazy val platform: Platform = config.adjust(Platform(kv = kv, blobs = blobs,
    sql = SqliteSql(Some(config.dir.resolve("sql").toFile.tap(_.mkdirs()))), queues = queues, fetch = fetch,
    cache = cache, env = env, services = services, limits = limits, ai = ai, vectors = vectors,
    analytics = analytics, mails = mails, objects = objects, tcp = tcp, log = config.log, now = config.clock))

  extension [A](a: A) private def tap(f: A => Any): A = { f(a); a }

  def close(): Unit = store.close()
