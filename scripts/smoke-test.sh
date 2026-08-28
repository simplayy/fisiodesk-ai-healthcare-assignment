#!/usr/bin/env bash
# End-to-end check of the running stack: waits for the API, runs the target
# query and verifies the expected patients, the explain block and the
# per-professional scoping.
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
indexed=0
for _ in $(seq 1 60); do
  # "|| true": a failed poll must not abort the script under "set -e", it must retry.
  indexed=$(curl -sf "$BASE_URL/api/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["notesIndexed"])' 2>/dev/null || true)
  [ -n "$indexed" ] && [ "$indexed" -gt 0 ] 2>/dev/null && break
  sleep 2
done
if [ -z "$indexed" ] || [ "$indexed" -eq 0 ] 2>/dev/null; then
  echo "FAIL: no clinical note was indexed (check 'docker logs fisiodesk-smart-query')"
  exit 1
fi
echo "  $indexed notes indexed"

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

problems = []
if found != expected:
    problems.append(f"expected {expected}")
leaked = {"Ferrari", "Verdi", "Ricci"} & set(found)
if leaked:
    problems.append(f"negative cases leaked: {sorted(leaked)}")
if data["meta"]["tookMs"] > 2000:
    problems.append(f"over the 2 s budget: {data['meta']['tookMs']} ms")
explain = data.get("explain") or {}
if not explain.get("stages") or "excluded" not in explain:
    print("FAIL: explain block missing")
    sys.exit(1)
print(f"Explain: plan {explain['planOrigin']}, {len(explain['stages'])} stages, {len(explain['excluded'])} excluded with reasons")
if problems:
    for problem in problems:
        print("FAIL: " + problem)
    sys.exit(1)
print("OK: target query returns exactly the expected patients")
PY
)"

echo "Checking professional scoping ..."
check_scope() {
  local prof="$1" expected="$2"
  local found
  found=$(curl -sf -X POST "$BASE_URL/api/query" -H 'Content-Type: application/json' \
    -d "{\"question\":\"Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento\",\"professionistaId\":\"$prof\"}" \
    | python3 -c 'import json,sys; print(" ".join(sorted(r["cognome"] for r in json.load(sys.stdin)["results"])))')
  if [ "$found" != "$expected" ]; then
    echo "FAIL: professional $prof expected [$expected], got [$found]"; exit 1
  fi
  echo "  professional ...${prof: -4}: [$found] OK"
}
check_scope 507f1f77bcf86cd799439021 "Bianchi Romano Rossi"
check_scope 507f1f77bcf86cd799439022 ""      # has patients, but none matching this query
check_scope 507f1f77bcf86cd799439023 "Colombo"
echo "OK: each professional only sees their own patients"
