#!/usr/bin/env bash
# Simulated "simple broker". demonstrates M2M Auth0 token flow against supplier-ti.
# Requires: curl, jq
#
# Auth0 setup needed before use:
#   1. Create an Auth0 API:  name="GerbitPCB Suppliers", identifier="https://gerbitpcb-supplier"
#   2. Create an Auth0 M2M App: name="GerbitPCB Broker", authorize it for the Suppliers API
#   3. Fill in the four variables below

AUTH0_DOMAIN="gerbitpcb.eu.auth0.com"      # e.g. dev-abc123.eu.auth0.com
CLIENT_ID="Vs1cFMqtIng9zu6MItjCX4HCvYO7NwTo"
CLIENT_SECRET="cnIZy4iCSyLMMNrq9CzfUkFfBJOd9-bw0by8yQYfGxFvIvlfRHywenD-8Nwu18IM"
AUDIENCE="https://gerbitpcb-supplier"

SUPPLIER_URL="http://74.248.131.180:8081"
# SUPPLIER_URL="http://localhost:8081"
SKU="LM324N"
QUANTITY=1

set -e

# ── 1. Obtain client-credentials token ──────────────────────────────────────
echo ">>> [1] Requesting Auth0 token"
TOKEN_RESPONSE=$(curl -s --request POST \
  --url "https://${AUTH0_DOMAIN}/oauth/token" \
  --header "content-type: application/json" \
  --data "{
    \"client_id\": \"${CLIENT_ID}\",
    \"client_secret\": \"${CLIENT_SECRET}\",
    \"audience\": \"${AUDIENCE}\",
    \"grant_type\": \"client_credentials\"
  }")

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token')

if [ "$ACCESS_TOKEN" = "null" ] || [ -z "$ACCESS_TOKEN" ]; then
  echo "ERROR: Failed to obtain token. Auth0 response:"
  echo "$TOKEN_RESPONSE" | jq .
  exit 1
fi

echo "    Token obtained (${#ACCESS_TOKEN} chars)"

# ── 2. List available components ─────────────────────────────────────────────
echo ""
echo ">>> [2] GET /api/components"
curl -s --request GET \
  --url "${SUPPLIER_URL}/api/components" \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  | jq .

# ── 3. Phase 1 — Reserve ─────────────────────────────────────────────────────
echo ""
echo ">>> [3] POST /api/transaction/reserve  (sku=${SKU}, qty=${QUANTITY})"
RESERVE_RESPONSE=$(curl -s --request POST \
  --url "${SUPPLIER_URL}/api/transaction/reserve" \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data "{\"sku\": \"${SKU}\", \"quantity\": ${QUANTITY}}")

echo "$RESERVE_RESPONSE" | jq .
RESERVATION_ID=$(echo "$RESERVE_RESPONSE" | jq -r '.reservationId')

if [ "$RESERVATION_ID" = "null" ] || [ -z "$RESERVATION_ID" ]; then
  echo "ERROR: Reserve failed — check SKU exists and stock is available."
  exit 1
fi

echo "    reservationId: ${RESERVATION_ID}"

# ── 4. Phase 2 — Commit ──────────────────────────────────────────────────────
echo ""
echo ">>> [4] POST /api/transaction/commit"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --request POST \
  --url "${SUPPLIER_URL}/api/transaction/commit" \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data "{\"reservationId\": \"${RESERVATION_ID}\"}")

echo "    HTTP status: ${HTTP_STATUS}"

if [ "$HTTP_STATUS" = "204" ]; then
  echo ""
  echo "SUCCESS: 2PC transaction committed."
else
  echo ""
  echo "WARN: Unexpected status ${HTTP_STATUS} on commit: rolling back."
  curl -s --request POST \
    --url "${SUPPLIER_URL}/api/transaction/rollback" \
    --header "Authorization: Bearer ${ACCESS_TOKEN}" \
    --header "Content-Type: application/json" \
    --data "{\"reservationId\": \"${RESERVATION_ID}\"}"
  echo "    Rolled back."
fi
