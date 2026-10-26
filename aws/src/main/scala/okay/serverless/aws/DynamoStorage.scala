package okay.serverless.aws

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Storage, Versioned}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.Thenable.Implicits.*

/**
 * `Storage` ON DYNAMODB, for AWS Lambda (Scala.js on Node; the runtime's own `@aws-sdk/client-dynamodb`). A table of
 * items `(space, key) → value, version`: `space` a process's or a tenant's — every key of a step in one space. No
 * single writer: a COMMIT is one `TransactWriteItems` — each key read and written put on condition its version is
 * still the one read (or still absent), each key only read checked (`ConditionCheck`), each key written unread
 * updated as it is — so a conflict cancels it all and answers `false`, and the step runs again. Reads are strongly
 * consistent. The alarm is kept as an item (`#alarm`, the earliest) and given to `schedule` (EventBridge Scheduler's,
 * the caller's) once the commit is in.
 */
final class DynamoStorage(client: js.Dynamic, table: String, space: String, schedule: Long => Unit = _ => ())(
  using ExecutionContext):
  private val sdk = g.require("@aws-sdk/client-dynamodb")

  private def send(command: String, input: js.Object): Future[js.Dynamic] =
    client.send(js.Dynamic.newInstance(sdk.selectDynamic(command))(input)).asInstanceOf[js.Promise[js.Dynamic]].toFuture

  private def s(v: String): js.Object = js.Dynamic.literal(S = v)
  private def n(v: Long): js.Object = js.Dynamic.literal(N = v.toString)
  private def itemKey(k: String): js.Object = js.Dynamic.literal(space = s(space), key = s(k))

  def read(keys: List[String]): Future[Map[String, Versioned]] =
    Future.traverse(keys.distinct)(k => send("GetItemCommand", js.Dynamic.literal(TableName = table, Key = itemKey(k),
      ConsistentRead = true)).map { r =>
      val item = r.Item
      if js.isUndefined(item) then None
      else Some(k -> Versioned(item.value.S.asInstanceOf[String], item.version.N.asInstanceOf[String].toLong))
    }).map(_.flatten.toMap)

  def keys(prefix: String): Future[List[String]] =
    def page(from: js.UndefOr[js.Dynamic], acc: List[String]): Future[List[String]] =
      val input = js.Dynamic.literal(TableName = table, ConsistentRead = true,
        KeyConditionExpression = "#s = :s AND begins_with(#k, :p)",
        ExpressionAttributeNames = js.Dynamic.literal("#s" -> "space", "#k" -> "key"),
        ExpressionAttributeValues = js.Dynamic.literal(":s" -> s(space), ":p" -> s(prefix)))
      from.foreach(f => input.ExclusiveStartKey = f)
      send("QueryCommand", input).flatMap { r =>
        val got = r.Items.asInstanceOf[js.Array[js.Dynamic]].toList.map(_.key.S.asInstanceOf[String])
          .filterNot(_ == "#alarm")
        val next = r.LastEvaluatedKey
        if js.isUndefined(next) then Future.successful((acc ++ got).sorted)
        else page(next.asInstanceOf[js.Dynamic], acc ++ got)
      }
    page(js.undefined, Nil)

  def commit(read: Map[String, Long], writes: Map[String, Option[String]], alarm: Option[Long]): Future[Boolean] =
    val allNames = Map("#v" -> "value", "#n" -> "version", "#k" -> "key")
    /** an operation on one item: its expressions, and only the names and values they use (DynamoDB refuses others) */
    def op(k: String, update: Option[String], condition: Option[String], values: Map[String, js.Object]): js.Dynamic =
      val exprs = (update ++ condition).mkString(" ")
      val o = js.Dynamic.literal(TableName = table, Key = itemKey(k))
      update.foreach(u => o.UpdateExpression = u)
      condition.foreach(c => o.ConditionExpression = c)
      val ns = allNames.filter((t, _) => exprs.contains(t))
      if ns.nonEmpty then o.ExpressionAttributeNames = js.Dictionary(ns.toSeq*)
      val vs = values.filter((t, _) => exprs.contains(t))
      if vs.nonEmpty then o.ExpressionAttributeValues = js.Dictionary(vs.toSeq*)
      o
    def expected(v: Long): String = if v == 0 then "attribute_not_exists(#k)" else "#n = :was"
    val items = js.Array[js.Object]()
    for (k, w) <- writes do
      val was = read.get(k)
      val values = Map[String, js.Object](":was" -> n(was.getOrElse(0L)), ":now" -> n(was.getOrElse(0L) + 1),
        ":one" -> n(1))
      (was, w) match
        case (Some(v), Some(value)) =>
          items.push(js.Dynamic.literal(Update = op(k, Some("SET #v = :val, #n = :now"), Some(expected(v)),
            values + (":val" -> s(value)))))
        case (Some(v), None) => items.push(js.Dynamic.literal(Delete = op(k, None, Some(expected(v)), values)))
        case (None, Some(value)) =>
          items.push(js.Dynamic.literal(Update = op(k, Some("SET #v = :val ADD #n :one"), None,
            values + (":val" -> s(value)))))
        case (None, None) => items.push(js.Dynamic.literal(Delete = op(k, None, None, Map.empty)))
    for (k, v) <- read if !writes.contains(k) do
      items.push(js.Dynamic.literal(ConditionCheck = op(k, None, Some(expected(v)), Map(":was" -> n(v)))))
    if items.length > 100 then
      Future.failed(IllegalStateException(s"a step of ${items.length} items: DynamoDB takes 100 in a transaction"))
    else
      val written =
        if items.isEmpty then Future.successful(true)
        else send("TransactWriteItemsCommand", js.Dynamic.literal(TransactItems = items)).map(_ => true).recover {
          case js.JavaScriptException(e) if e.asInstanceOf[js.Dynamic].name.asInstanceOf[String] ==
              "TransactionCanceledException" &&
            e.asInstanceOf[js.Dynamic].CancellationReasons.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
              .exists(_.exists(r => r.Code.asInstanceOf[js.UndefOr[String]].contains("ConditionalCheckFailed"))) =>
            false
        }
      written.flatMap { ok =>
        alarm.filter(_ => ok) match
          case None => Future.successful(ok)
          case Some(at) =>
            send("UpdateItemCommand", js.Dynamic.literal(TableName = table, Key = itemKey("#alarm"),
              UpdateExpression = "SET #a = :a", ConditionExpression = "attribute_not_exists(#a) OR #a > :a",
              ExpressionAttributeNames = js.Dynamic.literal("#a" -> "at"),
              ExpressionAttributeValues = js.Dynamic.literal(":a" -> n(at)))).map { _ =>
              schedule(at)
              ok
            }.recover { case _ => ok } // a later alarm than the one kept: the earlier stands
      }

  /** the handler of `Storage` on this table and space: each operation awaited (`Async`) */
  def handler[R[_], Rest[_], A](p: A ! R)(using Split[Storage, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Storage, Rest, A, A](pure)([X] => (op: Storage[X], k: X => A ! Rest) => op match
      case Storage.Read(keys) => Async.fromFuture[Map[String, Versioned], Rest](read(keys)).flatMap(k)
      case Storage.Keys(prefix) => Async.fromFuture[List[String], Rest](keys(prefix)).flatMap(k)
      case Storage.Commit(r, w, a) => Async.fromFuture[Boolean, Rest](commit(r, w, a)).flatMap(k)))

