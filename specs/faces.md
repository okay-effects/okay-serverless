# okay-serverless — a described effect served from the cloud (spec, 2026-10-27)

Status: built (module `faces`); checked on memory (KV), SQLite, Postgres (a Lambda), and `wrangler dev` (D1, an
object).

## The idea

okay-faces serves any described effect (okay-simple's `Describe`) — REST resources (`RestDomains`) and MCP tools
(`McpFace.domain`) — and okay-store describes a mapped type (`Mapping.describe`) and its records (`Stores.describe`).
Here those faces answer a Worker's or a Lambda's requests, the effect handled on the cloud's store:

```text
Request ──▶ Faces.rest(describe, base) / Faces.mcp(describe, name)     okay-faces' own code, on OnCek
              │  a program of the described effect alone (okay-simple's row)
              ▼  OnCek.into[cloud row]                                  okay-simple's bridge: no Future
            Mapped[A] ─ StoreOn.data(mapping) ─▶ Data ─ StoreOn.sql / .kv / .durable
                                                      ─▶ D1, Postgres, KV, objects
```

Declare a type, map it, describe it: it has a REST API and tools for agents, in a Worker or a Lambda, its data in D1,
in Postgres through Hyperdrive or RDS Proxy, in KV, or in an object of its own — nothing more written.

## The faces

- `Faces.rest(describe, base)`: `GET base` the description (the operations as links with their JSON Schemas); each
  operation at `base/<name>`, a `GET` with the arguments in the query when it reads and they are flat, else a `POST` of
  JSON; the result JSON; arguments that do not read by the shape a 400 with the problems — before the store is
  touched.
- `Faces.mcp(describe, name)`: MCP over HTTP, stateless (Streamable HTTP with JSON answers): each `POST` one JSON-RPC
  message, dispatched by okay-mcp (`Servers.dispatch`) as from an initialised session; its answer the body; a
  notification 202. The tools are `<name>_<operation>`, their input and output schemas the shapes'.

HTTP is okay-http's (0.1.1): okay-faces 0.1.0 was compiled against okay-http 0.1.0, so okay-http 0.1.1 keeps 0.1.0's
constructors (`Response(status, headers, body)`, `Request` of five) — code compiled against them runs unchanged.

## Checked

- memory cloud, KV: REST's description, save, load, count, a value the shape refuses (400); MCP's initialize, tools,
  a call — saved through MCP, read through REST;
- SQLite: `create` (the key the database's), `find` by a condition sent as JSON;
- a Lambda on Postgres: REST's create, MCP's count;
- `wrangler dev`: REST (description, create with a D1 key, find), MCP (initialize, tools, a call) on D1; REST of a
  shop's own store in its object (a request one step).

## Sessions, and who calls

A described effect's MCP is stateless here: a tool is an operation, nothing kept between calls. A DIALOGUE — a program
that offers actions, asks for consent by elicitation, goes on across calls — has sessions, event streams and its
continuation parked between requests in okay-faces itself (`McpFace.http`, on Cloudflare `Door.mcp`, each session a
Durable Object); it is not written again here. Who may call either: `Auth.guarded` in front of the app (specs/cloud.md,
"Admission").
