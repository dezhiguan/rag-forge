#!/usr/bin/env bash
# End-to-end judge smoke: POST /api/v1/answer (forceSample) -> MQ -> judge -> judge_results.
#
# Prerequisites:
#   - backend running with RAGFORGE_ROLE=all, profile dev
#   - RAGFORGE_JUDGE_DISPATCH_MODE=inline (local without RocketMQ broker) or mq + topic
#   - PostgreSQL up; DEEPSEEK_API_KEY in backend/.env
#   - At least one enabled api_keys row with allowed_kb_ids covering target KB
#
# Usage:
#   ./scripts/run-one-judge-smoke.sh
#   SMOKE_KB_ID=100 SMOKE_API_KEY=sk-... ./scripts/run-one-judge-smoke.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT}/backend/.env"
load_env_var() {
  local key=$1
  if [[ -f "$ENV_FILE" ]] && grep -q "^${key}=" "$ENV_FILE"; then
    export "$key=$(grep "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"')"
  fi
}
for v in POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD DEEPSEEK_API_KEY; do
  load_env_var "$v"
done

BASE_URL="${RAGFORGE_BASE_URL:-http://localhost:8080}"
SMOKE_QUERY="${SMOKE_QUERY:-Spring Boot 默认端口是多少？}"
PGHOST="${POSTGRES_HOST:-127.0.0.1}"
PGPORT="${POSTGRES_PORT:-5432}"
PGUSER="${POSTGRES_USER:-amy}"
PGDATABASE="${POSTGRES_DB:-ragforge}"
export PGPASSWORD="${POSTGRES_PASSWORD:-amy}"

if command -v psql >/dev/null 2>&1; then
  PSQL="$(command -v psql)"
elif [[ -x /Applications/Postgres.app/Contents/Versions/18/bin/psql ]]; then
  PSQL="/Applications/Postgres.app/Contents/Versions/18/bin/psql"
else
  echo "ERROR: psql not found; install Postgres client or set PSQL=/path/to/psql"
  exit 1
fi

psql_q() {
  "$PSQL" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1"
}

echo "==> health check $BASE_URL"
curl -sf "$BASE_URL/actuator/health" >/dev/null || {
  echo "ERROR: backend not reachable. Start with:"
  echo "  cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
  exit 1
}

if [[ -z "${SMOKE_API_KEY:-}" ]]; then
  SMOKE_API_KEY="$(psql_q "SELECT api_key FROM api_keys WHERE enabled=true ORDER BY id LIMIT 1" || true)"
fi
if [[ -z "${SMOKE_API_KEY:-}" ]]; then
  echo "ERROR: set SMOKE_API_KEY or seed api_keys table"
  exit 1
fi

if [[ -z "${SMOKE_KB_ID:-}" ]]; then
  SMOKE_KB_ID="$(psql_q "SELECT kb_id FROM document_chunks GROUP BY kb_id ORDER BY count(*) DESC LIMIT 1")"
fi
if [[ -z "${SMOKE_KB_ID:-}" ]]; then
  echo "ERROR: no KB with answer_mode=ON; set SMOKE_KB_ID"
  exit 1
fi

# Ensure smoke API key can read target KB (dev DB often has empty allowed_kb_ids).
PREV_KB_IDS="$(psql_q "SELECT allowed_kb_ids::text FROM api_keys WHERE api_key='${SMOKE_API_KEY}'")"
psql_q "UPDATE api_keys SET allowed_kb_ids='[$SMOKE_KB_ID]'::jsonb WHERE api_key='${SMOKE_API_KEY}'"
restore_api_key() {
  if [[ -n "$PREV_KB_IDS" ]]; then
    psql_q "UPDATE api_keys SET allowed_kb_ids='${PREV_KB_IDS}'::jsonb WHERE api_key='${SMOKE_API_KEY}'" >/dev/null || true
  fi
}
trap restore_api_key EXIT

