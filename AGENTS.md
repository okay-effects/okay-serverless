# AGENTS.md — okay-serverless

## For agents writing code with okay-serverless

okay-serverless is durable state for serverless programs as an effect — WITHOUT REPLAY (a step is a transaction, a
process keeps its state as data) — plus a cloud as effects (`Kv`, `Blobs`, `Sql`, `Queues`, `Fetch`, …), each cloud a
handler: Cloudflare Workers/Durable Objects, AWS Lambda + DynamoDB, memory (tests), a JVM host (fallback).

```scala-sbt
resolvers ++= Seq("okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven")
libraryDependencies += "io.github.okay-effects" %%% "okay-serverless-cloudflare" % "0.1.0"   // or -aws, or the core
```

Modules: `okay-serverless` (core: the effects, memory; JVM + Scala.js), `-cloudflare` / `-aws` (Scala.js), `-host`
(JVM), `-store` (okay-store data), `-faces` (okay-faces REST/MCP), `-policy`, `-simple` (the twin on okay-simple,
Scala 2.13 and 3, same stored format). Scala 3.3 LTS on okay-scala (`import okay.scala3.*`); Scala.js 1.22+.

### The model: effects, a program, handlers, one run

Compiled and run against the published core 0.1.0 (Scala 3.3.8, scala-cli, JVM):

```scala
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.*
import okay.serverless.cloud.*
import scala.concurrent.ExecutionContext

// a durable process: its state is data, one step (a transaction) per event — restarted anywhere, it goes on from
// the last committed state; nothing is replayed
type S[X] = (Storage + Fail % String + Durable)[X]
def account(amount: Long) =
  Durable.machine[Long, Long, S, Storage + Fail % String]("account", 0L)((n, e) =>
    if e < 0 && -e > n then Fail.raise[String, S](s"${-e} is more than the balance $n")
    else pure[Long, S](n + e))(amount)

// a Worker as an App: the program names the cloud's effects, never a binding or a client
object Notes extends App:
  override def fetch(r: Request): Response ! Cloud = r.path match
    case "/put" => Kv.put[Cloud]("NOTES", r.param("k").getOrElse("x"), r.text).as(Response.text("ok"))
    case "/get" => Kv.get[Cloud]("NOTES", r.param("k").getOrElse("x")).map(v => Response.text(v.getOrElse("")))
    case _ => pure(Response.notFound())

// tests: the storage in memory (the handler of Storage), one run per instance
val m = Memory()
def instance[A](p: A ! (Storage + Fail % String)): Either[String, A] = run(m.handler(Fail.run(p)))
instance(account(5)); instance(account(7))   // Right(5), Right(12): the second instance read the state
instance(account(-100))                      // Left("100 is more than the balance 12")

// tests: the whole cloud in memory
given ExecutionContext = Async.defaultContext
val c = MemoryCloud(() => 0L)
for
  _ <- c.serve(Notes)(Request.post("/put?k=a", "hello"))
  r <- c.serve(Notes)(Request.get("/get?k=a"))
yield r.text                                 // "hello"
```

The entry points name the platform, one line each: `Worker(app, actors)` (Cloudflare, `CloudflarePorts(env)` every
binding), `Lambda(app, ports)` with `AwsPorts` (AWS), `Host.start(app, actors, HostConfig(dir = Paths.get("data")))`
(JVM). Objects are `Actor`s (`fetch`, `alarm`, socket messages) with `Durable` state; `Workflow` stages run by an
object, no replay.
Data: `StoreOn.sql` / `.kv` / `.durable` handle okay-store's `Mapped` (`Repo.save`, `Repo.find(sales.at(_.qty) > 2)`).
A described type as an API: `Faces.rest`, `Faces.mcp`.

### Design rules

- Business logic = programs over declared effects: `Response ! Cloud`, or a narrower row you declare. The row is
  what the program may do; `Durable` for state, cloud effects for services, your own domain effects on top.
- Real resources (bindings, DB connections, DynamoDB clients, keys, `env`) live only in the platform's ports/handlers
  built at the entry (`Worker`, `Lambda`, `Host.start`, `MemoryCloud`); programs get data and effects, never `env`
  objects or live clients. Secrets through the `Env` effect.
