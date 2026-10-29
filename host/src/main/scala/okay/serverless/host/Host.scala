package okay.serverless.host

import okay.scala3.std.Level
import okay.serverless.cloud.{Actor, App, Scheduled}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/**
 * THE HOST, STARTED (specs/cloud.md "The host" — the fallback beside Cloudflare): an app and its actors on one JVM
 * process, its state in `config.dir`, its HTTP server on `config.port`; each tick (a second) the due alarms fired and
 * the due messages delivered, each minute the cron triggers that match it run. `Host.start(app, actors, config)`.
 */
final class Host private (val cloud: HostCloud, val server: HostServer, app: App, crons: List[Cron]):
  private val config = cloud.config
  @volatile private var running = true
  private var lastMinute = config.clock() / 60000

  /** one tick: alarms, queues, and — a new minute — its cron triggers. What is run is awaited: a slow one delays
   * the next tick, never overlaps it */
  def tick(): Unit =
    given ExecutionContext = ExecutionContext.global
    def logged(what: String)(f: => Unit): Unit =
      try f catch case e: Exception => config.log(Level.Error, s"$what: ${Option(e.getMessage).getOrElse(e.toString)}")
    logged("alarms")(Await.result(cloud.fireAlarms(), 5.minutes): Unit)
    logged("queues")(Await.result(cloud.deliver(app), 5.minutes): Unit)
    val minute = config.clock() / 60000
    if minute != lastMinute then
      lastMinute = minute
      crons.filter(_.matches(minute * 60000)).foreach(c =>
        logged(s"cron ${c.text}")(Await.result(cloud.platform.future(app.scheduled(Scheduled(c.text, minute * 60000))),
          5.minutes)))

  private val ticker = Thread.ofPlatform().name("okay-host-tick").daemon(true).start(() =>
    while running do
      try Thread.sleep(config.tickMillis) catch case _: InterruptedException => ()
      if running then tick())

  def port: Int = server.port

  def stop(): Unit =
    running = false
    ticker.interrupt()
    server.stop()
    cloud.close()

object Host:
  def start(app: App, actors: Map[String, Actor], config: HostConfig): Host =
    val crons = config.crons.map(c => Cron.parse(c).fold(e => throw IllegalArgumentException(e), identity))
    val cloud = HostCloud(config, actors)(using ExecutionContext.global)
    Host(cloud, HostServer(cloud, app), app, crons)
