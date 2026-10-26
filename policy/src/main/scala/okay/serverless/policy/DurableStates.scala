package okay.serverless.policy

import okay.policy.{Call, Change, Checked, Compiled, Decide, Decision, FactRef, Facts, States, Value, Window}
import okay.scala3.*
import okay.effects.json.Json
import okay.serverless.{Durable, Storage, field}

/**
 * A POLICY'S STATE ON `Durable`: each state's value under `policy/<state>/<key as JSON>`, with the start of its window;
 * a decision and its changes made in ONE step — so two instances spending one budget at once cannot both pass: the
 * second's commit finds the state changed, and its decision is made again on the new value.
 */
object DurableStates:
  private def key(ref: FactRef): String =
    s"policy/${ref.name}/" + Json.print(Json.Arr(ref.args.toVector.map(json)))

  private def json(v: Value): Json = v match
    case Value.I(i) => Json.Num(BigDecimal(i))
    case Value.D(d) => Json.Num(d)
    case Value.T(t) => Json.Str(t)
    case Value.E(_, n) => Json.Str(n)

  private def windowStart(w: Window, now: Long): Long = now - Math.floorMod(now, w.millis)

  private given okay.serverless.Codec[(BigDecimal, Long)] = okay.serverless.Codec.json(
    (v, since) => Json.obj("v" -> Json.Num(v), "since" -> Json.Num(BigDecimal(since))),
    j => (j.field("v"), j.field("since")) match
      case (Some(Json.Num(v)), Some(Json.Num(s))) => Right((v, s.toLong))
      case _ => Left(s"not a state: $j"))

  /** `States` answered from `Durable`: values read in the step, changes written in it */
  def handler[R[_], Rest[_], A](checked: Checked, now: () => Long)(p: A ! R)(
    using Split[States, R, Rest], In[Durable, Rest]): A ! Rest =
    def read(ref: FactRef): Value ! Rest =
      val sig = checked.states(ref.name)
      Durable.get[(BigDecimal, Long), Rest](key(ref)).map {
        case Some((v, since)) if sig.window.forall(w => windowStart(w, now()) == since) => value(sig.init, v)
        case _ => sig.init
      }
    def value(like: Value, v: BigDecimal): Value = like match
      case Value.I(_) => Value.I(v.toBigInt)
      case _ => Value.D(v)
    def number(v: Value): BigDecimal = v match
      case Value.I(i) => BigDecimal(i)
      case Value.D(d) => d
      case other => throw IllegalStateException(s"a state is a number: $other")
    handle(p, Handler.general[States, Rest, A, A](pure)([X] => (op: States[X], k: X => A ! Rest) => op match
      case States.Read(refs) =>
        refs.foldLeft(pure[Map[FactRef, Value], Rest](Map.empty))((acc, r) =>
          acc.flatMap(m => read(r).map(v => m + (r -> v)))).flatMap(k)
      case States.Update(changes) =>
        changes.foldLeft(unit[Rest])((acc, c) => acc.andThen(read(c.ref).flatMap { old =>
          val next = c.change match
            case Change.Set => number(c.value)
            case Change.Add => number(old) + number(c.value)
            case Change.Sub => number(old) - number(c.value)
          val since = checked.states(c.ref.name).window.fold(0L)(windowStart(_, now()))
          Durable.put[(BigDecimal, Long), Rest](key(c.ref), (next, since))
        })).flatMap(k)))

  /** the rows of a durable decision: outside the step, in it, and deciding */
  type Outside[X] = (Storage + Facts)[X]
  type InStep[X] = (Storage + Facts + Durable)[X]
  type Deciding[X] = (Storage + Facts + Durable + States)[X]

  /** a call decided and its changes made, in one step of `scope`: what a guard does for a call, durably — with other
   * effects, the same from `handler` and `Durable.step` */
  def enforce(policy: Compiled, call: Call, scope: String, now: () => Long): Decision ! Outside =
    Durable.step[InStep, Outside, Decision](scope)(
      handler[Deciding, InStep, Decision](policy.checked, now)(Decide.enforce[Deciding](policy, call)))
