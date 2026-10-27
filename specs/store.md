# okay-serverless — okay-store in the cloud (spec, 2026-10-27)

Status: built (module `store`), checked on memory, SQLite, Postgres, and on `wrangler dev` (D1, Hyperdrive, an object).

## Where it stands

okay-serverless is the cloud as effects; okay-store is a model of data over effects: `Data` (documents by key, a
query with a reference meaning, `Where`/`Order`) and `Mapped[A]` (a type's repository, the whole of an ORM, its fields
checked by the compiler). One is under the other: okay-store's operations, handled on the cloud's effects.

```text
a program     Repo.save / find / create (Mapped[A])        Docs.get / query (Data)
              │ StoreOn.data(mapping) — a value its document by the shape
              ▼
Data          StoreOn.sql(db, dialect)   StoreOn.kv(ns)    StoreOn.durable
              │ okay-store's SqlData     │ a scan, the     │ an object's state: a step one
              ▼                          ▼ reference       ▼ transaction
the cloud     Sql: D1, Hyperdrive/RDS,   Kv: KV, DynamoDB  Durable: a Durable Object, a DynamoDB space
              an SQLite file
```

okay-store's operations are data — case classes carrying their answers — so an okay-scala program performs them as
they are (`Docs`, `Repo`); no bridge between the two libraries' rows. The SQL is okay-store's own, moved to its `core`
for this (`Dialect`, `SqlData`: text and SQL's own arguments, no connection), so JDBC on the JVM and D1 or `pg` in a
Worker or a Lambda run the same statements; its tests compare them with the reference on SQLite and Postgres.

## The backends

- `StoreOn.sql(db, dialect)`: a collection a table `(key, doc)`, made at first use with `_keys`, the sequences' table;
  `Dialect.sqlite` for D1 and SQLite files, `Dialect.postgres` for Hyperdrive and RDS Proxy. Keys from the database
  (`INSERT … ON CONFLICT … RETURNING`), never given twice. Each operation is its statement: a transaction across
  them is the program's step, or the database's.
- `StoreOn.kv(ns)`: a document under `<collection>/<key>`; a query lists and reads the collection and applies the
  reference. Eventually consistent and no sequences: `Fresh` fails, naming SQL and objects.
- `StoreOn.durable`: in an object, a document under `data/<collection>/<key>`, a sequence under
  `data/_keys/<collection>`; inside `Durable.step` the program's operations are one transaction — a store per tenant,
  per shop, per user, with no database to run.

## Checked

- memory cloud: KV, an object (the documents in its storage, the keys, a removal);
- SQLite (`SqliteSql`, the JVM): the program's answers, the table, the keys;
- Postgres (`aws/test.sh`, a Postgres from `tools/`): the same answers, through `AwsPorts.postgres`;
- `wrangler dev` (`cloudflare/example/e2e.sh`): one program on D1, on Postgres through Hyperdrive, in an object.

## Not yet

`Store` (okay-store's journaled records) has its handler on `Data` in okay-store (`Stores.onData`), written over
okay-simple's interface; run here it needs okay-simple's runner (a program to a `Future`), not done. A type served as
REST or MCP from its mapping (`Mapping.describe`) is okay-faces' — on okay-simple, the same way.
