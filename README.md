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

## Platforms

| | module | status |
|---|---|---|
| memory, with faults (crash, conflict, eviction) | `core` | the scenarios every platform passes |
| Cloudflare Durable Objects | `cloudflare` | SQLite storage, `transactionSync`, alarms; end to end on `wrangler dev` |
| AWS Lambda + DynamoDB | `aws` | conditional `TransactWriteItems`, a step run again on a conflict; on DynamoDB Local |

The twin on [okay-simple](https://github.com/okay-effects/okay-simple), module `simple` (Scala 2.13 and 3, one
source): the same words, step, machine and `once`, and the same stored format, so state written by one is read by
the other.

## Build

Scala 3.3, sbt, on [okay-scala](https://github.com/okay-effects/okay-scala) 0.1.1; the JVM and Scala.js.

```text
sbt coreJVM/test coreJS/test                       # the scenarios
sbt +simpleJVM/test +simpleJS/test                 # the same on okay-simple, Scala 3 and 2.13
sbt cloudflareExample && cloudflare/example/e2e.sh  # the example account on a local workerd
aws/test.sh                                         # DynamoDB on DynamoDB Local (Java, Node)
```

One of the [okay-effects](https://github.com/okay-effects) family. License: Apache 2.0.
