package okay.serverless.aws

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Durable, Storage, Versioned}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

/** the scenarios on DynamoDB (DynamoDB Local at OKAY_DYNAMODB): a step a transaction; a conflict, injected and real —
 * two instances incrementing one counter at once lose nothing; an eviction; keys; an alarm */
class DynamoSuite extends munit.FunSuite:
  given ExecutionContext = Async.defaultContext
  val endpoint: Option[String] = g.process.env.OKAY_DYNAMODB.asInstanceOf[js.UndefOr[String]].toOption
  override def munitIgnore: Boolean = endpoint.isEmpty
  lazy val client = DynamoStorage.client(endpoint = endpoint)
  val table = "okay-test"

  type R[X] = (Async + Storage + Durable)[X]
  type Out[X] = (Async + Storage)[X]

  def space() = s"test-${js.Math.random().toString.drop(2)}"

  /** a program run by one instance — a storage of its own — in `space` */
  def instance[A](sp: String, alarm: Long => Unit = _ => ())(p: A ! Out): Future[A] =
    DynamoStorage.table(client, table).flatMap(_ =>
      Async.toFuture(DynamoStorage(client, table, sp, alarm).handler(p)))

  def counter(name: String): Long ! R = for
    n <- Durable.get[Long, R](s"$name/n")
    next = n.getOrElse(0L) + 1
    _ <- Durable.put[Long, R](s"$name/n", next)
  yield next

  test("a step: read your writes, committed at once; keys by prefix; a delete") {
    val sp = space()
    for
      got <- instance(sp)(Durable.step[R, Out, Option[String]]("p")(
        Durable.put[String, R]("p/a", "x").andThen(Durable.put[Int, R]("p/b", 2))
          .andThen(Durable.get[String, R]("p/a"))))
      keys <- instance(sp)(Durable.step[R, Out, List[String]]("p")(
        Durable.delete[R]("p/b").andThen(Durable.keys[R]("p/"))))
    yield
      assertEquals(got, Some("x"))
      assertEquals(keys, List("p/a"))
  }

  test("a CONFLICT injected: another writer between the read and the commit — the step runs again") {
    val sp = space()
    var runs = 0
    var injected = false
    // the storage's operations as they are, but before the first commit another instance writes the counter
    def injecting[A](p: A ! Out): Future[A] =
      val ds = DynamoStorage(client, table, sp)
      val other = DynamoStorage(client, table, sp)
      Async.toFuture(handle(p, Handler.general[Storage, Async, A, A](pure)(
        [X] => (op: Storage[X], k: X => A ! Async) => op match
          case Storage.Read(keys) => Async.fromFuture[Map[String, Versioned], Async](ds.read(keys)).flatMap(k)
          case Storage.Keys(prefix) => Async.fromFuture[List[String], Async](ds.keys(prefix)).flatMap(k)
          case Storage.Commit(r, w, a) =>
            val before = if injected then Future.unit else
              injected = true
              other.commit(Map.empty, Map("c/n" -> Some("10")), None).map(_ => ())
            Async.fromFuture[Boolean, Async](before.flatMap(_ => ds.commit(r, w, a))).flatMap(k))))
    for
      _ <- DynamoStorage.table(client, table)
      n <- injecting(Durable.step[R, Out, Long]("c")(defer { runs += 1; counter("c") }))
    yield
      assertEquals(n, 11L) // 10, written meanwhile, plus 1 — not the 1 first computed
      assertEquals(runs, 2)
  }

  test("TWO INSTANCES AT ONCE, ten increments each on one counter: twenty, nothing lost") {
    val sp = space()
    def ten(): Future[Unit] =
      (1 to 10).foldLeft(Future.unit)((f, _) => f.flatMap(_ => instance(sp)(Durable.step[R, Out, Long]("x")(
        counter("x"))).map(_ => ())))
    for
      _ <- DynamoStorage.table(client, table)
      _ <- Future.sequence(List(ten(), ten()))
      n <- instance(sp)(Durable.step[R, Out, Option[Long]]("x")(Durable.get[Long, R]("x/n")))
    yield assertEquals(n, Some(20L))
  }

  test("an EVICTION: a machine goes on from its committed state in another instance; an alarm, the earliest") {
    val sp = space()
    var scheduled = List.empty[Long]
    def event(e: Long) = Durable.machine[Long, Long, R, Out]("acc", 0L)((s, e) => pure(s + e))(e)
    for
      a <- instance(sp)(event(5))
      b <- instance(sp)(event(7))
      _ <- instance(sp, at => scheduled ::= at)(Durable.step[R, Out, Unit]("t")(
        Durable.wakeAt[R](9000).andThen(Durable.wakeAt[R](5000))))
      _ <- instance(sp, at => scheduled ::= at)(Durable.step[R, Out, Unit]("t")(Durable.wakeAt[R](7000)))
    yield
      assertEquals((a, b), (5L, 12L))
      assertEquals(scheduled, List(5000L)) // 7000 is later than the alarm kept: not scheduled again
  }
