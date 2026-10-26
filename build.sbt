// okay-serverless: durable state as an effect, without replay — a step a transaction, the state data (specs/durable.md)
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports,privates,locals")
ThisBuild / resolvers += "okay-scala" at "https://okay-effects.github.io/okay-scala/maven"

val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE EFFECT: `Durable`, steps, machines, `once`; `Storage`, the platforms' contract; the store in memory with faults;
 * the scenarios every platform passes */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-serverless",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-scala" % "0.1.1", munit.value))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js).settings(publish / skip := true)