object DynamoStorage:
  /** a DynamoDB client: the default AWS one, or one at `endpoint` (DynamoDB Local) */
  def client(region: String = "us-east-1", endpoint: Option[String] = None): js.Dynamic =
    val sdk = g.require("@aws-sdk/client-dynamodb")
    val config = js.Dynamic.literal(region = region)
    endpoint.foreach { e =>
      config.endpoint = e
      config.credentials = js.Dynamic.literal(accessKeyId = "local", secretAccessKey = "local")
    }
    js.Dynamic.newInstance(sdk.DynamoDBClient)(config)

  /** the table, made if it is not there: `space` its partition key, `key` its sort key */
  def table(client: js.Dynamic, name: String)(using ExecutionContext): Future[Unit] =
    val sdk = g.require("@aws-sdk/client-dynamodb")
    client.send(js.Dynamic.newInstance(sdk.CreateTableCommand)(js.Dynamic.literal(TableName = name,
      BillingMode = "PAY_PER_REQUEST",
      AttributeDefinitions = js.Array(js.Dynamic.literal(AttributeName = "space", AttributeType = "S"),
        js.Dynamic.literal(AttributeName = "key", AttributeType = "S")),
      KeySchema = js.Array(js.Dynamic.literal(AttributeName = "space", KeyType = "HASH"),
        js.Dynamic.literal(AttributeName = "key", KeyType = "RANGE")))))
      .asInstanceOf[js.Promise[js.Any]].toFuture.map(_ => ()).recover {
        case js.JavaScriptException(e) if e.asInstanceOf[js.Dynamic].name.asInstanceOf[String] ==
          "ResourceInUseException" => ()
      }
