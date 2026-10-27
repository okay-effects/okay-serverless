package okay.serverless.faces

import okay.effects.cek.OnCek
import okay.effects.describe.Describe
import okay.effects.{Sum, Void}
import okay.faces.mcp.McpFace
import okay.faces.rest.RestDomains
import okay.mcp.Rpc
import okay.http.{Request, Response}

/**
 * A DESCRIBED EFFECT SERVED FROM THE CLOUD (specs/faces.md): okay-faces' own faces of it — REST resources
 * (`RestDomains`) and MCP tools (`McpFace.domain`, over okay-mcp's `dispatch`) — as `Request => Response` programs on
 * okay-simple's interface on okay-scala's machine (`OnCek`), whose one effect is the described one. A Worker or a
 * Lambda puts such a program into its row (`OnCek.into`) and handles the effect there: a type's repository by
 * `StoreOn.data` and `StoreOn.sql` / `.kv` / `.durable`, okay-store's records by its own handler.
 *
 * A mapped type: `Faces.rest(Mapping.describe(sales), "/sales")`, `Faces.mcp(Mapping.describe(sales), "shop", "0.1")`.
 */
object Faces:
  /** a program of the described effect `E` alone: okay-simple's row of it */
  type Only[E[_]] = [X] =>> Sum[Void, E, X]

  private val restDomains: RestDomains[OnCek.type] = RestDomains()(using OnCek)
  private val mcpFace: McpFace[OnCek.type] = McpFace[OnCek.type]()(using OnCek)

  /** REST: `GET base` the description (the operations as links); each operation at `base/<name>`, a `GET` with the
   * arguments in the query if it reads and they are flat, else a `POST` of JSON; the result JSON, a problem a 400 */
  def rest[E[_]](d: Describe[E], base: String): Request => OnCek.![Response, Only[E]] =
    restDomains.serve[E, Only[E]](d, base).service

  /** MCP over HTTP, stateless (the protocol's Streamable HTTP, JSON answers): each `POST` one JSON-RPC message — the
   * operations as tools (`<name>_<operation>`) — its answer the response's body; a notification 202 */
  def mcp[E[_]](d: Describe[E], name: String, version: String = "0.1.0"): Request => OnCek.![Response, Only[E]] =
    import mcpFace.servers
    val server = servers.Server[Only[E]](name, version, mcpFace.domain[E, Only[E]](d))
    request =>
      if request.method != "POST" then
        OnCek.pure(Response(405, Map("Allow" -> "POST", "Content-Type" -> "text/plain; charset=utf-8"),
          "MCP: a JSON-RPC message by POST"))
      else Rpc.decode(request.body) match
        case Left(failed) => OnCek.pure(Response.json(Rpc.json(failed), 400))
        case Right(rpc) =>
          // stateless: every message as from an initialised session (`initialize` answered all the same)
          OnCek.map(servers.dispatch[Only[E]](server, servers.Session(initialized = true, None), rpc)) {
            case (_, Nil) => Response(202)
            case (_, answer :: _) => Response.json(Rpc.json(answer))
          }