BEFORE_LOG_ID="$(psql_q "SELECT COALESCE(MAX(id),0) FROM answer_logs")"
echo "==> POST /api/v1/answer kb=$SMOKE_KB_ID forceSample=true"
curl -sN -X POST "$BASE_URL/api/v1/answer" \
  -H "X-API-Key: $SMOKE_API_KEY" \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d "{\"kbIds\":[$SMOKE_KB_ID],\"query\":\"$SMOKE_QUERY\",\"forceSample\":true,\"stream\":true,\"topK\":3}" \
  --max-time 180 >/tmp/ragforge-judge-smoke-sse.txt || true

ANSWER_LOG_ID="$(psql_q "SELECT id FROM answer_logs WHERE id > $BEFORE_LOG_ID ORDER BY id DESC LIMIT 1")"
if [[ -z "$ANSWER_LOG_ID" ]]; then
  echo "ERROR: no new answer_logs row; check curl auth/KB access"
  tail -20 /tmp/ragforge-judge-smoke-sse.txt || true
  exit 1
fi
echo "answer_log_id=$ANSWER_LOG_ID"

STATUS=""
for _ in $(seq 1 150); do
  STATUS="$(psql_q "SELECT status FROM judge_results WHERE answer_log_id=$ANSWER_LOG_ID ORDER BY id DESC LIMIT 1" || true)"
  if [[ -n "$STATUS" && "$STATUS" != "RUNNING" ]]; then
    break
  fi
  sleep 2
done

JUDGE_ID="$(psql_q "SELECT id FROM judge_results WHERE answer_log_id=$ANSWER_LOG_ID ORDER BY id DESC LIMIT 1" || true)"
if [[ -z "$JUDGE_ID" ]]; then
  echo "ERROR: no judge_results for answer_log_id=$ANSWER_LOG_ID (check MQ topic or RAGFORGE_JUDGE_DISPATCH_MODE=inline)"
  exit 1
fi
RAW="$(psql_q "SELECT judge_raw_response::text FROM judge_results WHERE id=$JUDGE_ID")"
MODEL="$(psql_q "SELECT judge_model FROM judge_results WHERE id=$JUDGE_ID")"
LATENCY="$(psql_q "SELECT judge_latency_ms FROM judge_results WHERE id=$JUDGE_ID")"
COST="$(psql_q "SELECT judge_cost_cny FROM judge_results WHERE id=$JUDGE_ID")"

echo ""
echo "=== judge smoke result ==="
echo "judge_result_id=$JUDGE_ID status=$STATUS model=$MODEL latency_ms=$LATENCY cost_cny=$COST"
echo "judge_raw_response (truncated): ${RAW:0:400}..."

FAIL=0
if [[ "$STATUS" != "COMPLETED" ]]; then
  echo "FAIL: expected status=COMPLETED, got $STATUS"
  FAIL=1
fi
if echo "$RAW" | grep -q 'reasoning_content'; then
  echo "FAIL: judge_raw_response contains reasoning_content (thinking chain leak)"
  FAIL=1
fi
if echo "$RAW" | grep -q '"thinking"'; then
  echo "FAIL: judge_raw_response contains thinking field"
  FAIL=1
fi

python3 - "$RAW" <<'PY'
import json, sys
raw = sys.argv[1]
data = json.loads(raw)
failed = False
for dim, entry in data.items():
    p = entry.get("prompt_tokens") or 0
    c = entry.get("completion_tokens") or 0
    total = p + c
    print(f"  {dim}: prompt_tokens={p} completion_tokens={c} total={total}")
    if total >= 800:
        print(f"FAIL: {dim} tokens {total} >= 800 (thinking likely enabled)")
        failed = True
sys.exit(1 if failed else 0)
PY
TOKEN_RC=$?
if [[ "$TOKEN_RC" -ne 0 ]]; then
  FAIL=1
fi

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi

echo ""
echo "PASS: thinking disabled, no reasoning leak, all dimensions < 1000 tokens"
echo ""
echo "Verification curl (paste into PR):"
echo "curl -sN -X POST $BASE_URL/api/v1/answer \\"
echo "  -H 'X-API-Key: \$SMOKE_API_KEY' -H 'Accept: text/event-stream' \\"
echo "  -H 'Content-Type: application/json' \\"
echo "  -d '{\"kbIds\":[$SMOKE_KB_ID],\"query\":\"什么是 RAG？\",\"forceSample\":true}'"
