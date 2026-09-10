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

# Empty rather than fatal when the parameter is absent. Used for the bootstrap credentials, which
# exist only between the first launch and the first successful login.
param_optional() {
  aws ssm get-parameter --name "$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text 2>/dev/null || true
}

log "fetching configuration from SSM Parameter Store"
DUCKDNS_DOMAIN="$(param /beduno/prod/DUCKDNS_DOMAIN)"
DUCKDNS_TOKEN="$(param /beduno/prod/DUCKDNS_TOKEN)"
POSTGRES_PASSWORD="$(param /beduno/prod/POSTGRES_PASSWORD)"
JWT_SECRET="$(param /beduno/prod/JWT_SECRET)"
APP_IMAGE="$(param /beduno/prod/APP_IMAGE)"

# There is no user-management API, so the first agency and administrator are created at startup
# from these. Store them before the first launch, then delete them once you have logged in --
# they are a standing copy of an administrator password, re-read at every boot.
BOOTSTRAP_ENABLED="$(param_optional /beduno/prod/BOOTSTRAP_ENABLED)"
BOOTSTRAP_AGENCY_NAME="$(param_optional /beduno/prod/BOOTSTRAP_AGENCY_NAME)"
BOOTSTRAP_ADMIN_EMAIL="$(param_optional /beduno/prod/BOOTSTRAP_ADMIN_EMAIL)"
BOOTSTRAP_ADMIN_PASSWORD="$(param_optional /beduno/prod/BOOTSTRAP_ADMIN_PASSWORD)"

# Point the hostname at whatever address this boot was given. An empty ip= makes DuckDNS use the
# requesting address, so this works without the instance having to discover its own public IP.
log "updating DuckDNS record for ${DUCKDNS_DOMAIN}.duckdns.org"
duckdns_result="$(curl -fsS --retry 3 --retry-delay 2 \
  "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=")"
if [ "$duckdns_result" != "OK" ]; then
  log "ERROR: DuckDNS update returned '${duckdns_result}' (expected OK)"
  exit 1
fi

# docker compose interpolates variable references in an env file, so a value containing a dollar
# sign is silently truncated at that point: BOOTSTRAP_ADMIN_PASSWORD=Adm1n$ecret reaches the
# container as "Adm1n". Doubling the dollar is compose's escape for a literal one. This bites the
# admin password hardest -- the bootstrap runs once on an empty database and there is no
# user-management API, so a mangled password locks the deployment out of its own admin account.
# Verified against compose v2: '$' and "'" and '#' all round-trip with this escaping.
esc() { printf '%s' "$1" | sed 's/\$/$$/g'; }

# A newline would end the assignment early and turn the rest of the value into garbage lines.
# Refuse rather than write a file that half-works.
for name in POSTGRES_PASSWORD JWT_SECRET BOOTSTRAP_ADMIN_PASSWORD; do
  case "${!name-}" in
    *$'\n'*)
      log "ERROR: ${name} contains a newline; store it without one"
      exit 1
      ;;
    *) ;;
  esac
done

# Written with a restrictive mode before any secret reaches it: docker compose reads this file,
# and it holds the database password and the JWT signing key.
log "writing ${ENV_FILE}"
install -m 600 /dev/null "$ENV_FILE"
cat > "$ENV_FILE" <<EOF
APP_IMAGE=$(esc "${APP_IMAGE}")
POSTGRES_PASSWORD=$(esc "${POSTGRES_PASSWORD}")
JWT_SECRET=$(esc "${JWT_SECRET}")
SITE_ADDRESS=${DUCKDNS_DOMAIN}.duckdns.org
CORS_ALLOWED_ORIGINS=
BOOTSTRAP_ENABLED=${BOOTSTRAP_ENABLED:-false}
BOOTSTRAP_AGENCY_NAME=$(esc "${BOOTSTRAP_AGENCY_NAME}")
BOOTSTRAP_ADMIN_EMAIL=$(esc "${BOOTSTRAP_ADMIN_EMAIL}")
BOOTSTRAP_ADMIN_PASSWORD=$(esc "${BOOTSTRAP_ADMIN_PASSWORD}")
EOF

log "authenticating to ECR"
registry="${APP_IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$registry"

log "starting the stack"
cd "$APP_DIR"
docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" pull
# --wait blocks until the containers that declare a healthcheck report healthy, so this unit
# fails when the app cannot start instead of reporting success over a crash loop. Without it
# "up -d" returns as soon as the containers exist, which is not the same thing at all.
docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" \
  up -d --remove-orphans --wait --wait-timeout 300

log "done; https://${DUCKDNS_DOMAIN}.duckdns.org/actuator/health"
