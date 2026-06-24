# Multi-stage build for RAGForge frontend on k3s (Server 3).
# Build context: rag-forge repository root.
#
#   docker build -f frontend/Dockerfile -t docker.io/ragforge/frontend:<tag> .

FROM node:20-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY deploy/k8s/ragforge/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80

# Pre-built dist (SKIP_FRONTEND_BUILD=1 path)
FROM nginx:1.27-alpine AS runtime-prebuilt
COPY deploy/k8s/ragforge/frontend-nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist /usr/share/nginx/html
EXPOSE 80
