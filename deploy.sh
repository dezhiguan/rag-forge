#!/bin/bash
set -e

echo "=== RAGForge 部署脚本 ==="
echo "目标服务器: 轻量应用服务器 (8.163.63.222)"
echo ""

echo "[1/4] 构建前端..."
cd frontend
npm install
npm run build
cd ..

echo "[2/4] 构建后端..."
cd backend
mvn clean package -DskipTests
cd ..

echo "[3/4] 构建 Docker 镜像..."
docker build -t ragforge-backend:latest ./backend

echo "[4/4] 重启应用服务..."
docker compose -f docker-compose-app.yml down
docker compose -f docker-compose-app.yml up -d

echo ""
echo "=== 部署完成 ==="
echo "前端: http://8.163.63.222"
echo "后端: http://8.163.63.222/api/v1/health"
echo ""
echo "检查服务状态:"
docker compose -f docker-compose-app.yml ps

