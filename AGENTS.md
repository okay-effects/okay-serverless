# AGENTS.md — okay-serverless

Read before anything else, then `specs/durable.md` and `specs/cloud.md`: a change of design is a change of the spec
first.

- Durable state as an effect WITHOUT REPLAY: the state is data, a step a transaction (`Durable.step`), a process a
  machine (`Durable.machine`); a platform is a handler of `Storage` (Read with versions, Keys, Commit → Boolean).
- On okay-scala (Scala 3, published 0.1.1, `import okay.scala3.*`); the twin `simple` on okay-simple 0.1.1 (one source
  for Scala 2.13 and 3; `sbt +simpleJVM/test`), with the SAME stored format (JSON text values, `<scope>/#steps`,
  `<name>/state`) and the same scenarios.
- `Scenarios` is the TCK: every platform's handler passes it (memory here; a Durable Object on `wrangler dev`).
- `sbt coreJVM/test coreJS/test`. Lines at most 120 wide. Commits: plain messages, no attribution trailers.
- A CLOUD AS EFFECTS (`okay.serverless.cloud`): each service an effect, each cloud a `Platform` of `Port`s; `App` and
  `Actor` the entry points; `Workflow` stages run by an object. A new cloud is a new set of ports, never a change of
  the effects. What is general goes to the family: JSON okay-simple's, `Env`/`Log`/`Clock` okay-scala's `std`.
- `store`: okay-store's `Data` and `Mapped` performed as they are (`Docs`, `Repo`), handled by `StoreOn.data`, `.sql`,
  `.kv`, `.durable` (specs/store.md). The SQL is okay-store's (`okay.store.SqlData`): a change of it is okay-store's.
  okay-simple code (okay-store's `Store`) joins a cloud program by `OnCek.into` (okay-effects-cek), never by a Future.
- `faces`: okay-faces' REST and MCP of a described effect (`Faces.rest`, `Faces.mcp`), put into a cloud program by
  `OnCek.into` (specs/faces.md). okay-faces and okay-mcp are the other session's: used by their releases, not changed.
- `aws`: `DynamoStorage(client, table, space, schedule)` — a commit one conditional `TransactWriteItems`. Tests:
  `aws/test.sh` (DynamoDB Local in ~/.cache/dynamodb-local, `OKAY_DYNAMODB`; the suite is ignored without it).
- `aws`: `AwsPorts` (KV, limits, objects on DynamoDB; env, fetch, log, analytics as EMF; SQL by
  `AwsPorts.postgres`), `Lambda` (API Gateway, SQS, EventBridge, `okayAlarm`).
- Postgres for tests: `node tools/postgres.mjs` (npm's embedded-postgres; prints its URL, stops on SIGTERM) — used by
  `aws/test.sh` and `cloudflare/example/e2e.sh` (Hyperdrive's local connection string).
- `cloudflare`: `CloudflarePorts(env)` every binding, `Worker(app, actors)` the entry points; the example and its
  e2e use every binding locally but Workers AI and Vectorize (remote only).
- `cloudflare`: `ObjectStorage(ctx)` — a table `okay_values(key, value, version)` in the object's SQLite storage, a
  commit one `transactionSync`, the alarm `setAlarm`. End to end: `sbt cloudflareExample && cloudflare/example/e2e.sh`
  (wrangler from the example's own node_modules; `--persist-to` a temp dir, the Worker killed and started again).
