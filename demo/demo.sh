#!/usr/bin/env bash
# Seeds a demo application with five endpoints that each behave differently,
# sends a few events, and prints the console link.
#
# Receivers are public test URLs (httpbin.org, api.github.com) because the SSRF
# guard - correctly - refuses localhost. Payloads are fake order data.
#
# Start the app first, ideally with the local model on:
#   mvn spring-boot:run -Dspring-boot.run.arguments="--relay.ai.provider=ollama --relay.ai.timeout-ms=60000"
#
# Usage: demo/demo.sh [base-url]        (default http://localhost:8080)
set -euo pipefail

BASE=${1:-http://localhost:8080}
API=$BASE/api/v1/applications
JSON='Content-Type: application/json'

post() { curl -sf -X POST "$1" -H "$JSON" "${@:3}" -d "$2"; }

APP=$(post "$API" '{"name":"acme-shop (demo)"}' | jq -r .id)
echo "application  $APP"

endpoint() {   # url description
  post "$API/$APP/endpoints" "{\"url\":\"$1\",\"description\":\"$2\",\"eventTypes\":[\"order.created\"]}" \
    | jq -r '"endpoint     \(.description)  →  \(.url)"'
}
endpoint https://httpbin.org/post        "warehouse - healthy (200)"
endpoint https://httpbin.org/status/500  "billing - server broken (500)"
endpoint https://httpbin.org/status/401  "crm - wrong credentials (401, no body)"
endpoint https://api.github.com/user     "partner API - 401 with an error body (AI reads it)"
endpoint https://httpbin.org/status/429  "analytics - throttling us (429)"

echo "SSRF check   $(curl -s -X POST "$API/$APP/endpoints" -H "$JSON" \
  -d '{"url":"https://169.254.169.254/latest/meta-data"}' | jq -r .message)"

for i in 1 2 3; do
  post "$API/$APP/messages" "{\"eventType\":\"order.created\",\"payload\":{\"orderId\":$((1000 + i)),\"amount\":$((i * 499)),\"currency\":\"INR\"}}" \
    -H "Idempotency-Key: demo-order-$i" | jq -r '"message      \(.id)  fan-out: \(.deliveriesCreated) deliveries"'
done

# Same key again: must return the original message, not create a new one.
post "$API/$APP/messages" '{"eventType":"order.created","payload":{"orderId":1001,"amount":499,"currency":"INR"}}' \
  -H "Idempotency-Key: demo-order-1" | jq -r '"idempotent   same key → same id \(.id)"'

echo
echo "console      $BASE/#$APP"
