# Changelog

## 0.1.1 (not released)

### The JVM's handlers left the core

okay-audit (okay-effects/okay's boundary checks) found real I/O in the core's business package: the JDK's
`HttpClient`, raw sockets, `java.sql` and sqlite-jdbc on the classpath of every business module built on
`okay-serverless`. They are a module of their own now (specs/cloud.md, "Layers"):

| 0.1.0 | 0.1.1 |
|---|---|
| `okay.serverless.cloud.JdkFetch` (`okay-serverless`) | `okay.serverless.jvm.JdkFetch` (`okay-serverless-jvm`) |
| `okay.serverless.cloud.JdkTcp` (`okay-serverless`) | `okay.serverless.jvm.JdkTcp` (`okay-serverless-jvm`) |
| `okay.serverless.cloud.SqliteSql` (`okay-serverless`) | `okay.serverless.jvm.SqliteSql` (`okay-serverless-jvm`) |
| sqlite-jdbc, a dependency of `okay-serverless` (JVM) | a dependency of `okay-serverless-jvm` only |
| `Ai.attempted`, `Objects.together` (a port's helpers) | `Port.attempted`, `Port.together` |
| `Envelope.Seen` (mutable) given to `Envelope.open` | also `Envelope.Nonces` (a value) and `Envelope.check` |

Migrating:

- `-host` depends on `-jvm`: a build on the host needs nothing.
- A build on the core that names `JdkFetch`, `JdkTcp` or `SqliteSql` adds
  `"io.github.okay-effects" %% "okay-serverless-jvm" % "0.1.1"` (`% Test` when only its tests do — `SqliteSql` under
  `MemoryCloud`), and imports them by name: `import okay.serverless.jvm.SqliteSql`. A wildcard
  `import okay.serverless.jvm.*` beside `import okay.serverless.cloud.*` makes the deprecated old names ambiguous.
- Until then the old names compile: `okay.serverless.cloud.JdkFetch` / `JdkTcp` / `SqliteSql` (a type and its
  `apply`, the same arguments) forward to the new ones, deprecated — they are in `okay-serverless-jvm` too, so the
  dependency is needed either way.
- `Ai.attempted` and `Objects.together` forward to `Port`'s, deprecated.

Nothing else moved: the effects, `App`, `Actor`, `Platform`, `Port`, `MemoryCloud`, `Crypto` (and `Crypto.run` on the
runtime's own) stay in the core, in `okay.serverless.cloud`. The stored format, the Cloudflare and AWS modules and the
Scala.js side are unchanged.

## 0.1.0

The first release (RELEASING.md).
