package okay.serverless.policy

import okay.policy.{Call, Compiled, Facts, Value, Verdict}
import okay.scala3.*
import okay.serverless.Memory

/** a policy's state on Durable: a budget kept across instances; two instances spending it at once cannot both pass */
class DurableStatesSuite extends munit.FunSuite:
  val policy = Compiled("""policy budget
    |type User
    |state spent(user: User): Dec per day = 0
    |call pay(amount: Dec)
    |allow pay when spent(caller) + amount <= 1000
    |count pay then spent(caller) += amount
    |""".stripMargin).fold(ps => fail(ps.mkString("\n")), identity)
  val ann = Value.E("User", "ann")
  def pay(n: Int) = Call.flat("pay", ann, Map("amount" -> Value.D(BigDecimal(n))))

  /** one instance on the storage `m`, at time `t` */
  def decide(m: Memory, c: Call, t: Long = 0L) =
    run(m.handler(Facts.known(Map.empty)(DurableStates.enforce(policy, c, "budget/ann", () => t))))

  test("the state kept durably: a new instance on the same storage goes on; the day's window starts again") {
    val m = Memory()
    assertEquals(decide(m, pay(600)).verdict, Verdict.Allow)
    assertEquals(decide(m, pay(300)).verdict, Verdict.Allow) // another instance: 900 spent, read from storage
    assertEquals(decide(m, pay(200)).verdict, Verdict.Deny)  // 1100 > 1000
    assertEquals(decide(m, pay(200), t = 86_400_000L).verdict, Verdict.Allow) // the next day
  }

  test("a CONFLICT: another instance spent the budget between this one's read and its commit — decided again") {
    val m = Memory()
    decide(m, pay(800))
    // another instance spends 150 while this one decides 100 on the 800 it read: 800 + 100 <= 1000, allowed — but
    // the commit finds the state changed, so the decision is made again on 950: 1050 > 1000, denied
    m.beforeCommit = () =>
      m.write("policy/spent/[\"ann\"]", """{"v":950,"since":0}""")
    val d = decide(m, pay(100))
    assertEquals(d.verdict, Verdict.Deny)
    assert(d.reasons.exists(_.contains("950")), d.reasons.toString)
    assertEquals(m.snapshot("policy/spent/[\"ann\"]"), """{"v":950,"since":0}""") // nothing of the denied one
  }
