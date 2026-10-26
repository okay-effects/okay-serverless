package okay.serverless.cloud

import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.{Durable, Storage}

import scala.concurrent.{ExecutionContext, Future}

/** WHAT A CLOUD DOES FOR ONE EFFECT: each of its operations, as a future */
trait Port[E[_]]:
  def apply[X](op: E[X]): Future[X]

object Port:
  /** a port of an effect this cloud does not have: each use fails, naming it */
  def missing[E[_]](what: String): Port[E] = new Port[E]:
    def apply[X](op: E[X]): Future[X] = Future.failed(UnsupportedOperationException(s"no $what here: $op"))

  /** `p`'s operations of `E` by `port`, each awaited */
  def handle[E[_], R[_], Rest[_], A](port: Port[E])(p: A ! R)(using Split[E, R, Rest], In[Async, Rest]): A ! Rest =
    okay.scala3.handle(p, Handler.general[E, Rest, A, A](pure)(
      [X] => (op: E[X], k: X => A ! Rest) => Async.fromFuture[X, Rest](port(op)).flatMap(k)))

/** EVERYTHING A PROGRAM MAY USE IN A CLOUD (specs/cloud.md) */
type Cloud[X] = (Async + Log + Clock + Kv + Blobs + Sql + Queues + Fetch + Cache + Env + Services + Limits + Ai + Vectors +
  Analytics + Mails + Objects)[X]

/** AN ACTOR'S ROW: the cloud, its own storage (for `Durable`), its sockets */
type InActor[X] = (Cloud + Storage + Sockets)[X]
/** an actor's row with `Durable`: what a step of it runs in */
type ActorStep[X] = (InActor + Durable)[X]

/** A CLOUD: one port an effect, and where its log goes */
final case class Platform(kv: Port[Kv] = Port.missing("KV"), blobs: Port[Blobs] = Port.missing("blobs"),
                          sql: Port[Sql] = Port.missing("SQL"), queues: Port[Queues] = Port.missing("queues"),
                          fetch: Port[Fetch] = Port.missing("fetch"), cache: Port[Cache] = Port.missing("cache"),
                          env: Port[Env] = Port.missing("env"), services: Port[Services] = Port.missing("services"),
                          limits: Port[Limits] = Port.missing("rate limits"), ai: Port[Ai] = Port.missing("AI"),
                          vectors: Port[Vectors] = Port.missing("vectors"),
                          analytics: Port[Analytics] = Port.missing("analytics"),
                          mails: Port[Mails] = Port.missing("mail"), objects: Port[Objects] = Port.missing("objects"),
                          log: (Level, String) => Unit = (l, m) => println(s"[$l] $m"),
                          now: () => Long = () => System.currentTimeMillis()):
  /** a program of the cloud, its effects by this platform's ports: what is left, `Async` */
  def run[A](p: A ! Cloud): A ! Async =
    Port.handle(objects)(Port.handle(mails)(Port.handle(analytics)(Port.handle(vectors)(Port.handle(ai)(
      Port.handle(limits)(Port.handle(services)(Port.handle(env)(Port.handle(cache)(Port.handle(fetch)(
        Port.handle(queues)(Port.handle(sql)(Port.handle(blobs)(Port.handle(kv)(clock(Log.to(log)(p))))))))))))))))

  /** the time: `now`'s; a sleep, awaited */
  private def clock[R[_], Rest[_], A](p: A ! R)(using Split[Clock, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Clock, Rest, A, A](pure)([X] => (op: Clock[X], k: X => A ! Rest) => op match
      case Clock.Now() => k(now())
      case Clock.Sleep(ms) => Async.sleep[Rest](ms).flatMap(k)))

  /** the same, to a future */
  def future[A](p: A ! Cloud)(using ExecutionContext): Future[A] = Async.toFuture(run(p))

/** a cron trigger's firing */
final case class Scheduled(cron: String, time: Long)

/** A WORKER: its entry points, each a program of the cloud */
trait App:
  def fetch(request: Request): Response ! Cloud = pure(Response.notFound)
  def scheduled(event: Scheduled): Unit ! Cloud = pure(())
  /** a message of `queue`: acknowledged, or given again later. A failure is a retry */
  def queue(queue: String, message: Message): Settle ! Cloud = pure(Settle.Ack)
  def mail(mail: IncomingMail): Unit ! Cloud = pure(())

/** AN OBJECT CLASS: one instance a name, its requests one at a time where the cloud can (Cloudflare), its state
 * `Durable`'s wherever it runs */
trait Actor:
  def fetch(name: String, request: Request): Response ! InActor
  def alarm(name: String): Unit ! InActor = pure(())
  def message(name: String, socket: Socket, text: String): Unit ! InActor = pure(())
  def closed(name: String, socket: Socket): Unit ! InActor = pure(())

/** how an actor's storage and sockets are given to it: the cloud's */
trait Hosting:
  def storage: Port[Storage]
  def sockets: Port[Sockets]
  def run[A](platform: Platform)(p: A ! InActor): A ! Async =
    platform.run(Port.handle(sockets)(Port.handle(storage)(p)))
