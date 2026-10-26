#!/usr/bin/env bash
# THE EXAMPLE ON A LOCAL WORKERD (wrangler dev), end to end: an account's events; the process killed and started again
# on the same storage — the state goes on, nothing replayed; the alarm's interest. After `sbt cloudflareExample`.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules/wrangler ] || npm install --silent
state=$(mktemp -d)
port=8799
start() {
  npx wrangler dev --port $port --persist-to "$state" --log-level error >"$state/log" 2>&1 &
  pid=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null "http://localhost:$port/" -d '{}' && return; sleep 0.5; done
  cat "$state/log"; exit 1
}
stop() { kill $pid 2>/dev/null; wait $pid 2>/dev/null || true; }
ev() { curl -s "http://localhost:$port/?account=${2:-ann}" -d "$1"; }
expect() { if [[ "$1" == *"$2"* ]]; then echo "✓ $3"; else echo "✗ $3: got $1, wanted $2"; stop; exit 1; fi; }

start
expect "$(ev '{"op":"deposit","amount":1000}')" '"balance":1000' "a deposit"
expect "$(ev '{"op":"pay","amount":300}')" '"balance":700,"payments":["pay-account/2/payment"]' \
  "a payment, its idempotency key of its step"
expect "$(ev '{"op":"pay","amount":5000}')" '"refused":"5000 is more than the balance 700"' "a payment refused"
expect "$(ev '{"op":"deposit","amount":5}' bob)" '"balance":5' "another account, another object"
stop
echo "  (the Worker killed, started again on the same storage)"
start
expect "$(ev '{"op":"deposit","amount":0}')" '"balance":700,"payments":["pay-account/2/payment"]' \
  "after the restart: the state as committed, nothing replayed"
expect "$(ev '{"op":"interest-in","amount":500}')" '"balance":700' "an alarm set"
sleep 3
expect "$(ev '{"op":"deposit","amount":0}')" '"balance":707' "the alarm woke the object: 1% interest"
stop
echo "CLOUDFLARE OK"
