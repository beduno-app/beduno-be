#!/bin/bash
# cloud-init user data. Runs once, on first boot of the instance.
# Everything that must happen on EVERY boot lives in /opt/beduno/boot.sh instead.
set -euxo pipefail

COMPOSE_VERSION=v2.32.4
APP_DIR=/opt/beduno

dnf update -y
dnf install -y docker
systemctl enable --now docker

# Amazon Linux 2023 ships the aws CLI, but do not assume it.
command -v aws >/dev/null 2>&1 || dnf install -y awscli-2

# The compose plugin is not packaged for AL2023; install it for the docker CLI.
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL --retry 3 \
  "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-aarch64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

mkdir -p "$APP_DIR"

# __FILES__ is replaced at launch time with heredocs writing docker-compose.prod.yml, Caddyfile
# and boot.sh into APP_DIR. Keeping them out of this template means the deploy files stay
# reviewable in git rather than being buried in a base64 blob.
__FILES__

chmod +x "${APP_DIR}/boot.sh"

cat > /etc/systemd/system/beduno.service <<'UNIT'
[Unit]
Description=Beduno application stack
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/opt/beduno/boot.sh
# The first run pulls images and waits on a Let's Encrypt challenge.
TimeoutStartSec=900
Restart=on-failure
RestartSec=30

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now beduno.service
