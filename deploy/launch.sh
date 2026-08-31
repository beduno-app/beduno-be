#!/bin/bash
# Launch the single production instance. Idempotent in the sense that it refuses to run if an
# instance tagged Name=beduno-api already exists -- relaunching would orphan the EBS volume that
# holds the only copy of the database.
#
# After the first launch this script is not the deploy path. "aws ec2 start-instances" is:
# boot.sh re-runs on every boot, refreshes DNS and the ECR token, and brings the stack back up.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
NAME=beduno-api
SG_ID="${SG_ID:-sg-0a7d87a2d1dfe6ac9}"
SUBNET_ID="${SUBNET_ID:-subnet-0ce8ebafd619884bc}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t4g.small}"
VOLUME_GB="${VOLUME_GB:-12}"
here="$(cd "$(dirname "$0")" && pwd)"

existing="$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Name,Values=${NAME}" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)"
if [ -n "$existing" ]; then
  echo "ERROR: instance ${existing} already exists. Start it instead of launching a second one." >&2
  exit 1
fi

# boot.sh fails the whole stack if any of these is missing, and it fails on the box where the
# error is far less visible. Check here instead.
echo "checking SSM parameters"
for p in DUCKDNS_DOMAIN DUCKDNS_TOKEN POSTGRES_PASSWORD JWT_SECRET APP_IMAGE; do
  if aws ssm get-parameter --region "$REGION" --name "/beduno/prod/$p" --query Parameter.Name --output text >/dev/null 2>&1; then
    echo "  ok   /beduno/prod/$p"
  else
    echo "  MISSING /beduno/prod/$p" >&2
    missing=1
  fi
done
if [ "${missing:-0}" = 1 ]; then
  echo "ERROR: store the missing parameters first." >&2
  exit 1
fi

# Resolved from the public SSM parameter rather than hardcoded, so a rebuild picks up the current
# patched AMI instead of whichever one happened to be current on the day this was written.
AMI_ID="$(aws ssm get-parameters --region "$REGION" \
  --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
  --query 'Parameters[0].Value' --output text)"
echo "AMI: $AMI_ID"

USER_DATA="$(mktemp)"
trap 'rm -f "$USER_DATA"' EXIT
python3 "$here/render-user-data.py" > "$USER_DATA"
echo "user data: $(wc -c < "$USER_DATA") bytes"

# --instance-initiated-shutdown-behavior stop: a "shutdown" typed inside the guest must never
# terminate the box, because terminating it destroys the database volume.
# No key pair and no port 22 -- shell access is via SSM Session Manager.
INSTANCE_ID="$(aws ec2 run-instances --region "$REGION" \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --subnet-id "$SUBNET_ID" \
  --security-group-ids "$SG_ID" \
  --associate-public-ip-address \
  --iam-instance-profile Name=beduno-ec2-profile \
  --instance-initiated-shutdown-behavior stop \
  --metadata-options "HttpTokens=required,HttpEndpoint=enabled" \
  --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":${VOLUME_GB},\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true,\"Encrypted\":true}}]" \
  --user-data "file://${USER_DATA}" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${NAME}},{Key=Project,Value=beduno}]" \
                       "ResourceType=volume,Tags=[{Key=Name,Value=${NAME}},{Key=Project,Value=beduno}]" \
  --query 'Instances[0].InstanceId' --output text)"

echo "launched ${INSTANCE_ID}; waiting for it to run"
aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCE_ID"
aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].{Id:InstanceId,State:State.Name,PublicIp:PublicIpAddress,Type:InstanceType}' \
  --output table
echo
echo "cloud-init still has to install docker, pull images and obtain a certificate (a few minutes)."
echo "watch it with:  aws ssm start-session --target ${INSTANCE_ID}"
