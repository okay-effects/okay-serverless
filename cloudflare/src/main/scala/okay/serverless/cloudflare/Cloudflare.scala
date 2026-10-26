package okay.serverless.cloudflare

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.Storage
import okay.serverless.cloud.*

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}
import scala.scalajs.js.JSConverters.*

import WebJs.{*, given}

/**
 * THE PORTS ON CLOUDFLARE: each effect by its binding in `env` — the resource's name the binding's (KV namespaces,
 * R2 buckets, D1 databases, queues, services, rate limiters, Vectorize indexes, Analytics Engine datasets, `send_email`
 * bindings, Durable Object namespaces); a variable or a secret `env`'s; the cache `caches.default`; a model `env.AI`.
 */
final class CloudflarePorts(env: js.Dynamic, ai: String = "AI"):
  private def binding(name: String): js.Dynamic =
    val b = env.selectDynamic(name)
    if js.isUndefined(b) then throw java.util.NoSuchElementException(s"no binding $name") else b
  private def guard[X](f: => Future[X]): Future[X] = try f catch case e: Throwable => Future.failed(e)

  val kv: Port[Kv] = new Port[Kv]:
    def apply[X](op: Kv[X]): Future[X] = guard[X](op match
      case Kv.Get(ns, key) => await(binding(ns).getWithMetadata(key, obj(`type` = "text"))).map(r =>
        str(r.value).map(v => KvEntry(v, if r.metadata == null then None else Some(g.JSON.stringify(r.metadata)
          .asInstanceOf[String]))))
      case Kv.Put(ns, key, value, ttl, meta) =>
        val o = obj()
        ttl.foreach(t => o.expirationTtl = math.max(t, 60L).toDouble)
        meta.foreach(m => o.metadata = g.JSON.parse(m))
        await(binding(ns).put(key, value, o)).map(_ => ())
      case Kv.Delete(ns, key) => await(binding(ns).delete(key)).map(_ => ())
      case Kv.List(ns, prefix, cursor, limit) =>
        val o = obj(prefix = prefix, limit = limit)
        cursor.foreach(c => o.cursor = c)
        await(binding(ns).list(o)).map(r => Page(r.keys.asInstanceOf[js.Array[js.Dynamic]].toList
          .map(_.name.asInstanceOf[String]), if r.list_complete.asInstanceOf[Boolean] then None else str(r.cursor))))

  private def info(o: js.Dynamic): BlobInfo =
    val custom = o.customMetadata
    BlobInfo(o.key.asInstanceOf[String], o.size.asInstanceOf[Double].toLong, o.etag.asInstanceOf[String],
      if js.isUndefined(o.httpMetadata) then None else str(o.httpMetadata.contentType),
      if js.isUndefined(custom) then Map.empty else custom.asInstanceOf[js.Dictionary[String]].toMap)

  val blobs: Port[Blobs] = new Port[Blobs]:
    def apply[X](op: Blobs[X]): Future[X] = guard[X](op match
      case Blobs.Put(bucket, key, body, ct, meta) =>
        val o = obj(customMetadata = meta.toJSDictionary)
        ct.foreach(c => o.httpMetadata = obj(contentType = c))
        await(binding(bucket).put(key, uint8(body), o)).map(info)
      case Blobs.Get(bucket, key, range) =>
        val o = obj()
        range.foreach((from, until) => o.range = obj(offset = from.toDouble, length = (until - from).toDouble))
        await(binding(bucket).get(key, o)).flatMap(r =>
          if r == null then Future.successful(None) else await(r.arrayBuffer()).map(b => Some((info(r), bytes(b)))))
      case Blobs.Head(bucket, key) =>
        await(binding(bucket).head(key)).map(r => if r == null then None else Some(info(r)))
      case Blobs.Delete(bucket, keys) => await(binding(bucket).delete(keys.toJSArray)).map(_ => ())
      case Blobs.List(bucket, prefix, cursor, limit) =>
        val o = obj(prefix = prefix, limit = limit, include = js.Array("httpMetadata", "customMetadata"))
        cursor.foreach(c => o.cursor = c)
        await(binding(bucket).list(o)).map(r => Page(r.objects.asInstanceOf[js.Array[js.Dynamic]].toList.map(info),
          if r.truncated.asInstanceOf[Boolean] then str(r.cursor) else None)))

  private def param(v: SqlValue): js.Any = v match
    case SqlValue.Null => null
    case SqlValue.Int(n) => n.toDouble
    case SqlValue.Real(d) => d
    case SqlValue.Text(t) => t
    case SqlValue.Blob(b) => uint8(b).buffer
  private def value(d: js.Dynamic): SqlValue =
    if d == null || js.isUndefined(d) then SqlValue.Null
    else js.typeOf(d) match
      case "number" =>
        val n = d.asInstanceOf[Double]
        if n.isWhole && math.abs(n) < 9.007199254740992e15 then SqlValue.Int(n.toLong) else SqlValue.Real(n)
      case "string" => SqlValue.Text(d.asInstanceOf[String])
      case _ => if js.Array.isArray(d) then SqlValue.Blob(Bytes(d.asInstanceOf[js.Array[Double]].map(_.toByte)
          .toArray)) else SqlValue.Blob(bytes(d))
  private def rows(r: js.Dynamic): List[Row] =
    r.results.asInstanceOf[js.Array[js.Dictionary[js.Dynamic]]].toList.map(_.toMap.map((k, v) => k -> value(v)))
  private def prepare(db: js.Dynamic, s: Statement): js.Dynamic =
    db.prepare(s.sql).applyDynamic("bind")(s.params.map(param)*)

  val sql: Port[Sql] = new Port[Sql]:
    def apply[X](op: Sql[X]): Future[X] = guard[X](op match
      case Sql.Query(db, s) => await(prepare(binding(db), s).all()).map(rows)
      case Sql.Run(db, s) => await(prepare(binding(db), s).run()).map(r =>
        Changes(r.meta.changes.asInstanceOf[Double].toLong, r.meta.last_row_id.asInstanceOf[Double].toLong))
      case Sql.Batch(db, ss) =>
        val d = binding(db)
        await(d.batch(ss.map(prepare(d, _)).toJSArray)).map(_.asInstanceOf[js.Array[js.Dynamic]].toList.map(rows)))

  val queues: Port[Queues] = new Port[Queues]:
    def apply[X](op: Queues[X]): Future[X] = guard[X](op match
      case Queues.Send(queue, messages) => await(binding(queue).sendBatch(messages.map(m =>
          obj(body = m.body, delaySeconds = m.delaySeconds.toDouble)).toJSArray)).map(_ => ()))

  val fetch: Port[Fetch] = WebJs.fetch

  val cache: Port[Cache] = new Port[Cache]:
    private def default = g.caches.default
    def apply[X](op: Cache[X]): Future[X] = guard[X](op match
      case Cache.Match(url) => await(default.`match`(url)).flatMap(r =>
        if js.isUndefined(r) || r == null then Future.successful(None) else response(r).map(Some(_)))
      case Cache.Put(url, r, ttl) =>
        await(default.put(url, toResponse(r.copy(headers = r.headers + ("cache-control" -> s"max-age=$ttl")))))
          .map(_ => ())
      case Cache.Delete(url) => await(default.delete(url)).map(_.asInstanceOf[Boolean]))

  val envPort: Port[Env] = new Port[Env]:
    def apply[X](op: Env[X]): Future[X] = guard[X](op match
      case Env.Var(name) => Future.successful(str(env.selectDynamic(name)).filter(_ => js.typeOf(env.selectDynamic(
          name)) == "string"))
      case Env.Secret(name) => Future.successful(str(env.selectDynamic(name)).filter(_ => js.typeOf(env.selectDynamic(
          name)) == "string")))

  val services: Port[Services] = new Port[Services]:
    def apply[X](op: Services[X]): Future[X] = guard[X](op match
      case Services.Call(name, r) => await(binding(name).fetch(toRequest(r))).flatMap(response))

  val limits: Port[Limits] = new Port[Limits]:
    def apply[X](op: Limits[X]): Future[X] = guard[X](op match
      case Limits.Take(name, key) => await(binding(name).limit(obj(key = key))).map(_.success.asInstanceOf[Boolean]))

  val aiPort: Port[Ai] = new Port[Ai]:
    def apply[X](op: Ai[X]): Future[X] = guard[X](op match
      case Ai.Run(model, input) => await(binding(ai).run(model, fromJson(input))).map(r => toJson(r)))

  val vectors: Port[Vectors] = new Port[Vectors]:
    def apply[X](op: Vectors[X]): Future[X] = guard[X](op match
      case Vectors.Upsert(index, vs) => await(binding(index).upsert(vs.map(v => obj(id = v.id,
          values = v.values.toJSArray, metadata = v.metadata.toJSDictionary)).toJSArray)).map(_ => ())
      case Vectors.Delete(index, ids) => await(binding(index).deleteByIds(ids.toJSArray)).map(_ => ())
      case Vectors.Query(index, values, topK, filter) =>
        val o = obj(topK = topK, returnMetadata = "all")
        if filter.nonEmpty then o.filter = filter.toJSDictionary
        await(binding(index).query(values.toJSArray, o)).map(_.matches.asInstanceOf[js.Array[js.Dynamic]].toList
          .map(m => Nearest(m.id.asInstanceOf[String], m.score.asInstanceOf[Double],
            if js.isUndefined(m.metadata) then Map.empty else m.metadata.asInstanceOf[js.Dictionary[js.Any]].toMap
              .map((k, v) => k -> v.toString)))))

  val analytics: Port[Analytics] = new Port[Analytics]:
    def apply[X](op: Analytics[X]): Future[X] = guard[X](op match
      case Analytics.Write(dataset, p) => Future.successful(binding(dataset).writeDataPoint(obj(
          indexes = p.indexes.toJSArray, blobs = p.blobs.toJSArray, doubles = p.doubles.toJSArray))).map(_ => ()))

  val mails: Port[Mails] = new Port[Mails]:
    def apply[X](op: Mails[X]): Future[X] = guard[X](op match
      case Mails.Send(b, m) =>
        val raw = s"From: ${m.from}\r\nTo: ${m.to}\r\nSubject: ${m.subject}\r\n" +
          s"Message-ID: <${g.crypto.randomUUID()}@okay>" +
          s"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${m.text}"
        await(binding(b).send(js.Dynamic.newInstance(g.OkayEmailMessage)(m.from, m.to, raw))).map(_ => ()))

  val objects: Port[Objects] = new Port[Objects]:
    def apply[X](op: Objects[X]): Future[X] = guard[X](op match
      case Objects.Call(ns, name, r) =>
        val n = binding(ns)
        await(n.get(n.idFromName(name)).fetch(toRequest(r.copy(headers = r.headers + ("okay-object-name" -> name)))))
          .flatMap(response))

  val platform: Platform = Platform(kv = kv, blobs = blobs, sql = sql, queues = queues, fetch = fetch, cache = cache,
    env = envPort, services = services, limits = limits, ai = aiPort, vectors = vectors, analytics = analytics,
    mails = mails, objects = objects, log = (l, m) => g.console.log(s"[$l] $m"), now = () => js.Date.now().toLong)

