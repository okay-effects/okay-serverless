package okay.serverless.aws

import okay.effects.json.Json
import okay.scala3.std.*
import okay.serverless.Storage
import okay.serverless.cloud.*

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}
import scala.scalajs.js.JSConverters.*

/**
 * THE PORTS ON AWS — what DynamoDB carries, in the table `table` (`space` its partition key, `key` its sort key):
 *  - `Kv`: a namespace a space `kv/<ns>`, a value and its metadata an item, its ttl `expires` (epoch seconds — the
 *    table's TTL attribute; an expired item read as gone, as DynamoDB deletes it late);
 *  - `Limits`: a window's count an item `limit/<name>`, `<key>/<window>`, taken by a conditional `ADD`: atomic among
 *    all instances; `limits` the limit and the period (seconds) by name;
 *  - `Objects`: an actor in this process (work after an answer, `Sockets.later`, not kept: a Lambda is frozen when
 *    it has answered — a queue's message is the way), its state a space `obj/<namespace>/<name>`
 *    (`DynamoStorage`: steps
 *    conditioned, so instances at once stay right); its alarm given to `schedule` (EventBridge Scheduler's, the
 *    caller's) and come back as an `okayAlarm` event;
 *  - `Env` the process's, `Fetch` Node's, `Log` the console (CloudWatch Logs), `Analytics` CloudWatch's embedded
 *    metric format, written to the log.
 *  - `Sql` on Postgres (`AwsPorts.postgres`): RDS Proxy, as Hyperdrive on Cloudflare, by `pg`, a pool a database.
 * Blobs (S3), queues out (SQS), mail (SES), AI (Bedrock), vectors (S3 Vectors), services
 * (Lambda invoke) are mapped in specs/cloud.md and not written yet: their ports fail, naming them.
 */
