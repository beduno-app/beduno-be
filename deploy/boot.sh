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

# Demo agency, separate from any real tenant. Safe to leave enabled: SeedRunner's idempotency
# check is scoped to its own demo admin account, not to the database being empty.
BEDUNO_SEED_ENABLED="$(param_optional /beduno/prod/BEDUNO_SEED_ENABLED)"

# Point the hostname at whatever address this boot was given. An empty ip= makes DuckDNS use the
# requesting address, so this works without the instance having to discover its own public IP.
log "updating DuckDNS record for ${DUCKDNS_DOMAIN}.duckdns.org"
duckdns_result="$(curl -fsS --retry 3 --retry-delay 2 \
  "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=")"
if [ "$duckdns_result" != "OK" ]; then
  log "ERROR: DuckDNS update returned '${duckdns_result}' (expected OK)"
  exit 1
fi

# compose's env-file parser mangles unquoted values in four separate ways, each of which silently
# produces a different secret than the one stored. Verified against compose v2:
#
#   Adm1n$ecret             -> Adm1n            ($ starts an interpolation)
#   correct horse #battery  -> correct horse    (" #" starts an inline comment)
#   trailing<spaces>        -> trailing         (trailing whitespace is trimmed)
#   'quoted-looking         -> parse failure    (a leading quote opens a quoted value)
#
# This matters most for the admin password: the bootstrap runs once on an empty database and
# there is no user-management API, so a mangled password locks the deployment out of its own
# administrator account with no way back except editing Postgres by hand.
#
# Double-quoting the value settles all four. Inside double quotes the parser still expands
# escapes and interpolations, so backslash, quote and dollar each need escaping first -- in that
# order, or the backslashes introduced by the later rules would themselves be doubled.
esc() {
  printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\$/$$/g')"
}

# A newline is the one case quoting does not save: it ends the assignment and turns the rest of
# the value into garbage lines. Refuse rather than write a file that half-works.
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
BEDUNO_SEED_ENABLED=${BEDUNO_SEED_ENABLED:-false}
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
