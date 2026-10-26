package okay.serverless.simple

import okay.effects.{Runner, Std}
import okay.effects.free.Free
import okay.effects.machine.Machine

/**
 * THE SCENARIOS of okay-serverless's `core` (the TCK), on okay-simple, on each implementation — and the stored values
 * written the same, text for text, as okay-scala's twin writes them
 */
abstract class ScenariosChecks(val fx0: Runner) extends munit.FunSuite {
  implicit val runner: Runner = fx0
  val s = Serverless()
  import s._
  import s.fx._
  import s.fx.std.{attempt, raise}

  type Out[X] = Row[*[Pure] + *[Storage] + (Std.Error % String), X]
  type S[X] = Row[*[Pure] + *[Storage] + (Std.Error % String) + *[Durable], X]

  /** a program run by one instance on `m`: its value, or its failure */
  def instance[A](m: Memory)(p: A ! Out): Either[String, A] = s.fx.run(memory(m)(attempt[String](p)))

  def counter(name: String, by: Long = 1): Long ! S =
    get[Long, S](s"$name/n").flatMap { n =>
      val next = n.getOrElse(0L) + by
      put[Long, S](s"$name/n", next).as(next)
    }

  test("a step: read your writes; committed all at once; the stored format okay-scala's") {
    val m = new Memory
    val got = instance(m)(step[S, Out, (Option[String], List[Int])]("p")(
      put[String, S]("p/name", "ann").andThen(put[List[Int], S]("p/scores", List(1, 2, 3)))
        .andThen(get[String, S]("p/name")).flatMap(name =>
          get[List[Int], S]("p/scores").map(scores => (name, scores.getOrElse(Nil))))))
    assertEquals(got, Right((Some("ann"), List(1, 2, 3))))
    assertEquals(m.snapshot, Map("p/name" -> "\"ann\"", "p/scores" -> "[1,2,3]", "p/#steps" -> "1"))
    assertEquals(m.commits, 1)
  }

  test("a step that fails, or throws, writes nothing") {
    val m = new Memory
    val failed = instance(m)(step[S, Out, Unit]("p")(put[Long, S]("p/n", 1L).andThen(raise[String, S, Unit]("no"))))
    assertEquals(failed, Left("no"))
    intercept[IllegalStateException](instance(m)(step[S, Out, Unit]("p")(
      put[Long, S]("p/n", 1L).andThen(delay[Unit, S](throw new IllegalStateException("boom"))))))
    assertEquals(m.snapshot, Map.empty[String, String])
  }

  test("a CONFLICT: another writer between the step's read and its commit — the step runs again, nothing lost") {
    val m = new Memory
    instance(m)(step[S, Out, Long]("c")(counter("c")))
    var runs = 0
    m.beforeCommit = () => m.write("c/n", "10")
    val got = instance(m)(step[S, Out, Long]("c")(delay[Long, S] { runs += 1; counter("c") }))
    assertEquals(got, Right(11L))
    assertEquals(runs, 2)
    assertEquals(m.snapshot("c/n"), "11")
  }

  test("a CRASH before the commit: nothing written; the next instance does the step as if it never began") {
    val m = new Memory
    m.crashBeforeCommit = true
    intercept[Crash](instance(m)(step[S, Out, Long]("c")(counter("c"))))
    assertEquals(m.snapshot.get("c/n"), None)
    assertEquals(instance(m)(step[S, Out, Long]("c")(counter("c"))), Right(1L))
  }

  test("an EVICTION: a new instance on the same storage goes on from the last committed state, no replay") {
    val m = new Memory
    def event(e: Long) = machine[Long, Long, S, Out]("acc", 0L)((n, e) => pure[Long, S](n + e))(e)
    assertEquals(instance(m)(event(5)), Right(5L))
    assertEquals(instance(m)(event(7)), Right(12L))
    m.crashAfterCommit = true
    intercept[Crash](instance(m)(event(1)))
    assertEquals(instance(m)(event(0)), Right(13L))
    assertEquals(m.snapshot("acc/#steps"), "4")
    assertEquals(m.snapshot("acc/state"), "13")
  }

  test("ONCE: an external call run once across a step's runs; its key the same each run, another next step") {
    val m = new Memory
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    def charge: Long ! S =
      once[String, S]("charge")(key => delay[String, S] { calls += key; pure[String, S](s"ch_${calls.size}") })
        .andThen(counter("pay"))
    m.beforeCommit = () => m.write("pay/n", "100")
    assertEquals(instance(m)(step[S, Out, Long]("pay")(charge)), Right(101L))
    assertEquals(calls.toList, List("pay/1/charge"))
    m.crashBeforeCommit = true
    intercept[Crash](instance(m)(step[S, Out, Long]("pay")(charge)))
    instance(m)(step[S, Out, Long]("pay")(charge))
    assertEquals(calls.toList, List("pay/1/charge", "pay/2/charge", "pay/2/charge"))
  }

  test("an ALARM: set only if the step commits; the earliest kept") {
    val m = new Memory
    instance(m)(step[S, Out, Unit]("a")(wakeAt[S](5000).andThen(raise[String, S, Unit]("no"))))
    assertEquals(m.alarm, None)
    instance(m)(step[S, Out, Unit]("a")(wakeAt[S](5000).andThen(wakeAt[S](3000))))
    assertEquals(m.alarm, Some(3000L))
  }

  test("keys by prefix: the stored and this step's writes, its deletes left out") {
    val m = new Memory
    instance(m)(step[S, Out, Unit]("k")(put[Int, S]("k/a", 1).andThen(put[Int, S]("k/b", 2))))
    val got = instance(m)(step[S, Out, List[String]]("k")(
      delete[S]("k/a").andThen(put[Int, S]("k/c", 3)).andThen(keys[S]("k/"))))
    assertEquals(got, Right(List("k/b", "k/c")))
  }
}

class ScenariosOnMachine extends ScenariosChecks(Machine)
class ScenariosOnFree extends ScenariosChecks(Free)
