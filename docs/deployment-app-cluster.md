# RAGForge App Replica Deployment

This document describes the app-layer replica deployment shape.

## Topology

- Server 2 runs Nginx, frontend assets, TLS, and reverse proxy.
- Server 3 runs three `ragforge-backend` Docker containers on the same host.
- Server 1 continues to run PostgreSQL, Elasticsearch, Redis, RocketMQ, and reranker.

This is single-host multi-replica deployment. It can improve same-host request concurrency, but it is not machine-level high availability.

## GitHub Secrets

The app deployment still targets one host:

```text
RAGFORGE_APP_HOST=8.138.191.228
RAGFORGE_APP_USER=root
RAGFORGE_APP_PORT=22
```

Optional health ports for same-host replicas:

```text
RAGFORGE_APP_HEALTH_PORTS=8080 8081 8082
```

Nginx upstreams should point to Server 2 local SSH tunnel ports:

```text
RAGFORGE_BACKEND_UPSTREAMS=172.19.40.32:19080,172.19.40.32:19081,172.19.40.32:19082
```

If this secret is unset, the committed `nginx.conf` already uses the same tunnel upstreams.

Existing ingress secrets are still required:

```text
RAGFORGE_INGRESS_HOST=8.163.63.222
RAGFORGE_INGRESS_USER=root
RAGFORGE_INGRESS_PORT=22
RAGFORGE_INGRESS_SSH_KEY=<private key>
RAGFORGE_APP_SSH_KEY=<private key that can access Server 3 through Server 2>
```

## Storage Requirement

Same-host replicas share the same host bind mount `/data/files`, so uploaded files remain visible to all three backend containers on Server 3.

If app replicas are later moved to multiple machines, uploaded files must be moved off node-local Docker volumes first.

Use one of these before switching traffic to multiple app nodes:

- Alibaba Cloud OSS, preferred for object storage.
- NAS/NFS mounted at the same path on every app node.

If NAS/NFS is used, add a server-local `docker-compose.override.yml` on every app node to bind-mount the shared path to `/data/files`.

## Shared Env

Server 3 keeps shared secrets and service config in `/opt/shared/env`. These files are server-local and must not be committed.

```bash
# /opt/shared/env/common.env
TZ=Asia/Shanghai
DASHSCOPE_API_KEY=<dashscope-api-key>
LLM_API_KEY=<dashscope-api-key>

# /opt/shared/env/ragforge.env
SPRING_PROFILES_ACTIVE=prod
SPRING_OUTPUT_ANSI_ENABLED=always
JAVA_OPTS=-Xms512m -Xmx1g
```

RAGForge containers read both files through `env_file`, so all three backend replicas receive the same key and JVM settings.

Manual deploys use only the production compose file:

```bash
docker compose -f docker-compose-backend.yml up -d --force-recreate
```

## JVM

The current deployment intentionally keeps the existing JVM setting:

```text
JAVA_OPTS="-Xms512m -Xmx1g"
```

Do not raise heap until the app-node memory size is confirmed.
