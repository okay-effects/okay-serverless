package okay.serverless.cloudflare

import scala.scalajs.js

/**
 * WHERE A WORKER'S OBJECTS LIVE (specs/cloud.md "Where objects live"): the Worker's var `OBJECTS_JURISDICTION`
 * ("eu", "fedramp") names the jurisdiction every object of every namespace is found and created in — the id from the
 * namespace's jurisdictional subnamespace (`namespace.jurisdiction(j).idFromName(name)`), Cloudflare's recommended
 * way; unset or empty: the namespace's own id, as before. The same name is ANOTHER object in a jurisdiction: setting
 * the var on a Worker with data is a move (the old objects stay as they were, unreached), never a relocation
 */
object Jurisdiction:
  val variable = "OBJECTS_JURISDICTION"

  /** the jurisdiction the Worker's env names, if any */
  def of(env: js.Dynamic): Option[String] =
    val v = env.selectDynamic(variable)
    if js.isUndefined(v) || v == null then None else Some(v.toString.trim).filter(_.nonEmpty)

  /** the stub of the object `name` of the namespace `n`, in the env's jurisdiction */
  def stub(env: js.Dynamic, n: js.Dynamic, name: String): js.Dynamic = of(env) match
    case Some(j) => n.get(n.jurisdiction(j).idFromName(name))
    case None => n.get(n.idFromName(name))
