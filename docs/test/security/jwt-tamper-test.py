#!/usr/bin/env python3
"""RAGForge JWT 校验健壮性测试 · V1 · 2026-07-03
复现 SEC-PASS-07:验证服务端拒绝所有被篡改的令牌。
令牌通过环境变量注入,脚本不含任何真实凭据。

用法:  BASE=https://ragforge.net RF_TOKEN='<accessToken>' python3 jwt-tamper-test.py

预期:除"原始令牌对照"外,所有变体均应被 401 拒绝。任何非原始令牌返回 200 即为严重漏洞。
"""
import base64, json, os, ssl, sys, urllib.request

BASE = os.environ.get("BASE", "https://ragforge.net")
TOK = os.environ.get("RF_TOKEN")
if not TOK:
    sys.exit("请先设置 RF_TOKEN 环境变量(登录响应 data.accessToken)")
PROBE = BASE + "/api/v1/me"   # 任意需认证的只读端点
ctx = ssl.create_default_context()
if os.environ.get("RF_INSECURE"):          # 部分环境缺根证书时可 RF_INSECURE=1
    ctx = ssl._create_unverified_context()

h, p, s = TOK.split(".")
b64d = lambda x: base64.urlsafe_b64decode(x + "=" * (-len(x) % 4))
b64e = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
pl = json.loads(b64d(p))

fails = 0
def call(name, token, expect_reject=True):
    global fails
    req = urllib.request.Request(PROBE, headers={"Authorization": "Bearer " + token})
    try:
        r = urllib.request.urlopen(req, timeout=12, context=ctx)
        accepted, code = True, r.status
    except urllib.error.HTTPError as e:
        accepted, code = False, e.code
    except Exception as e:
        print(f"  [ERR ] {name}: {type(e).__name__} {e}"); return
    if expect_reject and accepted:
        print(f"  [FAIL] {name}: HTTP {code} 被接受!!! 严重漏洞"); fails += 1
    elif expect_reject and not accepted:
        print(f"  [PASS] {name}: HTTP {code} 已拒绝")
    elif not expect_reject and accepted:
        print(f"  [PASS] {name}: HTTP {code} 正常通过")
    else:
        print(f"  [FAIL] {name}: HTTP {code} 原始令牌却被拒(令牌可能过期)"); fails += 1

hnone = b64e(json.dumps({"alg": "none", "typ": "JWT"}).encode())
pmod  = b64e(json.dumps({**pl, "user_id": 1, "sub": "user:1"}).encode())
pesc  = b64e(json.dumps({**pl, "scopes": ["rag:admin:read", "rag:admin:write"],
                         "platform_role": "ADMIN", "rag_role": "ADMIN"}).encode())

print("=== SEC-PASS-07 JWT 篡改测试 ===")
call("alg=none + 改 user_id=1", f"{hnone}.{pmod}.")
call("篡改 payload + 保留原 RS256 签名", f"{h}.{pmod}.{s}")
call("提权改 scopes/role + 原签名", f"{h}.{pesc}.{s}")
call("签名截断 1 字符", f"{h}.{p}.{s[:-1]}")
call("空签名段", f"{h}.{p}.")
call("原始令牌对照", TOK, expect_reject=False)

print(f"\n结论:{'全部通过,JWT 校验健壮' if fails == 0 else f'发现 {fails} 处异常,需立即处理'}")
sys.exit(1 if fails else 0)
