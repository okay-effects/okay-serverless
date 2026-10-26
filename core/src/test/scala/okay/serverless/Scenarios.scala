package okay.serverless

import okay.scala3.*
import okay.scala3.std.*

/**
 * THE SCENARIOS every platform passes (the TCK): a step's atomicity, its isolation, a conflict, a crash, an eviction,
 * an alarm, `once`. Each over a `Storage` handler made fresh per instance on the same storage — here memory with faults
 */
class Scenarios extends munit.FunSuite:
  type S[X] = (Storage + Fail % String + Durable)[X]
  type D[X] = (Storage + Durable)[X]

  /** a program run by one instance on `m`: its value, or its failure */
  def instance[A](m: Memory)(p: A ! (Storage + Fail % String)): Either[String, A] =
    run(m.handler(Fail.run(p)))

  def counter(name: String, by: Long = 1): Long ! S =
    for
      n <- Durable.get[Long, S](s"$name/n")
      next = n.getOrElse(0L) + by
      _ <- Durable.put[Long, S](s"$name/n", next)
    yield next

  test("a step: read your writes; committed all at once; a value of each type by its codec") {
    val m = Memory()
    val got = instance(m)(Durable.step[S, Storage + Fail % String, (Option[String], List[Int])]("p")(
      for
        _ <- Durable.put[String, S]("p/name", "ann")
        _ <- Durable.put[List[Int], S]("p/scores", List(1, 2, 3))
        name <- Durable.get[String, S]("p/name")
        scores <- Durable.get[List[Int], S]("p/scores")
      yield (name, scores.getOrElse(Nil))))
    assertEquals(got, Right((Some("ann"), List(1, 2, 3))))
    assertEquals(m.snapshot, Map("p/name" -> "\"ann\"", "p/scores" -> "[1,2,3]", "p/#steps" -> "1"))
    assertEquals(m.commits, 1)
  }

  test("a step that fails, or throws, writes nothing") {
    val m = Memory()
    val failed = instance(m)(Durable.step[S, Storage + Fail % String, Unit]("p")(
      Durable.put[Long, S]("p/n", 1L).andThen(Fail.raise[String, S]("no"))))
    assertEquals(failed, Left("no"))
    intercept[IllegalStateException](instance(m)(Durable.step[S, Storage + Fail % String, Unit]("p")(
      Durable.put[Long, S]("p/n", 1L).andThen(defer(throw IllegalStateException("boom"))))))
    assertEquals(m.snapshot, Map.empty)
  }

  test("a CONFLICT: another writer between the step's read and its commit — the step runs again, nothing lost") {
    val m = Memory()
    instance(m)(Durable.step[S, Storage + Fail % String, Long]("c")(counter("c")))
    var runs = 0
    m.beforeCommit = () => m.write("c/n", "10") // another instance committed meanwhile
    val got = instance(m)(Durable.step[S, Storage + Fail % String, Long]("c")(defer { runs += 1; counter("c") }))
    assertEquals(got, Right(11L)) // 10, not the 1 it first read, plus 1
    assertEquals(runs, 2)
    assertEquals(m.snapshot("c/n"), "11")
  }

  test("a CRASH before the commit: nothing written; the next instance does the step as if it never began") {
    val m = Memory()
    m.crashBeforeCommit = true
    intercept[Crash](instance(m)(Durable.step[S, Storage + Fail % String, Long]("c")(counter("c"))))
    assertEquals(m.snapshot.get("c/n"), None)
    assertEquals(instance(m)(Durable.step[S, Storage + Fail % String, Long]("c")(counter("c"))), Right(1L))
  }

  test("an EVICTION: a new instance on the same storage goes on from the last committed state, no replay") {
    val m = Memory()
    val add = (n: Long, e: Long) => pure[Long, S](n + e)
    def event(e: Long) = Durable.machine[Long, Long, S, Storage + Fail % String]("acc", 0L)(add)(e)
    assertEquals(instance(m)(event(5)), Right(5L))
    assertEquals(instance(m)(event(7)), Right(12L)) // another instance: the state read, not the events replayed
    m.crashAfterCommit = true
    intercept[Crash](instance(m)(event(1))) // committed, then gone: its caller never told
    assertEquals(instance(m)(event(0)), Right(13L))
    assertEquals(m.snapshot("acc/#steps"), "4")
  }

  test("ONCE: an external call run once across a step's runs; its key the same each run, another next step") {
    val m = Memory()
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    def charge: Long ! S = for
      id <- Durable.once[String, S]("charge")(key => defer { calls += key; pure[String, S](s"ch_${calls.size}") })
      n <- counter("pay")
    yield n
    m.beforeCommit = () => m.write("pay/n", "100") // a conflict: the step runs again
    assertEquals(instance(m)(Durable.step[S, Storage + Fail % String, Long]("pay")(charge)), Right(101L))
    assertEquals(calls.toList, List("pay/1/charge")) // once, though the step ran twice
    m.crashBeforeCommit = true
    intercept[Crash](instance(m)(Durable.step[S, Storage + Fail % String, Long]("pay")(charge)))
    instance(m)(Durable.step[S, Storage + Fail % String, Long]("pay")(charge))
    assertEquals(calls.toList, List("pay/1/charge", "pay/2/charge", "pay/2/charge")) // after a crash: the same key
  }

  test("an ALARM: set only if the step commits; the earliest kept") {
    val m = Memory()
    instance(m)(Durable.step[S, Storage + Fail % String, Unit]("a")(
      Durable.wakeAt[S](5000).andThen(Fail.raise[String, S]("no"))))
    assertEquals(m.alarm, None)
    instance(m)(Durable.step[S, Storage + Fail % String, Unit]("a")(
      Durable.wakeAt[S](5000).andThen(Durable.wakeAt[S](3000))))
    assertEquals(m.alarm, Some(3000L))
  }

  test("keys by prefix: the stored and this step's writes, its deletes left out") {
    val m = Memory()
    instance(m)(Durable.step[S, Storage + Fail % String, Unit]("k")(
      Durable.put[Int, S]("k/a", 1).andThen(Durable.put[Int, S]("k/b", 2))))
    val got = instance(m)(Durable.step[S, Storage + Fail % String, List[String]]("k")(
      Durable.delete[S]("k/a").andThen(Durable.put[Int, S]("k/c", 3)).andThen(Durable.keys[S]("k/"))))
    assertEquals(got, Right(List("k/b", "k/c")))
  }
