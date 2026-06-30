#!/usr/bin/env bash
# Run on Server 1 data layer (8.163.30.216 / 172.25.90.183) as root.
# Example: ssh root@8.163.30.216
# Installs IK analyzer for the Elasticsearch container and verifies the bundled dictionaries.

set -euo pipefail

ES_HTTP="${ES_HTTP:-http://127.0.0.1:9200}"
TMP_DIR="${TMP_DIR:-/tmp/ragforge-analysis-ik}"
if [[ -n "${ES_USER:-}" && -n "${ES_PASS:-}" ]]; then
  AUTH=(-u "${ES_USER}:${ES_PASS}")
else
  AUTH=()
fi

echo "==> Finding Elasticsearch container..."
ES_CONTAINER="${ES_CONTAINER:-}"
if [[ -z "${ES_CONTAINER}" ]]; then
  ES_CONTAINER="$(docker ps --format '{{.ID}} {{.Image}} {{.Ports}}' | grep -E '9200|elasticsearch' | head -1 | awk '{print $1}')"
fi
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

ES_VERSION="$(
  docker exec "${ES_CONTAINER}" sh -lc '/usr/share/elasticsearch/bin/elasticsearch --version' \
    | sed -n 's/^Version: \([^,]*\),.*/\1/p'
)"
if [[ -z "${ES_VERSION}" ]]; then
  echo "ERROR: Could not detect Elasticsearch version from container ${ES_CONTAINER}"
  exit 1
fi
echo "    Elasticsearch version: ${ES_VERSION}"

IK_URL="${IK_URL:-https://get.infini.cloud/elasticsearch/analysis-ik/${ES_VERSION}}"
echo "    IK package URL: ${IK_URL}"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo "==> Checking existing plugins..."
if docker exec "${ES_CONTAINER}" /usr/share/elasticsearch/bin/elasticsearch-plugin list | grep -q '^analysis-ik$'; then
  echo "    analysis-ik already installed."
else
  echo "==> Installing IK plugin (may take 1-2 minutes)..."
  docker exec "${ES_CONTAINER}" /usr/share/elasticsearch/bin/elasticsearch-plugin install -b "${IK_URL}"
fi

echo "==> Ensuring IK config and dictionaries are present..."
rm -rf "${TMP_DIR}"
mkdir -p "${TMP_DIR}"
curl -fL --max-time 120 -o "${TMP_DIR}/analysis-ik.zip" "${IK_URL}"
python3 - "${TMP_DIR}/analysis-ik.zip" "${TMP_DIR}/unpacked" <<'PY'
import pathlib
import sys
import zipfile

zip_path = pathlib.Path(sys.argv[1])
out_dir = pathlib.Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(zip_path) as package:
    package.extractall(out_dir)
PY

IK_CONFIG_DIR="${TMP_DIR}/unpacked/config"
if [[ ! -f "${IK_CONFIG_DIR}/IKAnalyzer.cfg.xml" || ! -f "${IK_CONFIG_DIR}/main.dic" || ! -f "${IK_CONFIG_DIR}/stopword.dic" || ! -f "${IK_CONFIG_DIR}/surname.dic" ]]; then
  echo "ERROR: IK package did not contain the expected config dictionaries."
  find "${TMP_DIR}/unpacked" -maxdepth 3 -type f | sort
  exit 1
fi

docker exec "${ES_CONTAINER}" sh -lc \
  'mkdir -p /usr/share/elasticsearch/plugins/analysis-ik/config /usr/share/elasticsearch/config/analysis-ik'
docker cp "${IK_CONFIG_DIR}/." "${ES_CONTAINER}:/usr/share/elasticsearch/plugins/analysis-ik/config/"
docker cp "${IK_CONFIG_DIR}/." "${ES_CONTAINER}:/usr/share/elasticsearch/config/analysis-ik/"

echo "==> Verifying IK config files in container..."
docker exec "${ES_CONTAINER}" sh -lc '
  test -f /usr/share/elasticsearch/plugins/analysis-ik/config/IKAnalyzer.cfg.xml
  test -f /usr/share/elasticsearch/plugins/analysis-ik/config/main.dic
  test -f /usr/share/elasticsearch/plugins/analysis-ik/config/stopword.dic
  test -f /usr/share/elasticsearch/plugins/analysis-ik/config/surname.dic
'

echo "==> Restarting Elasticsearch container..."
docker restart "${ES_CONTAINER}"

echo "==> Waiting for cluster to be ready..."
for i in $(seq 1 60); do
  if curl -sf "${AUTH[@]}" "${ES_HTTP}/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; then
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
ik_max_word_result="$(
  curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' \
    "${ES_HTTP}/_analyze" \
    -d '{"analyzer":"ik_max_word","text":"中华人民共和国中文测试"}'
)"
ik_smart_result="$(
  curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' \
    "${ES_HTTP}/_analyze" \
    -d '{"analyzer":"ik_smart","text":"中华人民共和国中文测试"}'
)"

if ! grep -q '"tokens"' <<<"${ik_max_word_result}" || ! grep -q '"tokens"' <<<"${ik_smart_result}"; then
  echo "ERROR: IK analyzer verification failed."
  echo "ik_max_word: ${ik_max_word_result}"
  echo "ik_smart: ${ik_smart_result}"
  exit 1
fi

echo "${ik_max_word_result}" | head -c 400
echo ""
echo "${ik_smart_result}" | head -c 400
echo ""
echo ""
echo "Done. IK analyzer is installed with config dictionaries and verified."