/** AN OBJECT'S SOCKETS on Cloudflare: the hibernation API — each socket tagged by its id, its tags its attachment */
final class ObjectSockets(ctx: js.Dynamic) extends Port[Sockets]:
  /** the client ends of sockets just accepted, until their 101 leaves */
  val accepted: scala.collection.mutable.Map[String, js.Dynamic] = scala.collection.mutable.Map.empty
  private def socket(ws: js.Dynamic): Socket =
    val a = ws.deserializeAttachment()
    Socket(a.id.asInstanceOf[String], a.tags.asInstanceOf[js.Array[String]].toList)
  def of(ws: js.Dynamic): Socket = socket(ws)
  private def find(s: Socket): Option[js.Dynamic] =
    ctx.getWebSockets(s"id:${s.id}").asInstanceOf[js.Array[js.Dynamic]].headOption

  def apply[X](op: Sockets[X]): Future[X] = Future.fromTry(scala.util.Try[X](op match
    case Sockets.Accept(_, tags) =>
      val pair = js.Dynamic.newInstance(g.WebSocketPair)()
      val (client, server) = (pair.selectDynamic("0"), pair.selectDynamic("1"))
      val id = g.crypto.randomUUID().asInstanceOf[String]
      ctx.acceptWebSocket(server, (s"id:$id" :: tags).toJSArray)
      server.serializeAttachment(obj(id = id, tags = tags.toJSArray))
      accepted(id) = client
      Response(101, Map("okay-socket" -> id))
    case Sockets.Send(s, text) => find(s).foreach(_.send(text))
    case Sockets.Close(s, code, reason) => find(s).foreach(_.close(code, reason))
    case Sockets.Open(tag) => ctx.getWebSockets(tag.orUndefined).asInstanceOf[js.Array[js.Dynamic]].toList.map(socket)))

