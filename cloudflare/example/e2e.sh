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
[ -d ../../tools/node_modules/embedded-postgres ] || (cd ../../tools && npm install --silent &&
  (cd node_modules/@embedded-postgres/*/ && node scripts/hydrate-symlinks.js))
state=$(mktemp -d)
# a Postgres for Hyperdrive, from tools/: its URL the binding's local connection string
node ../../tools/postgres.mjs >"$state/pg.url" 2>"$state/pg.log" &
pgpid=$!
trap 'kill $pgpid 2>/dev/null' EXIT
for _ in $(seq 1 120); do [ -s "$state/pg.url" ] && break; sleep 0.5; done
export CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_PG=$(cat "$state/pg.url")
export WRANGLER_HYPERDRIVE_LOCAL_CONNECTION_STRING_PG=$CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_PG
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
expect "$(cmp -s "$state/in.bin" "$state/out.bin" && echo same)" "same" \
  "R2: 4 KB of random bytes, up and back, byte for byte"
expect "$(curl -s -XPOST "$P/sql" -d first; curl -s -XPOST "$P/sql" -d second)" "1 first,second" "D1: run, query"
expect "$(curl -s -XPOST "$P/pg" -d first; echo; curl -s -XPOST "$P/pg" -d second)" \
  $'Int(1):Text(first):Int(1)\nInt(1):Text(first):Int(1),Int(2):Text(second):Int(1)' \
  "Hyperdrive: Postgres, a batch and a query"
expect "$(curl -s "$P/store/d1?item=tea&qty=3"; echo; curl -s "$P/store/d1?item=cup&qty=1")" \
  $'000000000001 of 1; big: tea\n000000000002 of 2; big: tea' "okay-store on D1: a mapped type, keys from the store"
expect "$(curl -s "$P/store/pg?item=tea&qty=3"; echo; curl -s "$P/store/pg?item=pot&qty=7")" \
  $'000000000001 of 1; big: tea\n000000000002 of 2; big: pot,tea' "okay-store on Postgres through Hyperdrive"
expect "$(curl -s "$P/store/shop?shop=kyiv&item=tea&qty=2"; echo; curl -s "$P/store/shop?shop=lviv&item=cup&qty=5")" \
  $'000000000001 of 1; big: tea\n000000000001 of 1; big: cup' "okay-store in an object: a shop its own store"
expect "$(curl -s "$P/store/registry?of=ua&number=AB1"; echo; curl -s "$P/store/registry?of=ua&number=AB2")" \
  $'2 events; KindDefined,RecordAdded\n4 events; KindDefined,KindRevised,RecordAdded' \
  "okay-store's journaled records in an object: okay-store's own handler, through the bridge"
expect "$(curl -s "$P/api/sales" | head -c 40)" '{"data":{"name":"sales","operations"' \
  "okay-faces' REST of a mapped type: its description"
expect "$(curl -s -XPOST "$P/api/sales/create" -d '{"item":"kettle","qty":4}')" '"item":"kettle","qty":4' \
  "REST: create, the key D1's"
expect "$(curl -s -XPOST "$P/api/sales/find" -d '{"where":{"type":"greater","path":["qty"],"than":3}}')" \
  '"item":"kettle"' "REST: find by a condition, on D1"
mcp() { curl -s -XPOST "$P/mcp" -H 'content-type: application/json' -d "$1"; }
expect "$(mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')" '"serverInfo":{"name":"shop"' \
  "MCP over HTTP: initialize"
expect "$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')" '"name":"sales_find"' "MCP: the type's tools"
expect "$(mcp '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sales_count","arguments":{}}}')" \
  '"structuredContent"' "MCP: a tool called, the same D1"
expect "$(curl -s -XPOST "$P/shop/odesa/api/sales/save" -d '{"id":"s1","item":"map","qty":1}'; \
  curl -s "$P/shop/odesa/api/sales/load?key=s1")" '"item":"map"' "REST of a shop's own store, in its object"
expect "$(curl -s -o /dev/null -w '%{http_code}' "$P/private/whoami")" "401" "a private route, no token: 401"
expect "$(curl -s -H 'authorization: Bearer nope' -o /dev/null -w '%{http_code}' "$P/private/whoami")" "401" \
  "a private route, a wrong token: 401"
expect "$(curl -s -H 'authorization: Bearer local-secret' -H 'okay-caller: admin' "$P/private/whoami")" "owner" \
  "a private route, the secret's token: its caller (one sent from outside dropped)"
expect "$(curl -s -o /dev/null -w '%{http_code}' "$P/private/ticks/t1?n=3")" "401" "an object's stream, no token: 401"
expect "$(curl -s -N -H 'authorization: Bearer local-secret' "$P/private/ticks/t1?n=3" | tr '\n' '|')" \
  'data: tick 1 of owner||data: tick 2 of owner||data: tick 3 of owner||' \
  "an object's event stream: its events after the answer, the caller admitted"
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