final class AwsPorts(client: js.Dynamic, table: String, limits: Map[String, (Int, Long)] = Map.empty,
                     actors: Map[String, Actor] = Map.empty,
                     schedule: (String, String, Long) => Unit = (_, _, _) => (),
                     log: String => Unit = s => g.console.log(s),
                     sql: Port[Sql] = Port.missing("SQL (AwsPorts.postgres: RDS Proxy, Aurora, any Postgres)"))(
  using ExecutionContext):
  private val sdk = g.require("@aws-sdk/client-dynamodb")
  private def send(command: String, input: js.Object): Future[js.Dynamic] =
    client.send(js.Dynamic.newInstance(sdk.selectDynamic(command))(input)).asInstanceOf[js.Promise[js.Dynamic]].toFuture
  private def s(v: String): js.Object = obj(S = v)
  private def n(v: Long): js.Object = obj(N = v.toString)
  private def nowSeconds: Long = (js.Date.now() / 1000).toLong
  private def conditionFailed(e: Any): Boolean = e match
    case js.JavaScriptException(x) =>
      x.asInstanceOf[js.Dynamic].name.asInstanceOf[String] == "ConditionalCheckFailedException"
    case _ => false

  val kv: Port[Kv] = new Port[Kv]:
    def apply[X](op: Kv[X]): Future[X] = op match
      case Kv.Get(ns, key) =>
        send("GetItemCommand", obj(TableName = table, Key = obj(space = s(s"kv/$ns"), key = s(key)),
          ConsistentRead = true)).map { r =>
          val i = r.Item
          if js.isUndefined(i) || (!js.isUndefined(i.expires) && i.expires.N.asInstanceOf[String].toLong <= nowSeconds)
          then None
          else Some(KvEntry(i.value.S.asInstanceOf[String],
            if js.isUndefined(i.meta) then None else Some(i.meta.S.asInstanceOf[String])))
        }
      case Kv.Put(ns, key, value, ttl, meta) =>
        val item = obj(space = s(s"kv/$ns"), key = s(key), value = s(value))
        ttl.foreach(t => item.expires = n(nowSeconds + t))
        meta.foreach(m => item.meta = s(m))
        send("PutItemCommand", obj(TableName = table, Item = item)).map(_ => ())
      case Kv.Delete(ns, key) =>
        send("DeleteItemCommand", obj(TableName = table, Key = obj(space = s(s"kv/$ns"), key = s(key)))).map(_ => ())
      case Kv.List(ns, prefix, cursor, limit) =>
        // DynamoDB takes no empty text in a key condition: no prefix, the whole namespace
        val input =
          if prefix.isEmpty then obj(TableName = table, ConsistentRead = true, Limit = limit,
            KeyConditionExpression = "#s = :s", ExpressionAttributeNames = obj("#s" -> "space"),
            ExpressionAttributeValues = obj(":s" -> s(s"kv/$ns")))
          else obj(TableName = table, ConsistentRead = true, Limit = limit,
            KeyConditionExpression = "#s = :s AND begins_with(#k, :p)",
            ExpressionAttributeNames = obj("#s" -> "space", "#k" -> "key"),
            ExpressionAttributeValues = obj(":s" -> s(s"kv/$ns"), ":p" -> s(prefix)))
        cursor.foreach(c => input.ExclusiveStartKey = obj(space = s(s"kv/$ns"), key = s(c)))
        send("QueryCommand", input).map { r =>
          val items = r.Items.asInstanceOf[js.Array[js.Dynamic]].toList
          val live = items.filter(i =>
            js.isUndefined(i.expires) || i.expires.N.asInstanceOf[String].toLong > nowSeconds)
          val next = if js.isUndefined(r.LastEvaluatedKey) then None else Some(r.LastEvaluatedKey.key.S.toString)
          Page(live.map(_.key.S.asInstanceOf[String]), next)
        }

  val limitsPort: Port[Limits] = new Port[Limits]:
    def apply[X](op: Limits[X]): Future[X] = op match
      case Limits.Take(name, key) => limits.get(name) match
        case None => Future.failed(java.util.NoSuchElementException(s"no rate limit $name"))
        case Some((limit, period)) =>
          val window = nowSeconds / period
          send("UpdateItemCommand", obj(TableName = table,
            Key = obj(space = s(s"limit/$name"), key = s(s"$key/$window")),
            UpdateExpression = "ADD #c :one SET #e = :e",
            ConditionExpression = "attribute_not_exists(#c) OR #c < :limit",
            ExpressionAttributeNames = obj("#c" -> "count", "#e" -> "expires"),
            ExpressionAttributeValues = obj(":one" -> n(1), ":limit" -> n(limit), ":e" -> n((window + 2) * period))))
            .map(_ => true).recover { case e if conditionFailed(e) => false }

  val env: Port[Env] = new Port[Env]:
    def apply[X](op: Env[X]): Future[X] = op match
      case Env.Var(name) => Future.successful(g.process.env.selectDynamic(name).asInstanceOf[js.UndefOr[String]]
          .toOption)
      case Env.Secret(name) => Future.successful(g.process.env.selectDynamic(name).asInstanceOf[js.UndefOr[String]]
          .toOption)

  /** CloudWatch's embedded metric format: the indexes dimensions, the doubles metrics, the blobs properties */
  val analytics: Port[Analytics] = new Port[Analytics]:
    def apply[X](op: Analytics[X]): Future[X] = op match
      case Analytics.Write(dataset, p) => Future {
          val dims = p.indexes.indices.map(i => s"index$i")
          val fields = (p.indexes.zipWithIndex.map((v, i) => s"index$i" -> Json.Str(v)) ++
            p.blobs.zipWithIndex.map((v, i) => s"blob$i" -> Json.Str(v)) ++
            p.doubles.zipWithIndex.map((v, i) => s"double$i" -> Json.Num(BigDecimal(v)))).toVector
          log(Json.print(Json.Obj(Vector("_aws" -> Json.obj("Timestamp" -> Json.num(js.Date.now().toLong),
            "CloudWatchMetrics" -> Json.arr(Json.obj("Namespace" -> Json.Str(dataset),
              "Dimensions" -> Json.arr(Json.Arr(dims.toVector.map(Json.Str(_)))),
              "Metrics" -> Json.Arr(p.doubles.indices.toVector.map(i => Json.obj("Name" -> Json.Str(s"double$i")))))))
          ) ++ fields)))
        }

  /** an object: its actor here, its storage its space */
  def hosting(ns: String, name: String): Hosting = new Hosting:
    val storage: Port[Storage] = DynamoStorage(client, table, s"obj/$ns/$name", at => schedule(ns, name, at)).port
    val sockets: Port[Sockets] = Port.missing("sockets (API Gateway WebSockets: not written yet)")

  def actor(ns: String): Actor = actors.getOrElse(ns, throw java.util.NoSuchElementException(s"no actor for $ns"))

  val objects: Port[Objects] = new Port[Objects]:
    def apply[X](op: Objects[X]): Future[X] = op match
      case Objects.Call(ns, name, r) => Async.toFuture(hosting(ns, name).run(platform)(actor(ns).fetch(name, r)))
      case Objects.Unique(_) => Future.successful(g.crypto.randomUUID().asInstanceOf[String])

  lazy val platform: Platform = Platform(kv = kv, limits = limitsPort, env = env, fetch = WebJs.fetch,
    analytics = analytics, objects = objects, sql = sql, log = (l, m) => log(s"[$l] $m"))

