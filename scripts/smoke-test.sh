#!/usr/bin/env bash
# End-to-end check of the running stack: waits for the API, runs the target
# query and verifies the expected patients are returned.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Waiting for $BASE_URL ..."
for _ in $(seq 1 60); do
  if curl -sf "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf "$BASE_URL/actuator/health" > /dev/null || { echo "API not reachable"; exit 1; }

echo "Waiting for note indexing ..."
for _ in $(seq 1 60); do
  indexed=$(curl -sf "$BASE_URL/api/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["notesIndexed"])')
  [ "$indexed" -gt 0 ] && break
  sleep 2
done

echo "Running target query ..."
response=$(curl -sf -X POST "$BASE_URL/api/query" \
  -H 'Content-Type: application/json' \
  -d '{"question":"Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'\''ultimo appuntamento"}')

echo "$response" | python3 -c "$(cat <<'PY'
import json, sys

data = json.load(sys.stdin)
found = sorted(r["cognome"] for r in data["results"])
expected = sorted(["Rossi", "Bianchi", "Romano", "Colombo"])

print(f"Patients returned: {found}")
print(f"Latency: {data['meta']['tookMs']} ms (cached: {data['meta']['cached']})")

if found != expected:
    print(f"FAIL: expected {expected}")
    sys.exit(1)
excluded = {"Ferrari", "Verdi", "Ricci"}
if excluded & set(found):
    print(f"FAIL: negative cases leaked: {excluded & set(found)}")
    sys.exit(1)
print("OK: target query returns exactly the expected patients")
PY
)"
