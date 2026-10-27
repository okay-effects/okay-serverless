// the example Worker: its logic Scala (okay.js, linked by `sbt cloudflareExample`); here only what JavaScript must
// say — the entry points and the Durable Object classes, each handing its calls to the Scala worker
import { DurableObject } from "cloudflare:workers";
import { EmailMessage } from "cloudflare:email";
import pg from "pg";
import { worker } from "./okay.js";

globalThis.OkayEmailMessage = EmailMessage;
globalThis.OkayPg = pg;

const objectClass = (ns) => class extends DurableObject {
  fetch(request) { return worker.objectFetch(ns, this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm(ns, this.ctx, this.env); }
  webSocketMessage(ws, message) { return worker.objectMessage(ns, this.ctx, this.env, ws, message); }
  webSocketClose(ws) { return worker.objectClose(ns, this.ctx, this.env, ws); }
};

export const Account = objectClass("ACCOUNTS");
export const Room = objectClass("ROOMS");
export const Order = objectClass("ORDERS");

export default {
  fetch: (request, env, ctx) => worker.fetch(request, env, ctx),
  scheduled: (event, env, ctx) => worker.scheduled(event, env, ctx),
  queue: (batch, env, ctx) => worker.queue(batch, env, ctx),
  email: (message, env, ctx) => worker.email(message, env, ctx),
};
