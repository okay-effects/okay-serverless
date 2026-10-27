# okay-serverless

Durable state for serverless programs as an algebraic effect — **without replay**. A step is a transaction, a process
keeps its state as data, and each cloud is a handler: Cloudflare Durable Objects and AWS Lambda + DynamoDB.

```scala
import okay.scala3.*
import okay.serverless.*

// an account: its state data, one step per event — wherever it runs, whatever evicted it
def account(event: Event) =
  Durable.machine[Account, Event, R, Storage + Fail % String]("account", Account(0, Nil)) { (a, e) =>
    e match
      case Deposit(n) => pure(a.copy(balance = a.balance + n))
      case Pay(n) if n > a.balance => Fail.raise(s"$n is more than the balance ${a.balance}")
      case Pay(n) => Durable.once("payment")(key => psp.charge(n, idempotencyKey = key))
        .map(id => a.copy(balance = a.balance - n, payments = a.payments :+ id))
  }(event)
```

- `Durable.step(p)` — a TRANSACTION: reads a snapshot (and its own writes), writes kept and committed all at once,
  nothing written if it fails, run again if what it read changed meanwhile.
- `Durable.machine(name, init)(step)` — a durable PROCESS: its state loaded, the step run, the new state committed.
  Restarted anywhere, the next event goes on from the last committed state; nothing is replayed.
- `Durable.once(name)(f)` — an external call run once per step, given a stable idempotency key.
- `Durable.wakeAt(ms)` — an alarm, set only if the step commits.

Why not replay (Temporal, Azure Durable Functions)? Replay keeps a journal and runs the program again; the code must
stay deterministic and unchanged while a run lives. Here what is kept is the state itself, written atomically with each
step — so a new version of the code goes on from the old state. Why not keep the continuation? It holds closures:
code, not data. The design: [specs/durable.md](specs/durable.md).

## A cloud as effects

Everything a Cloudflare Worker can use is an effect a program names — and each cloud a platform, one port an effect:
`Kv`, `Blobs`, `Sql`, `Queues`, `Fetch`, `Cache`, `Env`, `Services`, `Limits`, `Ai`, `Vectors`, `Analytics`, `Mails`,
`Objects` and, in an object, `Sockets`. A Worker is an `App` (`fetch`, `scheduled`, `queue`, `mail`), an object class
an `Actor` (`fetch`, `alarm`, a socket's messages), its state `Durable`'s. A `Workflow` is a list of stages — steps
retried, sleeps, events — run by an object without replay.

```scala
object Shop extends App:
  override def fetch(r: Request): Response ! Cloud = r.path match
    case "/today" => Cache.through[Cloud]("https://shop.example/today", 60)(
        Fetch.get[Cloud]("https://prices.example/today"))
    case "/upload" => Blobs.put[Cloud]("BUCKET", r.param("name").getOrElse("file"), r.content)
        .map(i => Response.text(i.etag))
    case "/job" => Queues.send[Cloud]("JOBS", r.text).as(Response.text("queued"))
    case _ => pure(Response.notFound())
  override def queue(q: String, m: Message): Settle ! Cloud =
    Sql.run[Cloud]("DB", "insert into jobs (body) values (?)", m.body).as(Settle.Ack)
```

HTTP is [okay-http](https://github.com/okay-effects/okay-http)'s model — a body text, or bytes when it is not
UTF-8. The same program runs in memory (`MemoryCloud`, for tests), on Cloudflare (`Worker`: every binding) and on AWS
(`Lambda`: what DynamoDB carries so far). The design and the map of each effect to each cloud:
[specs/cloud.md](specs/cloud.md).

## Data: okay-store

[okay-store](https://github.com/okay-effects/okay-store)'s types in a store run here on the cloud's effects —
`StoreOn.sql` (D1, Postgres through Hyperdrive or RDS Proxy, SQLite), `StoreOn.kv`, `StoreOn.durable` (an object a
store of its own, a step one transaction): `Repo.save(sale)`, `Repo.find(sales.at(_.qty) > 2)`, the field checked by
the compiler. okay-store's journaled records too (`Journal`): its own handler, put into the cloud's row by okay-simple's
bridge (`OnCek.into`), no Future between. [specs/store.md](specs/store.md).

## A type served: REST and MCP

[okay-faces](https://github.com/okay-effects/okay-faces) serves a described type as REST resources and MCP tools; here
it answers a Worker's or a Lambda's requests (`Faces.rest`, `Faces.mcp`), the type's operations on the cloud's store —
declare a type, map it, and it has an API and tools for agents, its data in D1, Postgres, KV or an object.
[specs/faces.md](specs/faces.md).

## Platforms

| | module | status |
|---|---|---|
| memory, with faults (crash, conflict, eviction) | `core` | the scenarios; every cloud effect (`MemoryCloud`) |
| Cloudflare Workers | `cloudflare` | every effect on its binding, Hyperdrive too; objects, sockets, workflows; e2e |
| AWS Lambda + DynamoDB | `aws` | `Durable`, KV, limits, objects, Postgres; API Gateway, SQS, EventBridge events |

The twin on [okay-simple](https://github.com/okay-effects/okay-simple), module `simple` (Scala 2.13 and 3, one
source): the same words, step, machine and `once`, and the same stored format, so state written by one is read by
the other.

## Build

Scala 3.3, sbt, on [okay-scala](https://github.com/okay-effects/okay-scala) 0.1.1; the JVM and Scala.js.

```text
sbt coreJVM/test coreJS/test                       # the scenarios
sbt +simpleJVM/test +simpleJS/test                 # the same on okay-simple, Scala 3 and 2.13
sbt cloudflareExample && cloudflare/example/e2e.sh  # the example Worker on a local workerd
aws/test.sh                                         # AWS on DynamoDB Local (Java, Node)
```

One of the [okay-effects](https://github.com/okay-effects) family. License: Apache 2.0.
