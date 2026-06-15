# k3s registry mirror (optional)

`registries.yaml` in this directory is a **reference copy only**. It is **not** applied automatically by `deploy/scripts/deploy-ragforge-k8s.sh`.

## When to use

Use this file only if Server 3 cannot reliably pull from Docker Hub and you want k3s/containerd to use a mirror for `docker.io` pulls (for example sandbox images such as `rancher/mirrored-pause`).

Note: `rancher/mirrored-pause` is a Docker Hub namespace (`docker.io/rancher/...`), not a separate registry host. Configure mirrors under `docker.io` only.

## How to enable

Run on Server 3 as root:

```bash
sudo cp -a /etc/rancher/k3s/registries.yaml /etc/rancher/k3s/registries.yaml.bak.$(date +%Y%m%d%H%M%S) 2>/dev/null || true
sudo cp deploy/k3s/registries.yaml /etc/rancher/k3s/registries.yaml
sudo systemctl restart k3s
```

Back up any existing file before overwriting. After changing `registries.yaml`, k3s must be restarted for the mirror to take effect.

## Default deployment behavior

`deploy/scripts/deploy-ragforge-k8s.sh` does **not** install this file and does **not** restart k3s by default. RAGForge recovery relies primarily on locally built images and airgap tar files under `/var/lib/rancher/k3s/agent/images/`.
