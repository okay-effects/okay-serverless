// the example Worker: an account a Durable Object, its logic Scala (okay.js, linked by `sbt cloudflareExample`)
import { DurableObject } from "cloudflare:workers";
import { accountEvent, accountAlarm } from "./okay.js";

export class Account extends DurableObject {
  async fetch(request) {
    const answer = accountEvent(this.ctx, await request.text());
    return new Response(answer, { headers: { "content-type": "application/json" } });
  }
  async alarm() { accountAlarm(this.ctx); }
}

export default {
  async fetch(request, env) {
    const name = new URL(request.url).searchParams.get("account") || "main";
    return env.ACCOUNTS.get(env.ACCOUNTS.idFromName(name)).fetch(request);
  },
};
