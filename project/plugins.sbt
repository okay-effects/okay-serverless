// cross-built: the JVM and Scala.js (Workers, Lambda on Node) — okay-scala's versions
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
// Scala Native for the pairing alone (a phone's guest at a sealed room: okay-remote's iPhone app)
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