/**
 * A WORKER ON CLOUDFLARE: an app and its actors (by their namespaces' bindings) given their entry points —
 * `export default { fetch, scheduled, queue, email }`, and each Durable Object class's `fetch`, `alarm`,
 * `webSocketMessage`, `webSocketClose` (cloudflare/example/entry.js shows the few lines of JavaScript)
 */
final class Worker(app: App, actors: Map[String, Actor]):
  private def ports(env: js.Dynamic) = CloudflarePorts(env)

  @scala.scalajs.js.annotation.JSExport
  def fetch(request: js.Dynamic, env: js.Dynamic, ctx: js.Dynamic): js.Promise[js.Dynamic] =
    val head = Request(request.method.asInstanceOf[String], request.url.asInstanceOf[String], headers(request.headers))
    app.objectFor(head) match
      case Some((ns, name)) => // to the object as it is: its body, its upgrade
        val h = js.Dynamic.newInstance(g.Headers)(request.headers)
        h.set("okay-object-name", name)
        val n = env.selectDynamic(ns)
        n.get(n.idFromName(name)).fetch(js.Dynamic.newInstance(g.Request)(request, obj(headers = h)))
          .asInstanceOf[js.Promise[js.Dynamic]]
      case None =>
        WebJs.request(request).flatMap(r => ports(env).platform.future(app.fetch(r))).map(toResponse).toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def scheduled(event: js.Dynamic, env: js.Dynamic, ctx: js.Dynamic): js.Promise[Unit] =
    ports(env).platform.future(app.scheduled(Scheduled(event.cron.asInstanceOf[String],
      event.scheduledTime.asInstanceOf[Double].toLong))).toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def queue(batch: js.Dynamic, env: js.Dynamic, ctx: js.Dynamic): js.Promise[Unit] =
    val p = ports(env).platform
    val queue = batch.queue.asInstanceOf[String]
    batch.messages.asInstanceOf[js.Array[js.Dynamic]].toList.foldLeft(Future.unit)((f, m) => f.flatMap { _ =>
      val message = Message(m.id.asInstanceOf[String], m.body.toString, m.attempts.asInstanceOf[Int],
        js.Date.parse(m.timestamp.toString).toLong)
      p.future(app.queue(queue, message)).recover { case _ => Settle.Retry() }.map {
        case Settle.Ack => m.ack()
        case Settle.Retry(delay) => m.retry(obj(delaySeconds = delay.toDouble))
      }.map(_ => ())
    }).toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def email(message: js.Dynamic, env: js.Dynamic, ctx: js.Dynamic): js.Promise[Unit] =
    await(js.Dynamic.newInstance(g.Response)(message.raw).text()).flatMap(raw =>
      ports(env).platform.future(app.mail(IncomingMail(message.from.asInstanceOf[String],
        message.to.asInstanceOf[String], raw.asInstanceOf[String])))).toJSPromise

  // ---- objects
  private val hosted = scala.collection.mutable.Map.empty[js.Dynamic, (ObjectStorage, ObjectSockets)]
  private def host(ctx: js.Dynamic) = hosted.getOrElseUpdate(ctx, (ObjectStorage(ctx), ObjectSockets(ctx)))
  private def hosting(ctx: js.Dynamic): Hosting =
    val (st, so) = host(ctx)
    new Hosting:
      val storage: Port[Storage] = st.port
      val sockets: Port[Sockets] = so
  private def actor(ns: String) = actors.getOrElse(ns, throw java.util.NoSuchElementException(s"no actor for $ns"))
  /** the object's name: told by the first request (`okay-object-name`), kept for its alarms and sockets */
  private def name(ctx: js.Dynamic, told: Option[String]): String =
    val sql = ctx.storage.sql
    sql.exec("CREATE TABLE IF NOT EXISTS okay_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    told match
      case Some(n) =>
        sql.exec("INSERT INTO okay_meta (key, value) VALUES ('name', ?) ON CONFLICT(key) DO NOTHING", n)
        n
      case None => sql.exec("SELECT value FROM okay_meta WHERE key = 'name'").toArray()
          .asInstanceOf[js.Array[js.Dynamic]].headOption.map(_.value.asInstanceOf[String])
          .getOrElse(ctx.id.toString().asInstanceOf[String])
  private def run[A](ctx: js.Dynamic, env: js.Dynamic)(p: A ! InActor): Future[A] =
    Async.toFuture(hosting(ctx).run(ports(env).platform)(p))

  @scala.scalajs.js.annotation.JSExport
  def objectFetch(ns: String, ctx: js.Dynamic, env: js.Dynamic, request: js.Dynamic): js.Promise[js.Dynamic] =
    WebJs.request(request).flatMap { r =>
      val n = name(ctx, r.header("okay-object-name"))
      run(ctx, env)(actor(ns).fetch(n, r.copy(headers = r.headers - "okay-object-name"))).map { res =>
        res.header("okay-socket").flatMap(host(ctx)._2.accepted.remove) match
          case Some(client) => js.Dynamic.newInstance(g.Response)(null, obj(status = 101, webSocket = client))
          case None => toResponse(res)
      }
    }.toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def objectAlarm(ns: String, ctx: js.Dynamic, env: js.Dynamic): js.Promise[Unit] =
    run(ctx, env)(actor(ns).alarm(name(ctx, None))).toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def objectMessage(ns: String, ctx: js.Dynamic, env: js.Dynamic, ws: js.Dynamic, message: js.Dynamic)
    : js.Promise[Unit] =
    run(ctx, env)(actor(ns).message(name(ctx, None), host(ctx)._2.of(ws), message.toString)).toJSPromise

  @scala.scalajs.js.annotation.JSExport
  def objectClose(ns: String, ctx: js.Dynamic, env: js.Dynamic, ws: js.Dynamic): js.Promise[Unit] =
    run(ctx, env)(actor(ns).closed(name(ctx, None), host(ctx)._2.of(ws))).toJSPromise
