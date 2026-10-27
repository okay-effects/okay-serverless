# okay-serverless — a cloud as effects (spec, 2026-10-26)

Status 2026-10-26, built:
- the effects, `App`/`Actor`, `Workflow`, the memory platform (`MemoryCloud`), SQL on SQLite on the JVM (`SqliteSql`);
- Cloudflare: every effect on its binding, the Worker's entry points and objects (`Worker`, `entry.js`);
- AWS: `Kv`, `Limits`, objects' state on DynamoDB; `Env`, `Fetch`, `Log`, `Analytics` (EMF); the Lambda's events
  (API Gateway, SQS, EventBridge, an object's alarm). Not yet: S3, SQS out, Aurora, SES, Bedrock, S3 Vectors,
  Lambda invoke, API Gateway WebSockets — their ports fail, naming them.

## The idea

Everything a Cloudflare Worker can use is an EFFECT a program names; a cloud is a PLATFORM — one PORT an effect, the
effect's operations turned into the cloud's calls. A program says `Kv.get("sessions", id)`, never `env.SESSIONS.get`;
it runs on Workers, on Lambda, in memory, unchanged. What a cloud lacks, a port says so (a failure naming it), or
builds it from what it has (a rate limit from DynamoDB's conditional writes).

```text
program  A ! Cloud           -- the effects it names
port     [X] Op[X] => Future[X]
platform Platform(kv, blobs, sql, …)   -- one port an effect
run      Platform.run(platform)(p): A ! Async
```

Each operation names its RESOURCE by a logical name (`"sessions"`, `"uploads"`, `"main"`); a platform maps a name to
its binding (Cloudflare: the binding of that name; AWS: a table, a bucket, a queue URL by configuration).

## The effects

| effect | operations |
|---|---|
| `Kv` | get (with metadata), put (ttl, metadata), delete, list (prefix, cursor) |
| `Blobs` | put (content type, metadata), get (a range), head, delete, list |
| `Sql` | query, run (changes, last id), batch (atomic) — SQLite's dialect; parameters a union type |
| `Queues` | send (a delay), send a batch; a consumer settles each message (ack, retry with a delay) |
| `Fetch` | an HTTP request out — okay-http's own effect |
| `Cache` | match, put (ttl), delete — by URL; `Cache.through` |
| `Env` | a variable, a secret — okay-scala's `std.Env` |
| `Services` | a request to another service by name |
| `Limits` | may this key go now (a limit per period, configured per name) |
| `Ai` | run a model: JSON in, JSON out |
| `Vectors` | upsert, query (top k, a filter), delete — cosine |
| `Analytics` | write a data point (indexes, blobs, doubles) |
| `Mails` | send a mail |
| `Objects` | a request to an object by namespace and name |
| `Sockets` | in an object: accept, send, close, the open ones (by tag) |
| `Log`, `Clock`, `Async` | okay-scala's own |

Where each is, on each cloud:

| effect | Cloudflare | AWS | elsewhere |
|---|---|---|---|
| `Kv` | KV | DynamoDB (TTL attribute) | Firestore, Redis |
| `Blobs` | R2 | S3 | GCS, Azure Blob |
| `Sql` | D1 | Aurora Data API | an SQLite file (`SqliteSql`), Turso |
| `Queues` | Queues | SQS | Pub/Sub |
| `Fetch` | `fetch` | `fetch` (Node) | JDK HttpClient |
| `Cache` | Cache API | memory / ElastiCache | — |
| `Env` | vars, secrets | env, Secrets Manager | env |
| `Services` | service bindings | Lambda invoke / URL | HTTP |
| `Limits` | Rate Limiting | a DynamoDB counter | Redis |
| `Ai` | Workers AI | Bedrock | Vertex AI |
| `Vectors` | Vectorize | S3 Vectors / OpenSearch | pgvector |
| `Analytics` | Analytics Engine | CloudWatch EMF | BigQuery |
| `Mails` | Email Routing `send_email` | SES | SMTP |
| `Objects` | Durable Objects | the same Lambda, its state a DynamoDB space | — |
| `Sockets` | DO WebSockets (hibernation) | API Gateway WebSockets | — |

## The entry points

A Worker is an `App`; an object class an `Actor` — each a set of programs on the `Cloud` row (an actor's adds
`Storage`, for `Durable`, and `Sockets`):

```text
App:   fetch(Request): Response · scheduled(Scheduled) · queue(queue, Message): Settle · mail(IncomingMail)
Actor: fetch(Request): Response · alarm · message(Socket, text) · closed(Socket)
```

Cloudflare calls them from `export default { fetch, scheduled, queue, email }` and the Durable Object class; AWS from
the Lambda's events (API Gateway, EventBridge, SQS, SES); memory from a test. An actor's state is `Durable`'s: steps,
machines, alarms — on Cloudflare the object's SQLite, on AWS a DynamoDB space `namespace/name`, run in any instance.

## Workflows, without replay

Cloudflare Workflows replay a function, its steps' results kept. Here a workflow is DATA: a list of stages — `step`
(a function of the state, retried), `sleep`, `waitFor` (an event by name) — run by a durable machine: its state the
stage it is at and the workflow's value; each stage one `Durable` step, a sleep an alarm, an event delivered by
`Flow.send`. Restarted anywhere, it goes on from the stage committed. A new version of the code goes on from the old
state, the stages named.

## What is the family's, what is here

- JSON is okay-simple's `okay.effects.json` (as okay-guard's and okay-proof's): one JSON for both twins, the stored
  format the same by construction.
- `Env`, `Log`, `Clock`, `Async`, `Retry` are okay-scala's `std`: general, not a cloud's. `Env` was added there for
  this.
- HTTP is okay-http's (0.1.1): `Request`, `Response`, `Bytes` — a body text, or bytes when it is not UTF-8 — and its
  `Fetch` effect (`Fetch.Send` any request, `Fetch.Call` a text one), performed from the cloud's row; the cloud names
  them (`okay.serverless.cloud.Request`, …) as aliases. A request's path and query, never a URL: where it goes is the
  effect's (`Fetch(base, request)`, `Services.call(name, …)`, `Objects.call(ns, name, …)`).

## Testing

- memory: every effect, every entry point, the workflow — `sbt coreJVM/test coreJS/test`;
- Cloudflare: the example Worker on `wrangler dev` (`cloudflare/example/e2e.sh`): KV, R2, D1, Queues, the cache,
  rate limits, analytics, a service binding, an object with WebSockets, cron. Workers AI and Vectorize have no local
  mode: their ports are written to the bindings' API, not run;
- AWS: on DynamoDB Local (`aws/test.sh`): `Kv`, `Limits` across two instances, analytics in the log, a Lambda given
  API Gateway's, SQS's and EventBridge's events, an object's state.
