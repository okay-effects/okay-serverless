# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-serverless/maven. No account and no token is needed to use it, only resolvers:
this one and those of the libraries it stands on.

## Using a release

```scala-sbt
resolvers ++= Seq("okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven")

libraryDependencies ++= Seq(
  "io.github.okay-effects" %%% "okay-serverless" % "0.1.0",             // Durable, the cloud's effects, memory
  "io.github.okay-effects" %%% "okay-serverless-cloudflare" % "0.1.0",  // Scala.js: a Worker, its objects
  "io.github.okay-effects" %%% "okay-serverless-aws" % "0.1.0",         // Scala.js on Node: a Lambda, DynamoDB
  "io.github.okay-effects" %% "okay-serverless-host" % "0.1.0"           // the JVM: a server of one's own
)
```

`okay-serverless-store` (okay-store's data in the cloud) needs the `okay-store` resolver too, and
`okay-serverless-faces` (okay-faces' REST and MCP) the `okay-faces` and `okay-mcp` ones; `okay-serverless-policy`
the `okay-proof` one. `okay-serverless-relay` (sealed rooms) and `okay-serverless-telegram` (a bot as an effect) need nothing more. `okay-serverless-accounts` (a phone app's backend: sign-in by a code by mail, Apple or Google; the person's
shared values — specs/accounts.md) needs nothing more either.
`okay-serverless-simple` is the twin on okay-simple, for Scala 2.13 and 3. Built on Scala 3.3 LTS
and Scala.js 1.22: a Scala.js build needs 1.22 or newer.

## Making one

1. On `main`, everything green: the tests, the example Worker end to end, AWS on DynamoDB Local and a Postgres:

   ```sh
   sbt coreJVM/test coreJS/test host/test storeJVM/test storeJS/test facesJVM/test facesJS/test policyJVM/test \
     +simpleJVM/test +simpleJS/test
   sbt cloudflareExample && cloudflare/example/e2e.sh
   aws/test.sh
   ```

2. The version: `ThisBuild / version` in `build.sbt` without `-SNAPSHOT`, committed, tagged (`v0.1.0`).

3. The repository written into `target/pages-maven/` (the twin for both Scala versions by `+`):

   ```sh
   rm -rf target/pages-maven
   sbt publishPages
   ```

4. Onto `gh-pages`, under `maven/`:

   ```sh
   git worktree add /tmp/okay-serverless-pages gh-pages   # the first time: --orphan -b gh-pages
   mkdir -p /tmp/okay-serverless-pages/maven
   rsync -a target/pages-maven/ /tmp/okay-serverless-pages/maven/
   touch /tmp/okay-serverless-pages/.nojekyll
   git -C /tmp/okay-serverless-pages add -A && git -C /tmp/okay-serverless-pages commit -m "release 0.1.0"
   git -C /tmp/okay-serverless-pages push origin gh-pages
   git push origin v0.1.0
   ```

5. The version back to the next `-SNAPSHOT`.

One version while it is unused: a fix before anyone depends on it is published again under the same number and the
tag moved; after, a new number.
