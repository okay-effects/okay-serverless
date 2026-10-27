#!/usr/bin/env bash
# THE EXAMPLE ON A LOCAL WORKERD (wrangler dev), end to end — every binding the app uses, through the cloud's effects:
# KV, R2, D1, a queue and its consumer, the cache, a rate limit, vars and secrets, Analytics Engine, a service binding,
# send_email, cron; the objects: an account (a durable machine: killed and started again, its state goes on, nothing
# replayed; its alarm), a chat room (WebSockets), an order (a workflow: a sleep by alarm, an event). After
# `sbt cloudflareExample`.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules/wrangler ] || npm install --silent
[ -f .dev.vars ] || printf 'API_KEY=local-secret\n' > .dev.vars
state=$(mktemp -d)
port=8799
P="http://localhost:$port"
start() {
  npx wrangler dev --port $port --persist-to "$state" --test-scheduled --log-level info >"$state/log" 2>&1 &
  pid=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null "$P/hello" && return; sleep 0.5; done
  cat "$state/log"; exit 1
}
stop() { kill $pid 2>/dev/null; wait $pid 2>/dev/null || true; }
ev() { curl -s "$P/?account=${2:-ann}" -d "$1"; }
expect() { if [[ "$1" == *"$2"* ]]; then echo "✓ $3"; else echo "✗ $3: got $1, wanted $2"; stop; exit 1; fi; }

start
expect "$(curl -s -XPOST "$P/kv?key=a" -d one; curl -s "$P/kv?key=a")" "okone" "KV: put, get"
expect "$(curl -s -XPUT "$P/blob?key=f.txt" -H 'content-type: text/plain' --data-binary 'hello, world')" "12" \
  "R2: put"
expect "$(curl -s "$P/blob?key=f.txt&range=7-12")" "world" "R2: a range"
head -c 4096 /dev/urandom > "$state/in.bin"
curl -s -XPUT "$P/blob?key=r.bin" -H 'content-type: application/octet-stream' --data-binary @"$state/in.bin" \
  >/dev/null
curl -s "$P/blob?key=r.bin" -o "$state/out.bin"
expect "$(cmp -s "$state/in.bin" "$state/out.bin" && echo same)" "same" "R2: 4 KB of random bytes, up and back, byte for byte"
expect "$(curl -s -XPOST "$P/sql" -d first; curl -s -XPOST "$P/sql" -d second)" "1 first,second" "D1: run, query"
expect "$(curl -s "$P/queue?body=j1")" "queued" "a queue: sent"
expect "$(curl -s "$P/cached")" "made at" "the cache: made"
first=$(curl -s "$P/cached")
expect "$(curl -s "$P/cached")" "$first" "the cache: the same, cached"
expect "$(for i in 1 2 3; do curl -s "$P/limit?key=ann"; echo -n ' '; done)" "true true false" \
  "a rate limit: 2 a minute"
expect "$(curl -s "$P/env")" "hello from vars key:true" "a var, a secret"
expect "$(curl -s "$P/analytics")" "written" "Analytics Engine: a data point"
expect "$(curl -s "$P/service")" "via service: hello" "a service binding"
expect "$(curl -s "$P/mail")" "sent" "send_email"
expect "$(curl -s "$P/__scheduled?cron=*/5+*+*+*+*"; curl -s "$P/kv?key=cron")" "*/5 * * * *" \
  "cron: the app's scheduled"
sleep 2
expect "$(curl -s "$P/kv?key=job:j1")" "done after 1" "a queue: consumed, acknowledged"
expect "$(node sockets.mjs $port)" "SOCKETS OK" "an object's WebSockets: a line to both members, kept"
expect "$(curl -s -XPOST "$P/order/o-1/start" -d '{"id":"o-1"}')" '"status":"sleeping"' "a workflow: charged, asleep"
sleep 2.5
expect "$(curl -s "$P/order/o-1/")" '"status":"waiting:approved"' "a workflow: woken by its alarm, waiting"
expect "$(curl -s -XPOST "$P/order/o-1/event/approved" -d '{"by":"bob"}')" '"approvedBy":"bob"' \
  "a workflow: approved, mailed, done"

expect "$(ev '{"op":"deposit","amount":1000}')" '"balance":1000' "an account: a deposit"
expect "$(ev '{"op":"pay","amount":300}')" '"balance":700,"payments":["pay-account/2/payment"]' \
  "a payment, its idempotency key of its step"
expect "$(ev '{"op":"pay","amount":5000}')" '"refused":"5000 is more than the balance 700"' "a payment refused"
expect "$(ev '{"op":"deposit","amount":5}' bob)" '"balance":5' "another account, another object"
stop
echo "  (the Worker killed, started again on the same storage)"
start
expect "$(ev '{"op":"deposit","amount":0}')" '"balance":700,"payments":["pay-account/2/payment"]' \
  "after the restart: the state as committed, nothing replayed"
expect "$(curl -s "$P/kv?key=a")" "one" "after the restart: KV kept"
expect "$(ev '{"op":"interest-in","amount":500}')" '"balance":700' "an alarm set"
sleep 3
expect "$(ev '{"op":"deposit","amount":0}')" '"balance":707' "the alarm woke the object: 1% interest"
stop
echo "CLOUDFLARE OK"
