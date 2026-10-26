// okay-serverless: durable state as an effect, without replay — a step a transaction, the state data (specs/durable.md)
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports,privates,locals")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-proof" at "https://okay-effects.github.io/okay-proof/maven")

val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE EFFECT: `Durable`, steps, machines, `once`; `Storage`, the platforms' contract; the store in memory with faults;
 * the scenarios every platform passes */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-serverless",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1", munit.value))

/** A POLICY'S STATE, DURABLE: okay-policy's `States` kept by `Durable` — a decision and its changes one step, one
 * transaction; decided again if the state changed meanwhile */
lazy val policy = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("policy")).dependsOn(core)
  .settings(name := "okay-serverless-policy",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-policy" % "0.1.0", munit.value))

/** ON CLOUDFLARE: `Storage` on a Durable Object (its SQLite storage, synchronous, one transaction a commit); an example
 * Worker in `cloudflare/example`, run by `wrangler dev` (`sbt cloudflareExample`, then `cloudflare/example/e2e.sh`) */
lazy val cloudflare = (project in file("cloudflare")).enablePlugins(ScalaJSPlugin).dependsOn(core.js)
  .settings(name := "okay-serverless-cloudflare",
    scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.ESModule)))

/** the example Worker's Scala, linked into cloudflare/example/okay.js */
val cloudflareExample = taskKey[File]("the example Worker's code, linked")
cloudflareExample := {
  val out = (cloudflare / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (cloudflare / Compile / fastLinkJS).value
  val to = baseDirectory.value / "cloudflare" / "example" / "okay.js"
  IO.copyFile(out / "main.js", to)
  to
}

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, policy.jvm, policy.js, cloudflare).settings(publish / skip := true)
