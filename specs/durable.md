# okay-serverless — durable state as an effect (spec, 2026-10-26)

Status 2026-10-26: `core` (the effect, steps, machines, `once`, memory with faults, the scenarios — JVM and Scala.js)
and `cloudflare` (`ObjectStorage` on a Durable Object's SQLite storage; the example account, end to end on
`wrangler dev`: `cloudflare/example/e2e.sh` — events, a refusal, a restart going on from the committed state, an
alarm) and `aws` (`DynamoStorage` on DynamoDB, tested on DynamoDB Local: `aws/test.sh`) built. Next: the twin on
okay-simple.

## The problem

A serverless instance — a Cloudflare Durable Object, an AWS Lambda, an Azure function — is evicted, restarted,
or run twice at once, at any moment. A program that keeps state must find it again, consistent, after any of these.
The usual answer (Temporal, Azure Durable Functions) is REPLAY: keep a journal of what happened, run the program again
from the start, answering from the journal. It works, and it costs: the code must stay deterministic and unchanged
while a run is alive, and a long run replays a long journal.

okay-serverless does it WITHOUT REPLAY: what is kept is the state itself, as data, written atomically with each
step's changes; a restart loads it and goes on. Nothing runs again but a step that never committed.

Why not keep the continuation instead (on okay-scala's CEK machine `k` is data)? Because it holds closures — code,
not data — which cannot be written to storage and read by another process, perhaps of another version. So the unit
of durability is a STEP over a STATE: the state data, the step a program.

## The program's words: the effect `Durable`

```text
Durable.get[A](key)(using Codec[A])          : Option[A]      -- what was committed, or written in this step
Durable.put[A](key, a)(using Codec[A])       : Unit           -- written when the step commits
Durable.delete(key)                          : Unit
Durable.list(prefix)                         : List[String]   -- the keys under a prefix
Durable.wakeAt(ms)                           : Unit           -- an alarm: the object woken then (an event)
Durable.once[A](name)(p)(using Codec[A])     : A              -- p's value kept: run once per step that commits
```

A program says `Has[Durable]` and nothing of the cloud.

## A step is a transaction

`Durable.step(p)`: `p` runs against a SNAPSHOT — its reads see what was committed, and its own writes (read your
writes). Its writes are kept by the handler, not sent; when `p` ends they are COMMITTED, all of them atomically, with
the alarm it set. If `p` fails (`Fail`, `Abort`, a throw) nothing is written. If the commit finds that what the step
read has changed meanwhile (another instance committed), the step is RUN AGAIN from its start — legitimately: `p` is
a value, and nothing it wrote left the handler. A step's external calls are its only lasting traces before the
commit: `once` keeps a call's value in the step's writes, and gives its name as an idempotency key, so a step run
again asks the outside world the same question with the same key.

## A durable process: state and events

```text
Durable.machine[S, E](name, init)(step: (S, E) => S ! R)(using Codec[S]) : E => S ! R
```

On each event (a request, a message, an alarm): the state `S` loaded (or `init`), the step run, the new state put —
all one `Durable.step`. Restarted anywhere, the next event finds the state of the last committed step. `S` is data
with a codec; the step is code, so a new version of the code goes on from the old state (a state's shape that
changes is the codec's to read, as a schema's).

## The platforms: `Storage`, one handler each

The abstract effect is handled into a platform's `Storage` — what a cloud's storage does, nothing more:

```text
Storage.Read(keys)                 : Map[key, (value, version)]   -- versions: what a commit checks
Storage.List(prefix)               : List[key]
Storage.Commit(read, writes, alarm): Boolean                      -- false: what was read has changed; nothing written
```

- CLOUDFLARE DURABLE OBJECTS (Scala.js): one thread an object, its storage transactional (SQLite-backed objects:
  `ctx.storage.transactionSync`, the synchronous `kv` API) — a commit never conflicts; the alarm
  `ctx.storage.setAlarm`.
- AWS LAMBDA + DynamoDB: no single writer — a commit is a `TransactWriteItems` conditioned on the versions read;
  `false` on a conflict, and the step runs again. Alarms by EventBridge Scheduler.
- Later the same way: Azure (Cosmos DB etags), GCP (Firestore transactions).
- IN MEMORY, for tests: the same contract, with faults on demand — a crash before or after a commit, a conflicting
  writer, an eviction (a new instance on the same storage).

## The same on okay-simple (Scala 2)

The effect, the step, the machine are written again on okay-simple, beside: the same words, the same `Storage`
protocol, the same STORED FORMAT (keys, values as JSON text, versions), and the same scenarios (a TCK: crash, conflict,
eviction, alarm, once), so state written by one is read by the other.

## Modules

- `core` (okay-scala, JVM + Scala.js): `Durable`, `step`, `machine`, `once`, `Codec`, `Storage`, the in-memory store
  with faults, the TCK of scenarios.
- `cloudflare` (Scala.js): `Storage` on a Durable Object; an example Worker; tested on `wrangler dev`.
- `aws` (Scala.js on Node, later the JVM): `DynamoStorage(client, table, space, schedule)` — `Storage` on DynamoDB.
  A table of items `(space, key) → value, version`. A commit is one `TransactWriteItems`:
  - a key read and written: updated (or deleted) on condition its version is still the one read, or it still absent;
  - a key only read: a `ConditionCheck`;
  - a key written unread: updated as it is (`ADD version 1`).
  A conflict cancels it all (`ConditionalCheckFailed`): `false`, and the step runs again. At most 100 items a step.
  Reads strongly consistent. The alarm an item `#alarm`, kept the earliest, handed to `schedule` (EventBridge
  Scheduler's, the caller's) once the commit is in. Tested on DynamoDB Local (`aws/test.sh`): a step, a conflict
  injected, two instances incrementing one counter at once (twenty of twenty), an eviction, the earliest alarm.
- `simple` (okay-simple, Scala 2.13 and 3): the same, beside.
- `policy` (okay-policy): `DurableStates` — a policy's `States` on `Durable` (`policy/<state>/<key>`, a value and its
  window's start); `DurableStates.enforce(policy, call, scope, now)` — a decision and its changes one step: two
  instances spending one budget at once cannot both pass (the second is decided again on the new value).

Users: okay-guard's policy state (`States`: decide and change, atomically, in one step), okay-faces' and okay-ui's
sessions on Workers.
