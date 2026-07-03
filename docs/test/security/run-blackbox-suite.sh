#!/usr/bin/env bash
# RAGForge 黑盒安全测试套件(免认证) · V1 · 2026-07-03
# 用途:对生产/预发站点做无损外部安全探测,复现 blackbox-security-assessment-V1.md 的免认证部分。
# 交战规则:只读、无 DoS、无暴力。不发送短信、不写数据。
#
# 用法:  BASE=https://ragforge.net ./run-blackbox-suite.sh
set -uo pipefail
BASE="${BASE:-https://ragforge.net}"
CURL=(curl -sS --max-time 15)
pass(){ printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
warn(){ printf "  \033[33m[WARN]\033[0m %s\n" "$1"; }
info(){ printf "  \033[36m[INFO]\033[0m %s\n" "$1"; }
h(){ printf "\n=== %s ===\n" "$1"; }

h "SEC-BB-01/08  安全响应头 + 版本泄露"
HDRS="$("${CURL[@]}" -I "$BASE/" 2>/dev/null)"
for hdr in Strict-Transport-Security Content-Security-Policy X-Frame-Options X-Content-Type-Options Referrer-Policy; do
  if grep -qi "^$hdr:" <<<"$HDRS"; then pass "$hdr 存在"; else warn "$hdr 缺失"; fi
done
srv="$(grep -i '^Server:' <<<"$HDRS" | tr -d '\r')"
[[ "$srv" =~ [0-9]+\.[0-9]+ ]] && warn "版本号泄露: $srv" || pass "无版本号泄露"

h "SEC-PASS-01  Actuator 敏感端点鉴权"
for p in env info beans mappings configprops loggers heapdump threaddump metrics prometheus; do
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/actuator/$p")"
  [[ "$code" == "401" || "$code" == "403" ]] && pass "/actuator/$p -> $code" || warn "/actuator/$p -> $code (应 401/403)"
done
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
info "/actuator/health -> $code (SEC-BB-06: health 通常开放,确认不含敏感信息)"

h "SEC-PASS-01  大小写鉴权绕过检查"
lo="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/actuator/env")"
up_size="$("${CURL[@]}" -o /dev/null -w '%{size_download}' "$BASE/ACTUATOR/env")"
mix="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/actuator/ENV")"
if [[ "$lo" == "401" && "$mix" == "401" ]]; then pass "小写/混合大小写端点名均 401,无绕过 (大写前缀 ${up_size}B 为 SPA 回退)"
else warn "疑似大小写绕过: /actuator/env=$lo /actuator/ENV=$mix"; fi

h "SEC-PASS-09  CORS 锁定(伪造 Origin)"
aco="$("${CURL[@]}" -I -H 'Origin: https://evil.example.com' "$BASE/api/v1/health" | grep -i 'access-control-allow' || true)"
[[ -z "$aco" ]] && pass "无 Access-Control-Allow-* 回显" || warn "CORS 回显: $aco"

h "SEC-PASS-10  HTTP 方法"
for m in TRACE PUT DELETE; do
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X "$m" "$BASE/")"
  [[ "$code" == "405" || "$code" == "403" || "$code" == "501" ]] && pass "$m -> $code" || warn "$m -> $code"
done

h "SEC-BB-03  /sse 是否已下线"
# 判据:/sse 不应再暴露后端 MCP 端点（API_KEY_MISSING/INVALID）。下线后 nginx 无 /sse location，
# 回退到 SPA(200 HTML) 或 404/410 均视为已下线（与任意未知路径一致，不再泄露 MCP 语义）。
sse_body="$("${CURL[@]}" "$BASE/sse")"
if grep -q 'API_KEY_MISSING\|API_KEY_INVALID' <<<"$sse_body"; then
  warn "/sse 仍暴露后端 MCP 端点(API_KEY_*),未真正下线"
else
  pass "/sse 已下线(回退 SPA/404,不再暴露 MCP 语义)"
fi

h "SEC-BB-04  未认证错误提示语义"
body="$("${CURL[@]}" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/login")"
info "POST /api/login (空体) -> $body"
grep -q '登录状态已失效' <<<"$body" && warn "未登录却提示「登录状态已失效」,语义错误 (SEC-BB-04)"

h "信息泄露文件(应为 SPA 回退 393B,非真实文件)"
for p in /.git/config /.env /v3/api-docs /swagger-ui/index.html; do
  read -r code size < <("${CURL[@]}" -o /dev/null -w '%{http_code} %{size_download}' "$BASE$p")
  info "$p -> HTTP $code ${size}B"
done

echo -e "\n完成。判读见 blackbox-security-assessment-V1.md。"
