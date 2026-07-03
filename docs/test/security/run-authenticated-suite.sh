#!/usr/bin/env bash
# RAGForge 认证态安全测试套件(IDOR / 输入校验) · V1 · 2026-07-03
# 复现 blackbox-security-assessment-V1.md 的认证态部分。只读越权验证,不写不删。
#
# 令牌通过环境变量注入(脚本不含任何真实凭据):
#   1) 触发发码:  curl -X POST -H 'Content-Type: application/json' \
#                   -d '{"phone":"<你的手机号>","scene":"login"}' https://ragforge.net/api/auth/sms/send
#   2) 登录换令牌:curl -X POST -H 'Content-Type: application/json' \
#                   -d '{"phone":"<手机号>","code":"<收到的验证码>","scene":"login"}' \
#                   https://ragforge.net/api/auth/login-mobile   # 取响应 data.accessToken
#   3) 运行:      BASE=https://ragforge.net RF_TOKEN='<accessToken>' ./run-authenticated-suite.sh
set -uo pipefail
BASE="${BASE:-https://ragforge.net}"
: "${RF_TOKEN:?请先设置 RF_TOKEN 环境变量(登录响应 data.accessToken)}"
AUTH=(-H "Authorization: Bearer $RF_TOKEN")
CURL=(curl -sS --max-time 15)
pass(){ printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
warn(){ printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
info(){ printf "  \033[36m[INFO]\033[0m %s\n" "$1"; }
h(){ printf "\n=== %s ===\n" "$1"; }

h "自有资源基线(取一个自有 KB id 作对照)"
MYKB="$("${CURL[@]}" "${AUTH[@]}" "$BASE/api/v1/kb" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);data=d.get('data',d);items=data if isinstance(data,list) else [];print(items[0]['id'] if items else '')" 2>/dev/null)"
if [[ -z "$MYKB" ]]; then warn "无法获取自有 KB,令牌可能已过期(有效期 15 分钟),请重新登录"; exit 1; fi
info "自有 KB id = $MYKB"
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/api/v1/kb/$MYKB")"
[[ "$code" == "200" ]] && pass "自有 KB 详情 200" || warn "自有 KB 详情 $code(异常)"

h "SEC-PASS-03  KB 详情 IDOR(遍历非自有 id 应 403)"
for id in 1 50 100 300 500 600 620; do
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/api/v1/kb/$id")"
  [[ "$code" == "403" || "$code" == "404" ]] && pass "kb/$id -> $code" || warn "kb/$id -> $code(疑越权!请核对该 id 是否自有)"
done

h "SEC-PASS-04  文档详情 IDOR(非自有 docId 应 403)"
for id in 1 100 1000 3000 5000 7000; do
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/api/v1/documents/$id")"
  [[ "$code" == "403" || "$code" == "404" ]] && pass "documents/$id -> $code" || warn "documents/$id -> $code(疑越权!)"
done

h "SEC-PASS-05  搜索 KB 归属过滤(非自有 kbIds 应命中 0 条)"
for fid in 600 500 1; do
  hits="$("${CURL[@]}" -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"query\":\"简历 项目 经验\",\"kbIds\":[$fid],\"topK\":3}" "$BASE/api/v1/search" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);dd=d.get('data') or {};r=dd.get('results') if isinstance(dd,dict) else [];print(len(r) if isinstance(r,list) else -1)" 2>/dev/null)"
  [[ "$hits" == "0" ]] && pass "kbIds=[$fid] -> 0 命中(已过滤)" || warn "kbIds=[$fid] -> $hits 命中(疑泄露他人库!)"
done

h "SEC-PASS-10  输入校验 & 错误处理(应 400,无堆栈)"
for payload in '{bad json' '{"query":123,"kbIds":"not-array"}' '{"query":"x","kbIds":['"$MYKB"'],"topK":999999999}'; do
  read -r code < <("${CURL[@]}" -o /tmp/_rf_err -w '%{http_code}' -X POST "${AUTH[@]}" -H 'Content-Type: application/json' -d "$payload" "$BASE/api/v1/search")
  if [[ "$code" == "400" ]] && ! grep -qiE 'exception|stacktrace|at [a-z]+\.[a-z]+\.' /tmp/_rf_err; then
    pass "畸形输入 -> 400 无堆栈: $(head -c 60 /tmp/_rf_err)"
  else warn "畸形输入 -> $code,检查是否泄露内部信息"; fi
done
rm -f /tmp/_rf_err

echo -e "\n完成。判读见 blackbox-security-assessment-V1.md。"
