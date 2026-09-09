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

echo "pointing /beduno/prod/APP_IMAGE at ${TAG}"
aws ssm put-parameter --region "$REGION" \
  --name /beduno/prod/APP_IMAGE --type String --overwrite --value "$IMAGE" >/dev/null

INSTANCE_ID="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=${NAME}" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)"

if [ -z "$INSTANCE_ID" ]; then
  echo
  echo "No running instance. The parameter is updated, so the next start picks this image up:"
  echo "  ${here}/instance.sh start"
  exit 0
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
  echo "         Roll it by hand:  aws ssm start-session --target ${INSTANCE_ID}" >&2
  echo "         then:             sudo systemctl restart beduno.service" >&2
  exit 0
fi

echo "  command ${COMMAND_ID}; waiting (boot.sh pulls the image and waits on health checks)"
for _ in $(seq 1 60); do
  sleep 10
  status="$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Success) echo "  restarted"; break ;;
    Failed|Cancelled|TimedOut) echo "ERROR: restart $status" >&2; exit 1 ;;
    *) printf '.' ;;
  esac
done
echo
"$here/instance.sh" status
