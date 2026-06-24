# OSS CORS 配置说明

前端直传 OSS 使用浏览器 `PUT` 到后端返回的 `presignedPutUrl`。如果 Bucket 没有配置 CORS，浏览器会在预检请求或 PUT 阶段拦截，表现为前端上传失败但后端没有收到 `/documents/register`。

## Bucket

- Bucket: `rag-raw-docs`
- 控制台路径: 阿里云 OSS 控制台 -> Bucket 列表 -> `rag-raw-docs` -> 数据安全 -> 跨域设置

## CORS 规则

新增一条规则：

- 来源 `AllowedOrigin`
  - `https://ragforge.net`
  - `http://localhost:5173`
- 允许 Methods: `GET`, `PUT`, `POST`, `HEAD`
- 允许 Headers: `*`
- 暴露 Headers: `ETag`, `x-oss-request-id`
- 缓存时间 `MaxAgeSeconds`: `600`

## 操作步骤

1. 进入阿里云 OSS 控制台，打开 Bucket `rag-raw-docs`。
2. 在左侧进入“数据安全” -> “跨域设置”。
3. 点击“创建规则”，按上面的 CORS 规则填写。
4. 保存后等待规则生效，再从 `https://ragforge.net` 或本地 `http://localhost:5173` 上传测试文件。

截图建议保存到 `docs/upload-presign-acceptance/oss-cors-rule.png`，用于 PR 验收说明。

## 诊断方式

未配置 CORS 或配置不完整时，DevTools Network 通常会出现以下现象：

- `OPTIONS https://rag-raw-docs.oss-cn-guangzhou.aliyuncs.com/...` 返回 403 或没有 `Access-Control-Allow-Origin`。
- `PUT https://rag-raw-docs.oss-cn-guangzhou.aliyuncs.com/...` 在浏览器控制台显示 CORS error。
- 前端只完成 `POST /uploads/presign`，不会继续成功调用 `POST /documents/register`。

如果 OSS 返回 `SignatureDoesNotMatch`，优先检查前端 PUT 的 `Content-Type` 是否和 `/uploads/presign` 请求里的 `contentType` 完全一致。当前后端会把 `Content-Type` 写入 presign 签名，前端必须原样照传。
