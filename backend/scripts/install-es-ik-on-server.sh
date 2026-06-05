#!/usr/bin/env bash
# Run on Server 1 data layer (8.163.30.216 / 172.25.90.183) as root.
# Example: ssh root@8.163.30.216
# Installs IK analyzer for Elasticsearch 8.11.x running in Docker.

set -euo pipefail

IK_URL="${IK_URL:-https://get.infini.cloud/elasticsearch/analysis-ik/8.11.3}"

echo "==> Finding Elasticsearch container..."
ES_CONTAINER="$(docker ps --format '{{.ID}} {{.Image}} {{.Ports}}' | grep -E '9200|elasticsearch' | head -1 | awk '{print $1}')"
if [[ -z "${ES_CONTAINER}" ]]; then
  ES_CONTAINER="$(docker ps -q --filter 'ancestor=docker.elastic.co/elasticsearch/elasticsearch:8.11.3' | head -1)"
fi
if [[ -z "${ES_CONTAINER}" ]]; then
  ES_CONTAINER="$(docker ps -q --filter 'ancestor=docker.elastic.co/elasticsearch/elasticsearch:8.11.0' | head -1)"
fi
if [[ -z "${ES_CONTAINER}" ]]; then
  echo "ERROR: No Elasticsearch container found. Set ES_CONTAINER manually, e.g.:"
  echo "  export ES_CONTAINER=<container_id>"
  exit 1
fi
echo "    container: ${ES_CONTAINER}"

echo "==> Checking existing plugins..."
if docker exec "${ES_CONTAINER}" /usr/share/elasticsearch/bin/elasticsearch-plugin list | grep -q '^analysis-ik$'; then
  echo "    analysis-ik already installed."
else
  echo "==> Installing IK plugin (may take 1-2 minutes)..."
  docker exec "${ES_CONTAINER}" /usr/share/elasticsearch/bin/elasticsearch-plugin install -b "${IK_URL}"
fi

echo "==> Restarting Elasticsearch container..."
docker restart "${ES_CONTAINER}"

echo "==> Waiting for cluster to be ready..."
for i in $(seq 1 60); do
  if curl -sf -u "${ES_USER:-elastic}:${ES_PASS:-}" "http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1 \
    || curl -sf "http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; then
    echo "    ES is up."
    break
  fi
  sleep 2
  if [[ "$i" -eq 60 ]]; then
    echo "WARN: ES health check timed out; check logs: docker logs ${ES_CONTAINER}"
    exit 1
  fi
done

echo "==> Verifying IK analyzer..."
if [[ -n "${ES_USER:-}" && -n "${ES_PASS:-}" ]]; then
  AUTH=(-u "${ES_USER}:${ES_PASS}")
else
  AUTH=()
fi

curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' \
  "http://127.0.0.1:9200/_analyze" \
  -d '{"analyzer":"ik_max_word","text":"中华人民共和国"}' | head -c 400
echo ""
echo ""
echo "Done. If tokens look correct, delete ragforge_chunks and restart backend to recreate index with IK mapping:"
echo "  curl -X DELETE \"http://127.0.0.1:9200/ragforge_chunks\" ${AUTH[@]+-u \"$ES_USER:$ES_PASS\"}"
