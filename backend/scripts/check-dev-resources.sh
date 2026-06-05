#!/usr/bin/env bash
# 探测本地 dev 中间件是否可用（不启动 docker-compose）

set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
ROCKETMQ_ADDR="${ROCKETMQ_NAMESRV_ADDR:-127.0.0.1:9876}"
# 本地默认 127.0.0.1；探测 Server 1 数据层时：ELASTICSEARCH_HOST=172.25.90.183
ES_HOST="${ELASTICSEARCH_HOST:-127.0.0.1}"
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
  echo "OK (NameServer port open)"
else
  echo "FAIL — 可先执行: cd ~/rocketmq-5.5.0 && sh bin/mqnamesrv"
fi

echo "== RocketMQ Broker 127.0.0.1:10911 =="
if nc -z -w 2 127.0.0.1 10911 2>/dev/null; then
  echo "OK (Broker port open)"
else
  echo "FAIL — NameServer 在但 Broker 未启动会导致 No route info of this topic"
  echo "       可先执行: cd ~/rocketmq-5.5.0 && sh bin/mqbroker -n 127.0.0.1:9876 -c conf/broker.conf autoCreateTopicEnable=true"
fi

echo "== Elasticsearch http://${ES_HOST}:${ES_PORT} =="
if [ -z "${ES_PASS}" ]; then
  echo "SKIP cluster health (set ELASTICSEARCH_PASSWORD in .env)"
  curl -s -m 5 "http://${ES_HOST}:${ES_PORT}/" | head -c 120 || echo "FAIL"
else
  curl -s -m 5 -u "${ES_USER}:${ES_PASS}" "http://${ES_HOST}:${ES_PORT}/_cluster/health" || echo "FAIL"
fi
echo
