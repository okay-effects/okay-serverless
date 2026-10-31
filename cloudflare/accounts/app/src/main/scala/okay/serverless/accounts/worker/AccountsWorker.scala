package okay.serverless.accounts.worker

import okay.serverless.accounts.Accounts
import okay.serverless.cloudflare.Worker

import scala.scalajs.js.annotation.JSExportTopLevel

/** THE ACCOUNTS' WORKER (specs/accounts.md): the app and its objects on Cloudflare — never published, deployed */
object AccountsWorker:
  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Accounts(), Accounts.actors)
