# RAGForge 部署架构(k3s · 权威)

> 本文件是 RAGForge **当前生产部署的权威说明**。`docs/deploy/` 下的 `deployment-three-tier.md` / `deployment-migration-runbook.md` / `deployment-app-cluster.md` 描述的是早期 **docker-compose 三层**形态,已属历史口径,**以本文件为准**。
>
> 说明:本文出于安全考虑只描述拓扑(角色 / 端口 / 命名空间),不含真实服务器 IP;真实地址、凭据见内网运维笔记与集群 Secret。

---

## 总览

物理上仍是**三层分离**,但**应用层已从 docker-compose 迁移到 k3s 单节点集群**;数据层与入口层仍为宿主机进程。

```text
                    公网域名 ragforge.net (443/80)
                              │
                              ▼
        ┌───────────────────────────────────────────┐
        │  入口层(独立节点) · 宿主机 Nginx           │
        │   /        → 前端静态文件                    │
        │   /api/    → upstream → 应用层 NodePort 31090│
        └───────────────────────┬───────────────────┘
                                 ▼
        ┌───────────────────────────────────────────┐
        │  应用层(k3s 单节点 · v1.35.x)             │
        │  namespace: ragforge / auth-gateway /        │
        │             careermate                       │
        └───────────────────────┬───────────────────┘
                                 ▼
        ┌───────────────────────────────────────────┐
        │  数据层(独立节点 · 裸装,未进 k8s)        │
        │  PostgreSQL+pgvector / Elasticsearch /       │
        │  Redis / RocketMQ                            │
        └───────────────────────────────────────────┘
```

## 应用层:k3s

- 运行时:**k3s 单节点**(control-plane 同时承载 workload),内置 traefik 已禁用,**集群内无 Ingress 对象**,对外统一靠入口层 Nginx + NodePort。
- 三个命名空间:`ragforge`、`auth-gateway`、`careermate`,共节点共存。

### `ragforge` 命名空间

**关键设计:`api` / `worker` / `judge` 共用同一个 backend 镜像,通过环境变量 `RAGFORGE_ROLE` 切换职责。** 镜像 tag 形如 `backend-<gitsha>` / `frontend-<gitsha>`,可据此反推线上代码版本。

| Deployment | RAGFORGE_ROLE | 副本 | 入站 Service | 职责 |
| --- | --- | --- | --- | --- |
| `ragforge-api` | api | 3 | NodePort `8080:31090` | REST / Search / Answer / 管理 API(`SPRING_MAIN_WEB_APPLICATION_TYPE=servlet`) |
| `ragforge-frontend` | — | 2 | NodePort `80:31002` | 前端(集群内副本) |
| `ragforge-worker` | worker | 1 | 无 | RocketMQ 文档处理 consumer(`RAGFORGE_DOCUMENT_PROCESS_DISPATCH_MODE=mq`) |
| `ragforge-judge` | judge | 1 | 无 | LLM-as-Judge 评测后台 |

- 配置:非敏感项在 ConfigMap;敏感项在 Secret `ragforge-backend-env`(DB/ES/Redis/MQ 凭据、DashScope/DeepSeek Key、OSS AK/SK、RSA 私钥等)。
- 与 Auth Gateway 集成走**集群内 DNS**(非 NodePort):
  - `RAGFORGE_AUTH_JWKS_URL = http://auth-gateway.auth-gateway.svc.cluster.local:8090/.well-known/jwks.json`
  - `RAGFORGE_AUTH_PROXY_BASE_URL = http://auth-gateway.auth-gateway.svc.cluster.local:8090`

### 同集群其他命名空间(集成关系)

| ns | Deployment | 副本 | NodePort |
| --- | --- | --- | --- |
| `auth-gateway` | auth-gateway(IdP) | 2 | `8090:31091` |
| `careermate` | careermate-backend | 3 | `18080:31080` |
| `careermate` | careermate-frontend | 1 | `80:31000` |

## 入口层:宿主机 Nginx

- 独立节点,docker-compose 跑宿主机 Nginx(`docker-compose-ingress.yml`)。
- `/` 直出前端静态文件;`/api/` 反代到应用层 backend NodePort `31090`。
- 参考配置:`deploy/nginx/ragforge-k8s.locations.example`(`upstream ragforge_backend { server {应用层内网IP}:31090; }`)。

> ⚠️ 已知文档漂移:`deployment-migration-runbook.md` 旧表里写 `ragforge.net /api/ → <入口层>:19080/19081/19082`(docker-compose 时代端口),**已与现网不符**;现网为应用层 NodePort `31090`。

## 数据层:裸装中间件(独立节点)

| 中间件 | 端口 | 说明 |
| --- | --- | --- |
| PostgreSQL + pgvector | 5432 | 库 `ragforge`;`vl_vector(2560)` 无 HNSW(走顺序扫描) |
| Elasticsearch | 9200 | BM25,索引 `ragforge_chunks`;IK 分词(缺失回退 standard) |
| Redis | 6379 | 认证撤销 / API Key 限流 / ShedLock |
| RocketMQ NameServer | 9876 | topic `ragforge-document-process` |

> 注:数据层地址来自镜像内 `application-prod.yml` 默认值(Secret 未覆盖 host/port)。本地 Python Reranker(`:8001`)线上**未部署**,rerank 走 DashScope `qwen3-rerank`,该默认配置为死配置。

## 文件存储

- 当前:节点本地 `hostPath /data/files`(ConfigMap `RAGFORGE_UPLOAD_PATH=/data/files/`);`ragforge` 命名空间内无 PVC、集群无 PV。
- 因单节点,`api×3` / `worker` / `judge` 共享同节点同目录,暂无跨节点共享问题。
- OSS 抽象层(`ObjectStorage`,`storage.backend=aliyun|local`)已就绪,Secret 已配 `ALIYUN_OSS_*`;**跨节点多实例部署前应切换主存储到 OSS/NAS**。

## 镜像与版本追溯

- 镜像仓库:阿里云个人 ACR(广州 VPC)。
- tag 编入 git commit sha:`ragforge_image_repository:backend-<sha>` / `:frontend-<sha>`;历史 ReplicaSet 可反推上线序列。
- 查询当前部署版本:
  ```bash
  kubectl -n ragforge get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
  ```

## 部署清单与脚本

- k3s 清单:[`deploy/k8s/ragforge/`](../../deploy/k8s/ragforge/)(namespace / configmap / deployment / service)。
- 入口 Nginx:`deploy/nginx/ragforge-k8s.locations.example`。
- 脚本:`deploy/scripts/`(构建镜像、创建 Secret、部署、校验、ingress 重载、磁盘清理等)。
- 数据库手工迁移(向量 2560 维切换):`backend/src/main/resources/db/manual/V27__vl_unified_vector.sql` + `RUNBOOK.md`。

## 已知待办(运维向)

- 向量检索顺序扫描 → 评估降维 / PQ 量化 / 专用向量库。
- 文件存储本地盘 → 切 OSS/NAS,为跨节点扩容铺路。
- 监控:`docs/deploy/grafana-v5.json` 面板尚未导入目标集群;LLM-as-Judge 质量看板已内置于应用。