- Run only at the entry point (the platform runs the App); never a nested `run` inside a program or handler. okay-simple
  code (e.g. okay-store's `Store`) joins a cloud program by `OnCek.into`, never by a `Future` or a nested run.
- Gate and audit: every request passes `App.admit` first (`Auth.guarded(app, who)` for bearer tokens); okay-scala's
  `observe` / `around` / `intercept` over an effect (e.g. `Sql`, `Mails`, `Fetch`) to audit or refuse sensitive ops.
- Tests swap handlers: `Memory()` (with faults: `crashBeforeCommit`, `crashAfterCommit`, `beforeCommit` for a
  conflict), `MemoryCloud(clock)` (`serve`, `deliver` queues, `fireAlarms`), `SqliteSql` for SQL on the JVM. The same
  program then runs on Cloudflare / AWS / host unchanged. No mocks of clients.
- No ambient effects (`System.currentTimeMillis`, random, threads, `java.net`, files): use `Clock`, `Fetch`, `Blobs`,
  … — and inside a step, external calls only via `Durable.once`.
- Continuations: okay-scala's `k` is data and multi-shot within a run, but durability never keeps a continuation
  (it holds closures — code, not data) and never replays: what persists is state, written with each step.

### Pitfalls

- A step may run AGAIN (a conflict: what it read changed) — its body must be safe to rerun. An external call (a
  payment, a mail) goes in `Durable.once(name)(key => ...)`: run once per step, with a stable idempotency key that
  survives a crash before commit.
- `Durable.wakeAt(ms)` is set only if the step commits; a failed step (`Fail`) writes nothing.
- New code goes on from OLD state: keep state codecs (`Codec[A]`) backward-compatible; the stored format
  (`<name>/state`, `<scope>/#steps`, JSON text) is shared with the `simple` twin.
- Type parameters are explicit: `Kv.put[Cloud](...)`, `Fail.raise[String, S](...)`, `Durable.machine[S, E, R, Rest]`.
- `MemoryCloud` has no SQL (use `SqliteSql` on the JVM, the cloud's own elsewhere); Workers AI and Vectorize work
  only remotely, not under local `wrangler dev`.
- `-cloudflare` / `-aws` are Scala.js; `-host` is JVM. Put `@JSExportTopLevel` only in your app module, never in a
  library (it lands in every user's linked code).
- Cloudflare is the main platform, the JVM host the fallback: keep app code free of either (same `App`, same
  `Actor`s).
- `Mails.deliver` answers sent-or-why instead of failing; `Tcp` answers are `Either`.

### Pointers

- README: https://github.com/okay-effects/okay-serverless; `specs/durable.md`, `specs/cloud.md`, `specs/store.md`,
  `specs/faces.md` in the repo.
- Cross-language skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects).
- Build-time boundary checks for JVM code: `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven — checks that business code references no network/files/time/random/
  threads/reflection APIs; presets include `nested-run`.

If you see a way to improve okay-serverless, open a pull request against
https://github.com/okay-effects/okay-serverless; the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.

## For agents working on this repository

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
- Releases: RELEASING.md (0.1.0 on GitHub Pages). The example Worker is `cloudflareApp` (cloudflare/app), never
  published: a `@JSExportTopLevel` in a library would land in every user's linked code.
- `aws`: `DynamoStorage(client, table, space, schedule)` — a commit one conditional `TransactWriteItems`. Tests:
  `aws/test.sh` (DynamoDB Local in ~/.cache/dynamodb-local, `OKAY_DYNAMODB`; the suite is ignored without it).
- `aws`: `AwsPorts` (KV, limits, objects on DynamoDB; env, fetch, log, analytics as EMF; SQL by
  `AwsPorts.postgres`), `Lambda` (API Gateway, SQS, EventBridge, `okayAlarm`).
- Postgres for tests: `node tools/postgres.mjs` (npm's embedded-postgres; prints its URL, stops on SIGTERM) — used by
  `aws/test.sh` and `cloudflare/example/e2e.sh` (Hyperdrive's local connection string).
- `Mails`: a letter with its HTML, reply-to and sender's name, as MIME (`Mime.message`); `Mails.deliver` answers
  sent or why not instead of failing (specs/cloud.md "Mail").
- `Tcp` (connect, write, read, STARTTLS upgrade, close — answers are `Either`) and `Smtp.deliver` over it: on
  Cloudflare `connect()` (`globalThis.OkayConnect`), on the JVM `JdkTcp`, in memory `MemoryCloud.listen`.
- `relay` (specs/relay.md): sealed rooms — `Pairing`, `Envelope`, `Tokens`, `Hashes` (plain Scala), the `Rooms`
  actor and its `Steward`; `Sealer` and the host's `RelayClient` on the JVM, `Sealer` and `Guest` in a browser.
  `telegram`: the `Telegram` effect, by the Bot API over `Fetch` or in memory. okay-remote is their first user.
- `accounts` (specs/accounts.md): a phone app's backend — sign-in by a code by mail, Apple's or Google's identity
  token (RS256 checked in plain Scala, `Jwt`), sessions in KV, the person's shared values in their own object.
- `cloudflare`: `CloudflarePorts(env)` every binding, `Worker(app, actors)` the entry points; the example and its
  e2e use every binding locally but Workers AI and Vectorize (remote only).
- `cloudflare`: `ObjectStorage(ctx)` — a table `okay_values(key, value, version)` in the object's SQLite storage, a
  commit one `transactionSync`, the alarm `setAlarm`; its SQL tested under Node on `node:sqlite` (`sbt
  cloudflare/test`). End to end: `sbt cloudflareExample && cloudflare/example/e2e.sh`
  (wrangler from the example's own node_modules; `--persist-to` a temp dir, the Worker killed and started again).
- `host` (specs/cloud.md "The host"): a server of one's own, the FALLBACK — Cloudflare is the main platform.
  `Host.start(app, actors, config)`: one JVM process, its state in one directory (an SQLite file, blobs as files),
  its own HTTP and WebSocket server, cron, alarms, queues; `Ai` by any OpenAI-compatible server. `sbt host/test`.
