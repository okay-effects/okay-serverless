package okay.serverless.cloud

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Codec, Durable, field}

/** A STAGE OF A WORKFLOW over its state `S` */
enum Stage[S]:
  /** work done outside the transaction (it may call anything), its new state committed with the stage; a failure
   * (`Fail`) tried again after `backoffMs`, doubled each time, `retries` times */
  case Do(name: String, run: S => S ! (InActor + Fail % String), retries: Int = 3, backoffMs: Long = 1000)
  case Sleep(name: String, ms: Long)
  /** the workflow waits for `event`, sent by `Workflow.event`; its payload folded into the state */
  case Wait(name: String, event: String, fold: (S, Json) => S)

/** where a workflow is: its stage, its state (by its codec), and what it does now */
final case class FlowState(stage: Int, state: String, status: String, attempts: Int = 0,
                           wakeAt: Option[Long] = None, error: Option[String] = None)

object FlowState:
  given Codec[FlowState] = Codec.json(
    f => Json.Obj(Vector("stage" -> Json.num(f.stage), "state" -> Json.Str(f.state), "status" -> Json.Str(f.status),
      "attempts" -> Json.num(f.attempts)) ++ f.wakeAt.map(w => "wakeAt" -> Json.num(w)) ++
      f.error.map(e => "error" -> Json.Str(e))),
    j => (j.field("stage"), j.field("state"), j.field("status"), j.field("attempts")) match
      case (Some(Json.Num(st)), Some(Json.Str(s)), Some(Json.Str(status)), Some(Json.Num(a))) =>
        Right(FlowState(st.toInt, s, status, a.toInt, j.field("wakeAt").collect { case Json.Num(w) => w.toLong },
          j.field("error").collect { case Json.Str(e) => e }))
      case _ => Left(s"not a workflow's state: $j"))

/**
 * A WORKFLOW WITHOUT REPLAY (specs/cloud.md): the stages are data, the workflow an object — one a run, by its name;
 * where it is (`FlowState`) its durable state. Each stage moves it on in one `Durable` step; a sleep and a retry are
 * its alarm; an event a request. Restarted anywhere, it goes on from the stage committed: nothing is run again but
 * a `Do` whose commit did not happen (its work at least once, as on every cloud).
 *
 * Its requests, by how their paths end (an app may route `/order/<id>/…` to it as it is): `POST …/start` (the input,
 * JSON), `POST …/event/<name>` (the payload), `GET` (where it is).
 */
final class Workflow[S](stages: Vector[Stage[S]], start: Json => S)(using codec: Codec[S]) extends Actor:
  private val key = "flow"

  private def load(name: String): Option[FlowState] ! InActor =
    Durable.step[ActorStep, InActor, Option[FlowState]](name)(Durable.get[FlowState, ActorStep](key))

  /** `next` committed if the workflow is still at `at` (else another run moved it: nothing done) */
  private def commit(name: String, at: FlowState, next: FlowState): Boolean ! InActor =
    Durable.step[ActorStep, InActor, Boolean](name)(Durable.get[FlowState, ActorStep](key).flatMap {
      case Some(now) if now == at =>
        Durable.put[FlowState, ActorStep](key, next)
          .andThen(next.wakeAt.fold(pure[Unit, ActorStep](()))(w => Durable.wakeAt[ActorStep](w))).as(true)
      case _ => pure(false)
    })

  private def decode(f: FlowState): S =
    codec.decode(f.state).fold(e => throw IllegalStateException(s"a workflow's state: $e"), identity)

  /** the workflow moved on as far as it goes now */
  private def advance(name: String): Unit ! InActor =
    load(name).flatMap {
      case Some(f) if f.status == "running" =>
        if f.stage >= stages.size then commit(name, f, f.copy(status = "done")).as(())
        else stages(f.stage) match
          case Stage.Do(_, run, retries, backoff) =>
            Fail.run[String, InActor + Fail % String, InActor, S](run(decode(f))).flatMap {
              case Right(s) => commit(name, f, FlowState(f.stage + 1, codec.encode(s), "running"))
                .flatMap(ok => if ok then advance(name) else pure(()))
              case Left(why) =>
                if f.attempts >= retries then commit(name, f, f.copy(status = "failed", error = Some(why))).as(())
                else Clock.now[InActor].flatMap(t => commit(name, f, f.copy(status = "retrying",
                  attempts = f.attempts + 1, wakeAt = Some(t + backoff * (1L << f.attempts)), error = Some(why))))
                  .as(())
            }
          case Stage.Sleep(_, ms) =>
            Clock.now[InActor].flatMap(t => commit(name, f, f.copy(status = "sleeping", wakeAt = Some(t + ms)))).as(())
          case Stage.Wait(_, event, _) => commit(name, f, f.copy(status = s"waiting:$event")).as(())
      case _ => pure(())
    }

  private def status(f: Option[FlowState]): Response = f match
    case None => Response.json(Json.obj("status" -> Json.Str("none")), 404)
    case Some(f) => Response.json(Json.Obj(Vector("status" -> Json.Str(f.status), "stage" -> Json.num(f.stage)) ++
      stages.lift(f.stage).map(s => "at" -> Json.Str(stageName(s))) ++
      Descent.parse(f.state).toOption.map("state" -> _) ++ f.error.map(e => "error" -> Json.Str(e))))

  private def stageName(s: Stage[S]): String = s match
    case Stage.Do(n, _, _, _) => n
    case Stage.Sleep(n, _) => n
    case Stage.Wait(n, _, _) => n

  def fetch(name: String, request: Request): Response ! InActor =
    def body = Descent.parse(request.body.text).getOrElse(Json.Null)
    val segments = request.path.split('/').toList.filter(_.nonEmpty)
    (request.method, segments.reverse) match
      case ("POST", "start" :: _) =>
        val first = FlowState(0, codec.encode(start(body)), "running")
        Durable.step[ActorStep, InActor, Boolean](name)(Durable.get[FlowState, ActorStep](key).flatMap {
          case Some(_) => pure(false)
          case None => Durable.put[FlowState, ActorStep](key, first).as(true)
        }).flatMap(started => if started then advance(name).andThen(load(name)).map(status)
          else pure(Response.text("started already", 409)))
      case ("POST", event :: "event" :: _) =>
        load(name).flatMap {
          case Some(f) if f.status == s"waiting:$event" => stages(f.stage) match
            case Stage.Wait(_, _, fold) =>
              commit(name, f, FlowState(f.stage + 1, codec.encode(fold(decode(f), body)), "running"))
                .flatMap(_ => advance(name)).andThen(load(name)).map(status)
            case _ => pure(Response.text("not waiting", 409))
          case _ => pure(Response.text(s"not waiting for $event", 409))
        }
      case ("GET", _) => load(name).map(status)
      case _ => pure(Response.notFound)

  /** a sleep over, a retry due: on */
  override def alarm(name: String): Unit ! InActor =
    load(name).flatMap {
      case Some(f) if f.status == "sleeping" || f.status == "retrying" =>
        Clock.now[InActor].flatMap { t =>
          if f.wakeAt.exists(_ > t) then Durable.step[ActorStep, InActor, Unit](name)(
            Durable.wakeAt[ActorStep](f.wakeAt.get))
          else commit(name, f, f.copy(stage = if f.status == "sleeping" then f.stage + 1 else f.stage,
            status = "running", wakeAt = None)).flatMap(ok => if ok then advance(name) else pure(()))
        }
      case _ => pure(())
    }

object Workflow:
  def apply[S: Codec](start: Json => S)(stages: Stage[S]*): Workflow[S] = new Workflow(stages.toVector, start)
