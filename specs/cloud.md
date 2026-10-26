# okay-serverless — a cloud as effects (spec, 2026-10-26)

Status: a design, built in this order — the effects and the memory platform (core), Cloudflare (every binding), AWS
(what DynamoDB carries; the rest mapped below).

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

| effect | operations | Cloudflare | AWS | elsewhere |
|---|---|---|---|---|
| `Kv` | get (with metadata), put (ttl, metadata), delete, list (prefix, cursor) | KV | DynamoDB (TTL attribute) | Firestore, Redis |
| `Blobs` | put (content type, metadata), get (a range), head, delete, list | R2 | S3 | GCS, Azure Blob |
| `Sql` | query, run (changes, last id), batch (atomic) — SQLite's dialect | D1 | Aurora Data API | SQLite file, Turso |
| `Queues` | send (delay), send a batch; a consumer settles each message (ack, retry with delay) | Queues | SQS | Pub/Sub |
| `Fetch` | an HTTP request out | `fetch` | `fetch` (Node) | JDK HttpClient |
| `Cache` | match, put (ttl), delete — by URL | Cache API | in memory / ElastiCache | — |
| `Env` | a variable, a secret | vars, secrets | env, Secrets Manager | env |
| `Services` | a request to another service by name | service bindings | Lambda invoke / URL | HTTP |
| `Limits` | may this key go now (a limit per period, configured per name) | Rate Limiting | DynamoDB counter | Redis |
| `Ai` | run a model: JSON in, JSON out | Workers AI | Bedrock | Vertex AI |
| `Vectors` | upsert, query (top k, filter), delete | Vectorize | S3 Vectors / OpenSearch | pgvector |
| `Analytics` | write a data point (indexes, blobs, doubles) | Analytics Engine | CloudWatch EMF | BigQuery |
| `Mail` | send a mail | Email Routing `send_email` | SES | SMTP |
| `Objects` | a request to an object by namespace and name | Durable Objects | the same Lambda, its state a DynamoDB space | — |
| `Sockets` | in an object: accept, send, close, the open ones by tag | DO WebSockets (hibernation) | API Gateway WebSockets | — |
| `Log` | okay-scala's `Log` | console | console → CloudWatch | — |

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

## Testing

- memory: every effect, every entry point, the workflow — `sbt coreJVM/test coreJS/test`;
- Cloudflare: the example Worker on `wrangler dev` (`cloudflare/example/e2e.sh`): KV, R2, D1, Queues, the cache,
  rate limits, analytics, a service binding, an object with WebSockets, cron. Workers AI and Vectorize have no local
  mode: their ports are written to the bindings' API, not run;
- AWS: on DynamoDB Local (`aws/test.sh`): `Kv`, `Limits`, objects' state.
