#!/bin/bash
# Build the application image, push it to ECR, and point the APP_IMAGE parameter at it.
#
# The image must be arm64: the instance is a t4g (Graviton), and an x86 image does not fail at
# deploy time -- it fails when the container starts, on the box, where the error is far less
# visible. --platform makes that explicit rather than inherited from whatever built it.
#
# The tag is the commit sha, so "which code is running" is answerable from the parameter alone.
# Publishing a dirty tree would make that tag a lie, so it is refused unless ALLOW_DIRTY=1.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
REPO="${ECR_REPO:-beduno-api}"
NAME=beduno-api
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

if [ -n "$(git -C "$root" status --porcelain)" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "ERROR: working tree is dirty; the image tag would not identify what is in the image." >&2
  echo "       Commit first, or re-run with ALLOW_DIRTY=1 to tag it anyway." >&2
  exit 1
fi

# The image is built with `bootJar -x test`, and CI only runs on pushes to main and on PRs -- so a
# commit that exists only on a local branch has been tested by nothing at all. Publishing it still
# produces a sha-tagged image that looks deliberate and traceable, which is the trap. Require HEAD
# to be on origin/main unless the operator says otherwise in as many words.
if [ "${ALLOW_UNTESTED:-0}" != 1 ]; then
  if ! git -C "$root" fetch --quiet origin main 2>/dev/null; then
    echo "ERROR: could not fetch origin/main to check whether this commit has been tested." >&2
    echo "       Re-run with ALLOW_UNTESTED=1 to publish anyway." >&2
    exit 1
  fi
  if [ "$(git -C "$root" rev-parse HEAD)" != "$(git -C "$root" rev-parse origin/main)" ]; then
    echo "ERROR: HEAD is not origin/main, so CI has not run against this commit." >&2
    echo "       The image is built with -x test; nothing else would test it either." >&2
    echo "       Push and let CI go green, or re-run with ALLOW_UNTESTED=1." >&2
    exit 1
  fi
fi

TAG="${TAG:-$(git -C "$root" rev-parse --short HEAD)}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${REPO}:${TAG}"

echo "building ${IMAGE} (linux/arm64)"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"

docker buildx build \
  --platform linux/arm64 \
  -f "$root/docker/Dockerfile" \
  -t "$IMAGE" \
  --push \
  "$root"

# Remembered so a failed rollout can put it back. boot.sh reads this parameter on every boot, so
# leaving it pointing at an image that does not start turns one bad deploy into a box that comes
# up broken every time it is started.
PREVIOUS_IMAGE="$(aws ssm get-parameter --region "$REGION" --name /beduno/prod/APP_IMAGE \
  --query Parameter.Value --output text 2>/dev/null || true)"

echo "pointing /beduno/prod/APP_IMAGE at ${TAG}"
aws ssm put-parameter --region "$REGION" \
  --name /beduno/prod/APP_IMAGE --type String --overwrite --value "$IMAGE" >/dev/null

rollback_parameter() {
  if [ -n "$PREVIOUS_IMAGE" ] && [ "$PREVIOUS_IMAGE" != "$IMAGE" ]; then
    echo "restoring APP_IMAGE to ${PREVIOUS_IMAGE##*:}" >&2
    aws ssm put-parameter --region "$REGION" \
      --name /beduno/prod/APP_IMAGE --type String --overwrite --value "$PREVIOUS_IMAGE" >/dev/null
  fi
}

INSTANCE_ID="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=${NAME}" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)"

if [ -z "$INSTANCE_ID" ]; then
  echo
  echo "No running instance. The parameter is updated, so the next start picks this image up:"
  echo "  ${here}/instance.sh start"
  exit 0
fi

# publish.sh updates the image only. If the deploy files themselves changed recently, the box is
# still running the versions cloud-init wrote on its first boot, and nothing else would say so.
if [ -n "$(git -C "$root" log --oneline -20 --name-only --pretty=format: -- \
    deploy/docker-compose.prod.yml deploy/Caddyfile deploy/boot.sh | sort -u)" ]; then
  echo "NOTE: deploy/docker-compose.prod.yml, deploy/Caddyfile or deploy/boot.sh changed in the"
  echo "      last 20 commits. publish.sh does not update them on the box -- run ${here}/sync.sh"
  echo "      if those changes have not been pushed to the instance yet."
fi

# Snapshot before the roll, not after. Restarting the unit lets Flyway apply pending migrations to
# the only copy of the database, and rollback_parameter below reverts the *image* only -- a schema
# change or a data backfill is permanent. Without this the newest restore point was whenever the
# instance was last stopped, possibly days earlier. The snapshot is incremental and returns in
# seconds; it completes in the background, which is enough, because what matters is that the
# point-in-time marker exists before the migration runs.
echo "snapshotting the data volume before rolling"
if ! "$here/backup.sh" snapshot; then
  echo "ERROR: could not take a pre-deploy snapshot; refusing to roll." >&2
  echo "       Migrations are applied to the only copy of the database and are not reversible" >&2
  echo "       by pointing APP_IMAGE back at the previous tag." >&2
  rollback_parameter
  exit 1
fi

# Restarting the unit re-runs boot.sh, which re-reads the parameters, refreshes the ECR token and
# pulls -- the same path a cold boot takes, so there is only one deploy mechanism to reason about.
echo "rolling ${INSTANCE_ID} onto the new image"
if ! COMMAND_ID="$(aws ssm send-command --region "$REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "beduno deploy ${TAG}" \
  --parameters 'commands=["systemctl restart beduno.service"]' \
  --query Command.CommandId --output text 2>/dev/null)"; then
  echo "WARNING: could not send the restart command (missing ssm:SendCommand?)." >&2
  echo "         APP_IMAGE points at ${TAG}; the instance is still running the old image." >&2
  echo "         Roll it by hand:  aws ssm start-session --target ${INSTANCE_ID}" >&2
  echo "         then:             sudo systemctl restart beduno.service" >&2
  exit 1
fi

# boot.sh brings the stack up with --wait, so Success here means the containers reported healthy,
# not merely that they were created.
echo "  command ${COMMAND_ID}; waiting (boot.sh pulls the image and waits for health)"
status=""
for _ in $(seq 1 60); do
  sleep 10
  status="$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Success) echo "  restarted"; break ;;
    Failed|Cancelled|TimedOut)
      echo >&2
      echo "ERROR: restart $status -- the new image did not come up healthy." >&2
      rollback_parameter
      echo "       Logs:  ${here}/instance.sh logs app" >&2
      exit 1
      ;;
    *) printf '.' ;;
  esac
done

if [ "$status" != Success ]; then
  echo >&2
  echo "ERROR: gave up waiting after 10 minutes (last status: ${status:-unknown})." >&2
  rollback_parameter
  echo "       The instance may still be rolling; check ${here}/instance.sh status" >&2
  exit 1
fi

echo
"$here/instance.sh" status
