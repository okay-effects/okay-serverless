# Changelog

## 0.2.0 (2026-11-14)

0.2.0, not 0.1.1: classes moved between modules, a binary-incompatible change, so the minor number under 0.x
(`versionScheme` early-semver). 0.1.0 stays published as it was.

### Republished in place (2026-11-14): okay-audit's last 14 findings

okay-audit 0.1.4's last 14 findings on okay-serverless, acted on: a handler gives business code data, and business
code names no handler's class. 0.2.0 was published again with them (no new version while the family is its only
user); the `Cloud` row gained `Crypto`, so an effect's place in it (found at compile time) moved: code compiled
against the first 0.2.0 is to be compiled again.

| the first 0.2.0 | 0.2.0 as republished |
|---|---|
| `Crypto` outside `Cloud`; `Crypto.run(p)` installed the runtime's handler inside a program | `Crypto` last in `Cloud` (`… + Tcp + Crypto`), handled by `Platform.run` (`CryptoNative`); `Crypto.run` deprecated, only gives a program's `Crypto` to the row's |
| `Crypto.native` | removed: `CryptoNative.port` (a handler's) |
| `Ai.attempted`, `Objects.together` (deprecated forwarders) | removed: `Port.attempted`, `Port.together` |
| `App.failed(request, error: Throwable)` | `App.failure(request, error: Failed)` — `Failed(kind, message, trace)`, data; `failed` deprecated, still called by `failure`'s default |
| `accounts`' `Push.C` = `Cloud + Crypto` | `Push.C` = `Cloud` |
| `simple`'s `Serverless.memory(m: Memory)` | `Serverless.memory(m: Backend)` — `Backend` an interface `Memory` implements |
| the relay's JVM `Sealer` gave `Hashes` / `Pairing` its arrays | it writes its bytes as text itself (the JDK's base64url / hex, the same text); `Pairing.of(text)` |

Migrating:

- Recompile against 0.2.0 as republished (all of it: the row's places changed); clear the old 0.2.0 from the
  Coursier cache.
- `Crypto.run[InActor + Crypto, InActor, A](p)` still compiles (deprecated) and runs as before; to drop it, perform
  `Crypto` in the row itself — `Crypto.random[InActor](n)`, `Crypto.seal[Cloud](…)` — and remove the `+ Crypto`.
  `Crypto.run[Async + Crypto, Async, A]` (no `Crypto` in the rest) no longer compiles: outside a cloud,
  `Port.handle(CryptoNative.port)(p)`.
- `Crypto.native(op)` → `CryptoNative.port(op)`; `Ai.attempted` / `Objects.together` → `Port.attempted` /
  `Port.together`.
- An app overriding `failed(r, e: Throwable)` keeps working (deprecated): it is given `Failed.Again`, an exception
  whose message and `toString` are the original's — not its class. Move to
  `override def failure(r: Request, f: Failed)`: `f.message`, `f.kind` (the class name), `f.text`, `f.trace`.
- A handler of `Cloud` of one's own (not through `Platform.run`) handles `Crypto` too.
- `Serverless.memory` (simple) takes any `Backend`; a `Memory` is one: sources unchanged.

### The JVM's handlers left the core

okay-audit (okay-effects/okay's boundary checks) found real I/O in the core's business package: the JDK's
`HttpClient`, raw sockets, `java.sql` and sqlite-jdbc on the classpath of every business module built on
`okay-serverless`. They are a module of their own now (specs/cloud.md, "Layers"):

| 0.1.0 | 0.2.0 |
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
  `"io.github.okay-effects" %% "okay-serverless-jvm" % "0.2.0"` (`% Test` when only its tests do — `SqliteSql` under
  `MemoryCloud`), and imports them by name: `import okay.serverless.jvm.SqliteSql`. A wildcard
  `import okay.serverless.jvm.*` beside `import okay.serverless.cloud.*` makes the deprecated old names ambiguous.
- Until then the old names compile: `okay.serverless.cloud.JdkFetch` / `JdkTcp` / `SqliteSql` (a type and its
  `apply`, the same arguments) forward to the new ones, deprecated — they are in `okay-serverless-jvm` too, so the
  dependency is needed either way.
- `Ai.attempted` and `Objects.together` forward to `Port`'s, deprecated.
- The forwarders keep SOURCES compiling, not old binaries: a jar compiled against 0.1.0 that names
  `okay.serverless.cloud.JdkFetch` / `JdkTcp` / `SqliteSql` is to be compiled again (the classes are
  `okay.serverless.jvm`'s now). None of the family's published jars does (checked 2026-11-14).

Nothing else moved: the effects, `App`, `Actor`, `Platform`, `Port`, `MemoryCloud`, `Crypto` (and `Crypto.run` on the
runtime's own) stay in the core, in `okay.serverless.cloud`. The stored format, the Cloudflare and AWS modules and the
Scala.js side are unchanged.

## 0.1.0

The first release (RELEASING.md).
