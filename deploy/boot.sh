#!/bin/bash
# Runs on every boot (systemd: beduno.service), not just the first one. That matters because the
# instance is stopped and started to save money, and two things change or expire across a stop:
#   - the public IPv4 address (there is no Elastic IP; an idle one would cost more than the disk)
#   - the ECR authorization token (12 hour lifetime)
# Both are refreshed here, so "start the instance" is the whole deploy after the first time.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
APP_DIR=/opt/beduno
ENV_FILE="${APP_DIR}/.env"

log() { echo "[beduno-boot] $*"; }

param() {
  aws ssm get-parameter --name "$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text
}

log "fetching configuration from SSM Parameter Store"
DUCKDNS_DOMAIN="$(param /beduno/prod/DUCKDNS_DOMAIN)"
DUCKDNS_TOKEN="$(param /beduno/prod/DUCKDNS_TOKEN)"
POSTGRES_PASSWORD="$(param /beduno/prod/POSTGRES_PASSWORD)"
JWT_SECRET="$(param /beduno/prod/JWT_SECRET)"
APP_IMAGE="$(param /beduno/prod/APP_IMAGE)"

# Point the hostname at whatever address this boot was given. An empty ip= makes DuckDNS use the
# requesting address, so this works without the instance having to discover its own public IP.
log "updating DuckDNS record for ${DUCKDNS_DOMAIN}.duckdns.org"
duckdns_result="$(curl -fsS --retry 3 --retry-delay 2 \
  "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=")"
if [ "$duckdns_result" != "OK" ]; then
  log "ERROR: DuckDNS update returned '${duckdns_result}' (expected OK)"
  exit 1
fi

# Written with a restrictive mode before any secret reaches it: docker compose reads this file,
# and it holds the database password and the JWT signing key.
log "writing ${ENV_FILE}"
install -m 600 /dev/null "$ENV_FILE"
cat > "$ENV_FILE" <<EOF
APP_IMAGE=${APP_IMAGE}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
JWT_SECRET=${JWT_SECRET}
SITE_ADDRESS=${DUCKDNS_DOMAIN}.duckdns.org
CORS_ALLOWED_ORIGINS=
EOF

log "authenticating to ECR"
registry="${APP_IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$registry"

log "starting the stack"
cd "$APP_DIR"
docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" pull
docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" up -d --remove-orphans

log "done; https://${DUCKDNS_DOMAIN}.duckdns.org/actuator/health"
