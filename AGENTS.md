# AGENTS.md — okay-serverless

Read before anything else, then `specs/durable.md`: a change of design is a change of the spec first.

- Durable state as an effect WITHOUT REPLAY: the state is data, a step a transaction (`Durable.step`), a process a
  machine (`Durable.machine`); a platform is a handler of `Storage` (Read with versions, Keys, Commit → Boolean).
- On okay-scala (Scala 3, published 0.1.1, `import okay.scala3.*`); a twin on okay-simple (Scala 2) later, beside, with
  the SAME stored format (JSON text values, `<scope>/#steps`, `<name>/state`) and the same scenarios.
- `Scenarios` is the TCK: every platform's handler passes it (memory here; a Durable Object on `wrangler dev`).
- `sbt coreJVM/test coreJS/test`. Lines at most 120 wide. Commits: plain messages, no attribution trailers.
- `aws`: `DynamoStorage(client, table, space, schedule)` — a commit one conditional `TransactWriteItems`. Tests:
  `aws/test.sh` (DynamoDB Local in ~/.cache/dynamodb-local, `OKAY_DYNAMODB`; the suite is ignored without it).
- `cloudflare`: `ObjectStorage(ctx)` — a table `okay_values(key, value, version)` in the object's SQLite storage, a
  commit one `transactionSync`, the alarm `setAlarm`. End to end: `sbt cloudflareExample && cloudflare/example/e2e.sh`
  (wrangler from the example's own node_modules; `--persist-to` a temp dir, the Worker killed and started again).
