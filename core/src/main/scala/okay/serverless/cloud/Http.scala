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

/** WHERE A REQUEST COMES FROM, roughly (specs/cloud.md "The visitor's approximate place"): the platform's guess from
 * the IP — a city, a region, a country, a postcode, a point —, for a suggestion only */
final case class Visitor(city: Option[String] = None, region: Option[String] = None, country: Option[String] = None,
                         postcode: Option[String] = None, lat: Option[Double] = None, lon: Option[Double] = None)

object Visitor:
  /** the headers it is read from (Cloudflare's visitor location names): a client's own of these never pass */
  val headers: Vector[String] = Vector("cf-ipcity", "cf-region", "cf-ipcountry", "cf-postal-code", "cf-iplatitude",
    "cf-iplongitude", "cf-timezone")
  /** a percent-encoded value read back (UTF-8); a malformed one as it is */
  def decoded(s: String): String =
    if !s.contains('%') then s
    else
      val out = new java.io.ByteArrayOutputStream
      var i = 0
      var ok = true
      while i < s.length do
        if s(i) == '%' && i + 2 < s.length then
          scala.util.Try(Integer.parseInt(s.substring(i + 1, i + 3), 16)).toOption match
            case Some(b) => out.write(b); i += 3
            case None => ok = false; i = s.length
        else
          out.write(s.substring(i, i + 1).getBytes("UTF-8")); i += 1
      if ok then new String(out.toByteArray, "UTF-8") else s

extension (r: Request)
  /** the visitor's approximate place, when the platform said it */
  def visitor: Option[Visitor] =
    def h(n: String) = r.header(n).map(x => Visitor.decoded(x).trim).filter(_.nonEmpty)
    val v = Visitor(h("cf-ipcity"), h("cf-region"), h("cf-ipcountry"), h("cf-postal-code"),
      h("cf-iplatitude").flatMap(_.toDoubleOption), h("cf-iplongitude").flatMap(_.toDoubleOption))
    Option.when(v != Visitor())(v)
