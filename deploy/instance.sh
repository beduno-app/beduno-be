#!/bin/bash
# Lifecycle for the single production instance: start, stop, status, logs, shell.
#
# Stopping when the API is not in use is the whole reason this deployment costs ~$2.6/month
# instead of ~$55, so stopping has to be as easy as starting. Everything a stop invalidates --
# the public IP and the ECR token -- is refreshed by boot.sh on the way back up.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
NAME=beduno-api
here="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat >&2 <<'USAGE'
usage: instance.sh <command>

  start              start the instance and wait for the API to answer
  stop [--no-snapshot]
                     snapshot the data volume, then stop the instance
  status             instance state, address and API health
  logs [service] [n] tail container logs (default: all services, 100 lines).
                     SSM caps returned output at 24000 characters; name a service and a
                     smaller count when it truncates, or use `shell` for the full stream.
  shell              open an SSM session on the box
USAGE
  exit 2
}

instance_id() {
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=${NAME}" \
              "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text
}

require_instance() {
  local id
  id="$(instance_id)"
  if [ -z "$id" ]; then
    echo "ERROR: no ${NAME} instance. Launch one with ${here}/launch.sh" >&2
    exit 1
  fi
  echo "$id"
}

# Always succeeds: an unset domain yields an empty string, and callers decide what that means.
# Returning non-zero would kill them instead -- `url="$(site)"` under `set -e` takes the exit
# status of the substitution, so the "no domain configured" branch below was unreachable.
site() {
  local domain
  domain="$(aws ssm get-parameter --region "$REGION" --name /beduno/prod/DUCKDNS_DOMAIN \
    --query Parameter.Value --output text 2>/dev/null || true)"
  if [ -n "$domain" ]; then
    echo "https://${domain}.duckdns.org"
  fi
  return 0
}

# Runs a command on the box through SSM and prints its output. Nothing here needs a shell, and
# there is no port 22 or key pair to open one with.
remote() {
  local id="$1" script="$2" command_id status
  command_id="$(aws ssm send-command --region "$REGION" \
    --instance-ids "$id" --document-name AWS-RunShellScript \
    --parameters "commands=[\"${script}\"]" \
    --query Command.CommandId --output text)"
  status=""
  for _ in $(seq 1 30); do
    sleep 2
    status="$(aws ssm get-command-invocation --region "$REGION" \
      --command-id "$command_id" --instance-id "$id" --query Status --output text 2>/dev/null || echo Pending)"
    case "$status" in
      Success|Failed|Cancelled|TimedOut) break ;;
      *) ;;
    esac
  done

  # Without this, a command still Pending after a minute -- the SSM agent has not registered yet,
  # which is exactly the state right after a start -- printed nothing and returned success, so the
  # operator saw empty output and no reason for it.
  case "$status" in
    Success|Failed|Cancelled|TimedOut) ;;
    *)
      echo "ERROR: SSM command ${command_id} is still ${status:-unreachable} after 60s." >&2
      echo "       The agent may not have registered yet; retry in a moment." >&2
      return 1
      ;;
  esac
  aws ssm get-command-invocation --region "$REGION" \
    --command-id "$command_id" --instance-id "$id" \
    --query StandardOutputContent --output text
  local stderr
  stderr="$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$command_id" --instance-id "$id" \
    --query StandardErrorContent --output text)"
  [ -n "$stderr" ] && [ "$stderr" != "None" ] && echo "$stderr" >&2
  return 0
}

cmd_start() {
  local id url
  id="$(require_instance)"
  aws ec2 start-instances --region "$REGION" --instance-ids "$id" >/dev/null
  echo "starting ${id}"
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$id"

  url="$(site)"
  if [ -z "$url" ]; then
    echo "instance is running; /beduno/prod/DUCKDNS_DOMAIN is not set, so there is no URL to poll"
    exit 0
  fi

  # The DNS record only moves to this boot's address once boot.sh runs, and the JVM needs a while
  # after that. Polling the real endpoint is the only honest readiness signal.
  echo "waiting for ${url}/actuator/health"
  for _ in $(seq 1 60); do
    sleep 5
    if curl -fsS --max-time 5 "${url}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      echo "  UP -- ${url}"
      exit 0
    fi
    printf '.'
  done
  echo
  echo "WARNING: no healthy response after 5 minutes. Check: ${here}/instance.sh logs" >&2
  exit 1
}

cmd_stop() {
  local id
  id="$(require_instance)"
  if [ "${1:-}" != "--no-snapshot" ]; then
    "$here/backup.sh" snapshot
  fi
  aws ec2 stop-instances --region "$REGION" --instance-ids "$id" >/dev/null
  echo "stopping ${id}"
  aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$id"
  echo "stopped; billing is now the EBS volume only"
}

cmd_status() {
  local id url
  id="$(require_instance)"
  aws ec2 describe-instances --region "$REGION" --instance-ids "$id" \
    --query 'Reservations[0].Instances[0].{Id:InstanceId,State:State.Name,PublicIp:PublicIpAddress,Type:InstanceType}' \
    --output table
  echo "image: $(aws ssm get-parameter --region "$REGION" --name /beduno/prod/APP_IMAGE \
    --query Parameter.Value --output text 2>/dev/null || echo '(unset)')"
  url="$(site)"
  if [ -n "$url" ]; then
    printf 'health: '
    curl -fsS --max-time 5 "${url}/actuator/health" 2>/dev/null || echo "(no response from ${url})"
    echo
  fi
}

cmd_logs() {
  local id service="${1:-}" lines="${2:-100}"
  id="$(require_instance)"
  remote "$id" "cd /opt/beduno && docker compose -f docker-compose.prod.yml --env-file .env logs --no-color --tail ${lines} ${service}"
}

cmd_shell() {
  local id
  id="$(require_instance)"
  exec aws ssm start-session --region "$REGION" --target "$id"
}

case "${1:-}" in
  start)  cmd_start ;;
  stop)   shift; cmd_stop "$@" ;;
  status) cmd_status ;;
  logs)   shift; cmd_logs "$@" ;;
  shell)  cmd_shell ;;
  *)      usage ;;
esac
