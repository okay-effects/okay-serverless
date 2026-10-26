#!/usr/bin/env bash
# the AWS module's tests on DynamoDB Local (a JAR from AWS, kept in ~/.cache/dynamodb-local; Java needed)
set -euo pipefail
cd "$(dirname "$0")/.."
dir=~/.cache/dynamodb-local
if [ ! -f "$dir/DynamoDBLocal.jar" ]; then
  mkdir -p "$dir"
  curl -sL -o "$dir/d.zip" https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip
  (cd "$dir" && unzip -q -o d.zip && rm d.zip)
fi
[ -d aws/node_modules/@aws-sdk/client-dynamodb ] || (cd aws && npm install --silent)
port=$(python3 -c "import socket;s=socket.socket();s.bind(('',0));print(s.getsockname()[1])")
(cd "$dir" && java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -inMemory -port "$port" \
  >/tmp/okay-dynamodb.log 2>&1) &
pid=$!
trap 'kill $pid 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://localhost:$port" && break; sleep 0.25; done
OKAY_DYNAMODB="http://localhost:$port" sbt -batch aws/test
