# 安全评估 · docs/test/security

生产环境黑盒 + 认证态安全评估的**结果报告**与**可复现测试套件**。

| 文件 | 说明 |
| --- | --- |
| [`blackbox-security-assessment-V1.md`](blackbox-security-assessment-V1.md) | 评估报告 V1(2026-07-03):8 项 gap + 11 项已验证防护,含证据与修复建议 |
| [`run-blackbox-suite.sh`](run-blackbox-suite.sh) | 免认证黑盒套件:安全头、actuator 鉴权、大小写绕过、CORS、方法、`/sse`、错误语义 |
| [`run-authenticated-suite.sh`](run-authenticated-suite.sh) | 认证态套件:KB/文档 IDOR、搜索归属过滤、输入校验(需 `RF_TOKEN`) |
| [`jwt-tamper-test.py`](jwt-tamper-test.py) | JWT 篡改套件:`alg:none`、篡改载荷、提权、截断签名(需 `RF_TOKEN`) |

## 运行

```bash
# 1. 免认证(安全,不发短信不写数据)
BASE=https://ragforge.net ./run-blackbox-suite.sh

# 2. 获取令牌(触发一次发码 → 用收到的验证码换 accessToken)
curl -X POST -H 'Content-Type: application/json' \
     -d '{"phone":"<手机号>","scene":"login"}' https://ragforge.net/api/auth/sms/send
curl -X POST -H 'Content-Type: application/json' \
     -d '{"phone":"<手机号>","code":"<验证码>","scene":"login"}' \
     https://ragforge.net/api/auth/login-mobile          # 取 data.accessToken

# 3. 认证态(只读越权验证,不写不删;令牌 15 分钟有效)
BASE=https://ragforge.net RF_TOKEN='<accessToken>' ./run-authenticated-suite.sh
BASE=https://ragforge.net RF_TOKEN='<accessToken>' python3 jwt-tamper-test.py
```

## 约定与红线

- **凭据不入库**:所有脚本通过环境变量注入令牌,仓库内不含任何真实令牌 / 验证码 / 手机号 / 邮箱。
- **无损**:套件仅做只读探测与越权验证,不含 DoS / 暴力 / 写删;发码仅由操作者手动触发一次。
- 令牌短时效(15 分钟),认证态套件若报"无法获取自有 KB"通常是令牌过期,重新登录即可。
