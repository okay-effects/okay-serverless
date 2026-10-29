package okay.serverless.cloud

import okay.http.{Bytes, Request, Response}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/**
 * HTTP OUT ON THE JVM (specs/cloud.md, `Fetch` elsewhere): the JDK's HttpClient, redirects not followed — as a
 * Worker's `fetch` with `redirect: manual` would not, so a program sees the redirect it was given. A body is sent as
 * the request's bytes, and read back as text when it is UTF-8, as bytes when not.
 */
final class JdkFetch(timeoutMs: Int = 30000)(using ec: ExecutionContext) extends Port[Fetch]:
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofMillis(timeoutMs.toLong)).build()

  /** the headers a client may not set itself */
  private val restricted = Set("host", "content-length", "connection", "upgrade", "expect", "date", "via", "warning")

  def send(url: String, r: Request): Future[Response] =
    val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs.toLong))
    for (name, values) <- r.headers if !restricted(name.toLowerCase); v <- values do b.header(name, v)
    val body = r.content.toArray
    val req =
      if body.isEmpty && (r.method == "GET" || r.method == "HEAD" || r.method == "DELETE") then
        b.method(r.method, HttpRequest.BodyPublishers.noBody())
      else b.method(r.method, HttpRequest.BodyPublishers.ofByteArray(body))
    client.sendAsync(req.build(), HttpResponse.BodyHandlers.ofByteArray()).asScala.map { res =>
      val headers = res.headers().map().asScala.toMap.collect {
        case (k, vs) if !k.startsWith(":") => k -> vs.asScala.mkString(", ")
      }
      Response(res.statusCode(), headers).withContent(Bytes(res.body()))
    }

  def apply[X](op: Fetch[X]): Future[X] = op match
    case okay.http.Fetch.Send(base, r, answer) => send(okay.http.Fetch.target(base, r), r).map(answer)
    case okay.http.Fetch.Call(m, url, h, body, answer) =>
      send(url, okay.http.Fetch.request(m, url, h, body)).map(answer)
