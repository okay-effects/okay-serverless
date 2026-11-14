package okay.serverless.cloud

import scala.concurrent.ExecutionContext

/* THE JVM'S HANDLERS' OLD NAMES: until 0.1.0 they were in the core (`okay-serverless`), in this package. They are in
 * `okay-serverless-jvm` now, package `okay.serverless.jvm` — real I/O is not the business code's classpath. These
 * names forward to them, for a release or two (CHANGELOG.md) */

@deprecated("okay.serverless.jvm.JdkFetch, module okay-serverless-jvm", "0.1.1")
type JdkFetch = okay.serverless.jvm.JdkFetch
@deprecated("okay.serverless.jvm.JdkFetch, module okay-serverless-jvm", "0.1.1")
object JdkFetch:
  def apply(timeoutMs: Int = 30000)(using ExecutionContext): okay.serverless.jvm.JdkFetch =
    okay.serverless.jvm.JdkFetch(timeoutMs)

@deprecated("okay.serverless.jvm.JdkTcp, module okay-serverless-jvm", "0.1.1")
type JdkTcp = okay.serverless.jvm.JdkTcp
@deprecated("okay.serverless.jvm.JdkTcp, module okay-serverless-jvm", "0.1.1")
object JdkTcp:
  def apply(timeoutMs: Int = 30000)(using ExecutionContext): okay.serverless.jvm.JdkTcp =
    okay.serverless.jvm.JdkTcp(timeoutMs)

@deprecated("okay.serverless.jvm.SqliteSql, module okay-serverless-jvm", "0.1.1")
type SqliteSql = okay.serverless.jvm.SqliteSql
@deprecated("okay.serverless.jvm.SqliteSql, module okay-serverless-jvm", "0.1.1")
object SqliteSql:
  def apply(dir: Option[java.io.File] = None): okay.serverless.jvm.SqliteSql = okay.serverless.jvm.SqliteSql(dir)