object AwsPorts:
  /** SQL on Postgres — RDS Proxy (AWS's Hyperdrive), Aurora, any — by `pg`, a pool a database, kept across the
   * Lambda's invocations: a database's URL `url`'s, by default the variable `DATABASE_URL_<db>` */
  def postgres(url: String => Option[String] = db =>
    g.process.env.selectDynamic(s"DATABASE_URL_$db").asInstanceOf[js.UndefOr[String]].toOption)(
    using ExecutionContext): Port[Sql] = PostgresSql(g.require("pg"), url, pooled = true)

/**
 * A LAMBDA: an app and its actors given AWS's events — API Gateway's HTTP API (payload 2.0): a request; SQS: each
 * message to the consumer, a retry a batch item failure (the queue's visibility timeout its delay); EventBridge's
 * schedule: `scheduled`; `{okayAlarm: {namespace, name}}` (EventBridge Scheduler, set by `schedule`): an object's
 * alarm. Export its `handler` as the Lambda's.
 */
final class Lambda(app: App, ports: AwsPorts)(using ExecutionContext):
  private def header(h: js.Dynamic): Map[String, String] =
    if js.isUndefined(h) || h == null then Map.empty else h.asInstanceOf[js.Dictionary[String]].toMap

  private def http(e: js.Dynamic): Future[js.Dynamic] =
    val headers = header(e.headers)
    val query = if js.isUndefined(e.rawQueryString) || e.rawQueryString.toString.isEmpty then ""
      else "?" + e.rawQueryString
    val raw = if js.isUndefined(e.body) || e.body == null then "" else e.body.toString
    val body = if e.isBase64Encoded.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false) then
      Bytes(g.Buffer.from(raw, "base64").asInstanceOf[js.typedarray.Uint8Array].toArray.map(_.toByte))
    else Bytes.of(raw)
    val r = Request(e.requestContext.http.method.asInstanceOf[String], s"${e.rawPath}$query")
      .copy(headers = headers.map((k, v) => k.toLowerCase -> List(v))).withContent(body)
    val answer = ports.platform.future(app.admit(r)).flatMap {
      case Left(refused) => Future.successful(refused)
      case Right(admitted) => app.objectFor(admitted) match
        case Some((ns, name)) => Async.toFuture(ports.hosting(ns, name).run(ports.platform)(ports.actor(ns).fetch(name,
            admitted)))
        case None => ports.platform.future(app.fetch(admitted))
    }
    answer.map(res => obj(statusCode = res.status, headers = res.headers.toJSDictionary,
      body = g.Buffer.from(WebJs.uint8(res.content)).applyDynamic("toString")("base64"), isBase64Encoded = true))

  private def sqs(records: js.Array[js.Dynamic]): Future[js.Dynamic] =
    Future.traverse(records.toList) { m =>
      val queue = m.eventSourceARN.toString.split(':').last
      val message = Message(m.messageId.toString, m.body.toString,
        m.attributes.ApproximateReceiveCount.toString.toInt, m.attributes.SentTimestamp.toString.toLong)
      ports.platform.future(app.queue(queue, message)).recover { case _ => Settle.Retry() }.map {
        case Settle.Ack => None
        case Settle.Retry(_) => Some(message.id)
      }
    }.map(failed => obj(batchItemFailures = failed.flatten.map(id => obj(itemIdentifier = id)).toJSArray))

  def handle(event: js.Dynamic): Future[js.Any] =
    if !js.isUndefined(event.okayAlarm) then
      val a = event.okayAlarm
      val (ns, name) = (a.namespace.toString, a.name.toString)
      Async.toFuture(ports.hosting(ns, name).run(ports.platform)(ports.actor(ns).alarm(name))).map(_ => ())
    else if !js.isUndefined(event.requestContext) && !js.isUndefined(event.requestContext.http) then http(event)
    else if !js.isUndefined(event.Records) && event.Records.asInstanceOf[js.Array[js.Dynamic]].headOption
        .exists(_.eventSource.toString == "aws:sqs") then sqs(event.Records.asInstanceOf[js.Array[js.Dynamic]])
    else if event.selectDynamic("detail-type").toString == "Scheduled Event" then
      val rule = event.resources.asInstanceOf[js.Array[String]].headOption.getOrElse("")
      ports.platform.future(app.scheduled(Scheduled(rule, js.Date.parse(event.time.toString).toLong))).map(_ => ())
    else Future.failed(IllegalArgumentException(s"an event not known: ${g.JSON.stringify(event)}"))

  /** the Lambda's handler */
  val handler: js.Function2[js.Dynamic, js.Dynamic, js.Promise[js.Any]] = (event, _) => handle(event).toJSPromise
