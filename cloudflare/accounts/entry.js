// the accounts' Worker (specs/accounts.md): its logic Scala (okay.js, linked by `sbt accountsWorkerJs`); here only the
// entry points and the Durable Object classes — an address's signing in, a person's shared values
import { DurableObject } from "cloudflare:workers";
import { EmailMessage } from "cloudflare:email";
import { connect } from "cloudflare:sockets";
import { worker } from "./okay.js";

globalThis.OkayEmailMessage = EmailMessage;
globalThis.OkayConnect = connect;

const objectClass = (ns) => class extends DurableObject {
  fetch(request) { return worker.objectFetch(ns, this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm(ns, this.ctx, this.env); }
};

export const SignIn = objectClass("SIGNIN");
export const Shared = objectClass("SHARED");

export default { fetch: (request, env, ctx) => worker.fetch(request, env, ctx) };
