package okay.serverless.cloudflare

import okay.serverless.cloud.{Objects, Request}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal as obj}

/** `Objects.call` ON CLOUDFLARE (specs/cloud.md): the stub's `fetch` asked with `redirect: "manual"`, so the
 * object's redirect comes back as it is — its status, its `Location`, its `Set-Cookie` */
class ObjectsSuite extends munit.FunSuite:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  test("an object's redirect is answered as it is, never followed") {
    var mode = ""
    val stub = obj(fetch = ((r: js.Dynamic) => {
      mode = r.redirect.toString
      val h = js.Dynamic.newInstance(g.Headers)()
      h.set("location", "/pl/")
      h.set("set-cookie", "s=1; Path=/")
      js.Promise.resolve[js.Dynamic](js.Dynamic.newInstance(g.Response)(null, obj(status = 302, headers = h)))
    }): js.Function1[js.Dynamic, js.Promise[js.Dynamic]])
    val ns = obj(idFromName = ((n: String) => n): js.Function1[String, String],
      get = ((_: String) => stub): js.Function1[String, js.Dynamic])
    CloudflarePorts(obj(SHOPS = ns)).objects(Objects.Call("SHOPS", "barber", Request.get("/"))).map { r =>
      assertEquals(mode, "manual")
      assertEquals(r.status, 302)
      assertEquals(r.headers.get("location"), Some("/pl/"))
      assertEquals(r.headers.get("set-cookie"), Some("s=1; Path=/"))
    }
  }

/** WHERE OBJECTS LIVE (specs/cloud.md): `OBJECTS_JURISDICTION` set — the id from the namespace's jurisdictional
 * subnamespace; unset or empty — the namespace's own id, as before */
class JurisdictionSuite extends munit.FunSuite:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** a namespace whose ids say where they were made: `eu:<name>` from `jurisdiction("eu")`, `<name>` from itself */
  private def namespace(asked: scala.collection.mutable.Buffer[String]): js.Dynamic =
    val stub = (id: String) => obj(fetch = ((_: js.Dynamic) => {
      asked += id
      js.Promise.resolve[js.Dynamic](js.Dynamic.newInstance(g.Response)("ok", obj(status = 200)))
    }): js.Function1[js.Dynamic, js.Promise[js.Dynamic]])
    obj(idFromName = ((n: String) => n): js.Function1[String, String],
      jurisdiction = ((j: String) => obj(idFromName = ((n: String) => s"$j:$n"): js.Function1[String, String]))
        : js.Function1[String, js.Dynamic],
      get = ((id: String) => stub(id)): js.Function1[String, js.Dynamic])

  test("objects in the env's jurisdiction") {
    val asked = scala.collection.mutable.Buffer.empty[String]
    CloudflarePorts(obj(SHOPS = namespace(asked), OBJECTS_JURISDICTION = "eu")).objects(
      Objects.Call("SHOPS", "barber", Request.get("/"))).map { r =>
      assertEquals(r.status, 200)
      assertEquals(asked.toList, List("eu:barber"))
    }
  }

  test("no jurisdiction, or an empty one: the namespace's own ids") {
    val asked = scala.collection.mutable.Buffer.empty[String]
    val ports = (env: js.Dynamic) => CloudflarePorts(env).objects(Objects.Call("SHOPS", "barber", Request.get("/")))
    ports(obj(SHOPS = namespace(asked))).flatMap(_ => ports(obj(SHOPS = namespace(asked),
      OBJECTS_JURISDICTION = " "))).map(_ => assertEquals(asked.toList, List("barber", "barber")))
  }
