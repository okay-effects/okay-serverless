# AGENTS.md — okay-serverless

Read before anything else, then `specs/durable.md`: a change of design is a change of the spec first.

- Durable state as an effect WITHOUT REPLAY: the state is data, a step a transaction (`Durable.step`), a process a
  machine (`Durable.machine`); a platform is a handler of `Storage` (Read with versions, Keys, Commit → Boolean).
- On okay-scala (Scala 3, published 0.1.1, `import okay.scala3.*`); a twin on okay-simple (Scala 2) later, beside, with
  the SAME stored format (JSON text values, `<scope>/#steps`, `<name>/state`) and the same scenarios.
- `Scenarios` is the TCK: every platform's handler passes it (memory here; a Durable Object on `wrangler dev`).
- `sbt coreJVM/test coreJS/test`. Lines at most 120 wide. Commits: plain messages, no attribution trailers.
