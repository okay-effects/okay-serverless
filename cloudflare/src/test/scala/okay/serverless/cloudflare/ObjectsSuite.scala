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
