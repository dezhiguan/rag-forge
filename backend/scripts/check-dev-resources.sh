#!/usr/bin/env bash
# 探测本地 dev 中间件是否可用（不启动 docker-compose）

set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
ROCKETMQ_ADDR="${ROCKETMQ_NAMESRV_ADDR:-127.0.0.1:9876}"
ES_HOST="${ELASTICSEARCH_HOST:-8.163.63.222}"
ES_PORT="${ELASTICSEARCH_PORT:-9200}"
ES_USER="${ELASTICSEARCH_USERNAME:-elastic}"
ES_PASS="${ELASTICSEARCH_PASSWORD:-}"

echo "== PostgreSQL ${POSTGRES_HOST}:${POSTGRES_PORT} =="
if nc -z -w 2 "${POSTGRES_HOST}" "${POSTGRES_PORT}" 2>/dev/null; then
  echo "OK (port open)"
else
  echo "FAIL (port closed)"
fi

echo "== RocketMQ NameServer ${ROCKETMQ_ADDR} =="
MQ_HOST="${ROCKETMQ_ADDR%%:*}"
MQ_PORT="${ROCKETMQ_ADDR##*:}"
if nc -z -w 2 "${MQ_HOST}" "${MQ_PORT}" 2>/dev/null; then
  echo "OK (port open)"
else
  echo "FAIL — 可先执行: cd ~/rocketmq-5.5.0 && sh bin/mqnamesrv"
fi

echo "== Elasticsearch http://${ES_HOST}:${ES_PORT} =="
if [ -z "${ES_PASS}" ]; then
  echo "SKIP cluster health (set ELASTICSEARCH_PASSWORD in .env)"
  curl -s -m 5 "http://${ES_HOST}:${ES_PORT}/" | head -c 120 || echo "FAIL"
else
  curl -s -m 5 -u "${ES_USER}:${ES_PASS}" "http://${ES_HOST}:${ES_PORT}/_cluster/health" || echo "FAIL"
fi
echo
