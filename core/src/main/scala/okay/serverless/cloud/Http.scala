package okay.serverless.cloud

import okay.scala3.*

/** HTTP is okay-http's — the family's one model: a request (a path, a query, headers, a body text or bytes), a
 * response, bytes; named here so a program of the cloud says `Request`, `Response`, `Bytes` */
type Request = okay.http.Request
val Request = okay.http.Request
type Response = okay.http.Response
val Response = okay.http.Response
type Bytes = okay.http.Bytes
val Bytes = okay.http.Bytes

extension (r: Request)
  /** the body as text, bytes or not */
  def text: String = r.bytes.fold(r.body)(_.text)

extension (r: Response)
  /** the body as text, bytes or not */
  def text: String = r.bytes.fold(r.body)(_.text)
  /** a header's value, its name in any case */
  def headerOf(name: String): Option[String] = r.headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

/** HTTP OUT: okay-http's own effect — `Fetch.Send` any request, `Fetch.Call` a text one — performed from the
 * cloud */
type Fetch[X] = okay.http.Fetch[X]

object Fetch:
  /** `request` (a path, a query) to `base` (a scheme and an authority) */
  def apply[R[_]](base: String, request: Request)(using In[Fetch, R]): Response ! R =
    perform[Fetch](okay.http.Fetch.Send(base, request, identity))
  /** a GET of `url` */
  def get[R[_]](url: String)(using In[Fetch, R]): Response ! R =
    apply(okay.http.Fetch.base(url), okay.http.Fetch.request("GET", url, Map.empty, ""))
