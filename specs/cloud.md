# okay-serverless — a cloud as effects (spec, 2026-10-26)

Status 2026-10-28: RELEASED 0.1.0 (RELEASING.md), republished with mail as people get it (HTML, reply-to, a
name; `Mails.deliver`) and an object's SQL tested under Node — both from okay-workers, now retired. Built:
- the effects, `App`/`Actor`, `Workflow`, the memory platform (`MemoryCloud`), SQL on SQLite on the JVM (`SqliteSql`);
- Cloudflare: every effect on its binding, the Worker's entry points and objects (`Worker`, `entry.js`);
- AWS: `Kv`, `Limits`, objects' state on DynamoDB; `Sql` on Postgres (RDS Proxy); `Env`, `Fetch`, `Log`,
  `Analytics` (EMF); the Lambda's events
  (API Gateway, SQS, EventBridge, an object's alarm). Not yet: S3, SQS out, SES, Bedrock, S3 Vectors,
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
| `Sql` | query, run (changes, last id), batch (atomic) — the database's dialect; `?` parameters, a union type |
| `Queues` | send (a delay), send a batch; a consumer settles each message (ack, retry with a delay) |
| `Fetch` | an HTTP request out — okay-http's own effect |
| `Cache` | match, put (ttl), delete — by URL; `Cache.through` |
| `Env` | a variable, a secret — okay-scala's `std.Env` |
| `Services` | a request to another service by name |
| `Limits` | may this key go now (a limit per period, configured per name) |
| `Ai` | run a model: JSON in, JSON out |
| `Vectors` | upsert, query (top k, a filter), delete — cosine |
| `Analytics` | write a data point (indexes, blobs, doubles) |
| `Mails` | send a mail (text, and HTML beside it; a reply-to; the sender's name); deliver one: sent, or why not |
| `Objects` | a request to an object by namespace and name |
| `Sockets` | in an object: accept, send, close, the open ones (by tag) |
| `Log`, `Clock`, `Async` | okay-scala's own |

Where each is, on each cloud:

| effect | Cloudflare | AWS | elsewhere |
|---|---|---|---|
| `Kv` | KV | DynamoDB (TTL attribute) | Firestore, Redis |
| `Blobs` | R2 | S3 | GCS, Azure Blob |
| `Sql` | D1 (SQLite); Hyperdrive (Postgres) | RDS Proxy, Aurora (Postgres) | an SQLite file (`SqliteSql`) |
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

## Admission, tokens

`App.admit(request)`: before anything — `fetch` or an object's request as it is — a request let in (possibly said
more of) or the answer that refuses it; Workers, Lambdas and the memory cloud (`MemoryCloud.serve`) admit every request
first. `Auth.guarded(app, who, open)`: a bearer token taken to its caller by `who` — the environment's secret
(`Auth.secret`), tokens kept in KV (`Auth.kv`), any check — without one, or one not known, a 401 (`WWW-Authenticate:
Bearer`); known, the request goes on with its caller in `okay-caller`, one sent from outside under that name always
dropped; `open` paths let in as they are. Compared in constant time. Checked in memory, on a Lambda, on `wrangler dev`
(a private route; an object's stream behind the token).

## Event streams, work after the answer, names of their own

In an object, `Sockets.stream(request)` answers with an event stream (server-sent events) — its socket's sends its
events, its closing its end — and `Sockets.later(work)` runs `work` once the answer has gone, the object kept while it
runs: on Cloudflare a `TransformStream` and the runtime's `waitUntil`; in memory a future the test can wait for
(`settled`). On a Lambda, work after an answer is not kept (the runtime freezes): a queue's message is the way.
`Objects.unique(namespace)`: a name never given before, not to be guessed — an object of its own (a session, an
upload).

## Mail (2026-10-28, for szykownia.pl's sites; what okay-workers had)

A `Mail` is a letter as people get it: `from` (an address, `fromName` shown with it), `to`, `subject`, `text`, and,
when given, `html` — the two as one multipart/alternative — and `replyTo`. `Mails.send` fails the program when the
mail does not go (as every effect's failure); `Mails.deliver` answers it instead — `Right(())` sent, `Left(why)` not
— for a program whose work stands without its letters (an order placed is placed). On Cloudflare a letter is a raw
MIME message (`Mime.message`: the headers Cloudflare asks — `From`, `To`, `Subject` as RFC 2047 words, `Message-ID`,
`Date` —, each part UTF-8 in base64) in an `EmailMessage` through a `send_email` binding: Email Routing's (to verified
destinations, any plan) and Email Service's (any recipient, Workers Paid) take the same message. In memory a letter is
kept (`MemoryCloud.sent`); `deliver` there always sends.

## Workflows, without replay

Cloudflare Workflows replay a function, its steps' results kept. Here a workflow is DATA: a list of stages — `step`
(a function of the state, retried), `sleep`, `waitFor` (an event by name) — run by a durable machine: its state the
stage it is at and the workflow's value; each stage one `Durable` step, a sleep an alarm, an event delivered by
`Flow.send`. Restarted anywhere, it goes on from the stage committed. A new version of the code goes on from the old
state, the stages named.

## SQL: D1 and Hyperdrive

A database is a name; what it is, the platform's. On Cloudflare a D1 binding is SQLite; a Hyperdrive binding is a
Postgres of one's own (Neon, RDS, Supabase, on premises) reached through Cloudflare's pool and cache — the port asks
the binding which (`connectionString`). Postgres by `pg` (`PostgresSql`, shared with AWS): `?` numbered `$1, $2…`
(not in quotes, quoted names, comments), a batch one transaction on one connection, a connection a call (Hyperdrive
pools them). On AWS the same port with a pool a database kept across invocations: RDS Proxy is AWS's Hyperdrive. The
program writes its database's dialect; the effect is the same. A run's last id is SQLite's; Postgres says it by
`RETURNING`.

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
- Cloudflare, under Node (`sbt cloudflare/test`): `ObjectStorage`'s own SQL — the object's table, its commits in a
  transaction — on SQLite (Node's `node:sqlite`, as the Durable Object's storage is SQLite) behind the object's
  `ctx.storage` shape, through the scenarios every platform passes;
- Cloudflare: the example Worker on `wrangler dev` (`cloudflare/example/e2e.sh`): KV, R2, D1, Hyperdrive (a Postgres
  from `tools/`, npm's embedded-postgres, its URL the binding's local connection string), Queues, the cache,
  rate limits, analytics, a service binding, an object with WebSockets, cron. Workers AI and Vectorize have no local
  mode: their ports are written to the bindings' API, not run;
- AWS: on DynamoDB Local (`aws/test.sh`): `Kv`, `Limits` across two instances, analytics in the log, a Lambda given
  API Gateway's, SQS's and EventBridge's events, an object's state; Postgres (the same `tools/` one) as RDS Proxy
  gives it.
