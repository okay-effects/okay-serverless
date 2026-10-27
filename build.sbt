// okay-serverless: durable state as an effect, without replay — a step a transaction, the state data
// (specs/durable.md)
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports,privates,locals")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-proof" at "https://okay-effects.github.io/okay-proof/maven",
  "okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE EFFECT: `Durable`, steps, machines, `once`; `Storage`, the platforms' contract; the store in memory with faults;
 * the scenarios every platform passes */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-serverless",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json" % "0.1.1", "io.github.okay-effects" %%% "okay-http" % "0.1.1",
      munit.value))
  .jvmSettings(libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.53.1.0")

/** THE TWIN ON okay-simple, Scala 2.13 and 3: the same words, step, machine, `once`, the same stored format; the same
 * scenarios on the machine and on `Free` (`sbt +simpleJVM/test`) */
lazy val simple = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("simple"))
  .settings(name := "okay-serverless-simple", crossScalaVersions := Seq("3.3.8", "2.13.18"),
    scalacOptions ++= (if (scalaVersion.value.startsWith("2.")) Seq("-Xsource:3") else Nil),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-machine" % "0.1.1" % Test,
      "io.github.okay-effects" %%% "okay-effects-free" % "0.1.1" % Test, munit.value))

/** OKAY-STORE IN THE CLOUD: its `Data` and a type's repository (`Mapped`) on the cloud's SQL (D1, Postgres), KV and an
 * object's `Durable` state (specs/store.md) */
lazy val store = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("store")).dependsOn(core)
  .settings(name := "okay-serverless-store", libraryDependencies ++= Seq(
    "io.github.okay-effects" %%% "okay-store" % "0.1.0", munit.value))

/** A POLICY'S STATE, DURABLE: okay-policy's `States` kept by `Durable` — a decision and its changes one step, one
 * transaction; decided again if the state changed meanwhile */
lazy val policy = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("policy")).dependsOn(core)
  .settings(name := "okay-serverless-policy",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-policy" % "0.1.0", munit.value))

/** ON AWS: `Storage` on DynamoDB (Scala.js on Node — a Lambda's runtime has its SDK): no single writer, so a commit
 * is a `TransactWriteItems` conditioned on the versions read, `false` on a conflict and the step run again. Its tests
 * run on DynamoDB Local: `aws/test.sh` */
lazy val aws = (project in file("aws")).enablePlugins(ScalaJSPlugin).dependsOn(core.js, store.js % "test->test")
  .settings(name := "okay-serverless-aws", libraryDependencies += munit.value,
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
      .withEnv(Map("NODE_PATH" -> ((ThisBuild / baseDirectory).value / "aws" / "node_modules").getPath) ++
        Seq("OKAY_DYNAMODB", "OKAY_POSTGRES").flatMap(v => sys.env.get(v).map(v -> _)))))

/** ON CLOUDFLARE: `Storage` on a Durable Object (its SQLite storage, synchronous, one transaction a commit); an example
 * Worker in `cloudflare/example`, run by `wrangler dev` (`sbt cloudflareExample`, then `cloudflare/example/e2e.sh`) */
lazy val cloudflare = (project in file("cloudflare")).enablePlugins(ScalaJSPlugin).dependsOn(core.js, store.js)
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

lazy val root = (project in file("."))
  .aggregate(core.jvm, core.js, simple.jvm, simple.js, store.jvm, store.js, policy.jvm, policy.js,
    aws, cloudflare)
  .settings(publish / skip := true)
