#!/bin/bash
# Push the deploy files in this repository onto the running instance.
#
# docker-compose.prod.yml, the Caddyfile and boot.sh reach /opt/beduno exactly once, through
# cloud-init on the instance's very first boot. publish.sh updates the container image and nothing
# else. So a change to any of those three files could be reviewed, merged, and published, with CI
# green throughout, while the box carried on serving the version it was born with -- the repository
# and production disagreeing with no signal anywhere that they did. The Caddyfile's own instruction
# to refresh the CloudFront trusted-proxy ranges had no path to production at all.
#
# This sends the current contents over SSM and restarts the unit so they take effect.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
NAME=beduno-api
APP_DIR=/opt/beduno
FILES=(docker-compose.prod.yml Caddyfile boot.sh)

here="$(cd "$(dirname "$0")" && pwd)"

INSTANCE_ID="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=${NAME}" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)"

if [ -z "$INSTANCE_ID" ]; then
  echo "ERROR: no running instance; start it first:  ${here}/instance.sh start" >&2
  exit 1
fi

# base64 rather than a heredoc: the file contents are full of quotes, ${...} and {$...} sequences
# that must survive both the JSON parameter encoding and the remote shell untouched.
commands=()
for name in "${FILES[@]}"; do
  encoded="$(base64 < "$here/$name" | tr -d '\n')"
  commands+=("echo '${encoded}' | base64 -d > '${APP_DIR}/${name}'")
done
commands+=("chmod +x '${APP_DIR}/boot.sh'")
commands+=("systemctl restart beduno.service")

# Build the JSON payload with python rather than by hand: instance.sh's habit of interpolating
# straight into a JSON string breaks on any value containing a quote or a backslash.
payload="$(python3 - "${commands[@]}" <<'PY'
import json, sys
print(json.dumps({"commands": sys.argv[1:]}))
PY
)"

echo "syncing ${FILES[*]} to ${INSTANCE_ID} and restarting"
COMMAND_ID="$(aws ssm send-command --region "$REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "beduno deploy-file sync" \
  --parameters "$payload" \
  --query Command.CommandId --output text)"

echo "  command ${COMMAND_ID}; waiting (boot.sh brings the stack up with --wait)"
status=""
for _ in $(seq 1 60); do
  sleep 10
  status="$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Success) echo "  synced"; break ;;
    Failed|Cancelled|TimedOut)
      echo >&2
      echo "ERROR: sync $status -- the stack did not come up healthy with the new files." >&2
      echo "       The previous files are gone; fix forward or restore from git and re-run." >&2
      echo "       Logs:  ${here}/instance.sh logs" >&2
      exit 1
      ;;
    *) printf '.' ;;
  esac
done

if [ "$status" != Success ]; then
  echo >&2
  echo "ERROR: gave up waiting after 10 minutes (last status: ${status:-unknown})." >&2
  exit 1
fi

echo
"$here/instance.sh" status
