#!/bin/bash
# Snapshots of the data volume.
#
# The database is a container volume on the instance's root EBS volume, and that volume is the only
# copy of it. There is no managed database to fall back on and no replica: losing the volume loses
# every stay, worker and audit row. Snapshots are the entire disaster-recovery story, so they have
# to be cheap enough to take often -- EBS snapshots are incremental, so a nightly one on a mostly
# idle 12 GB volume costs cents per month.
set -euo pipefail

REGION="${AWS_REGION:-eu-central-1}"
NAME=beduno-api
RETENTION_DAYS="${RETENTION_DAYS:-14}"

usage() {
  cat >&2 <<'USAGE'
usage: backup.sh <command>

  snapshot           take a snapshot of the data volume now
  list               list snapshots, newest first
  prune [days]       delete snapshots older than N days (default 14)
  enable-daily       create the daily DLM policy (snapshots even while stopped)
  restore <snap-id>  print the steps to restore onto a new volume
USAGE
  exit 2
}

volume_id() {
  local id
  id="$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=${NAME}" \
              "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].BlockDeviceMappings[?DeviceName==`/dev/xvda`].Ebs.VolumeId' \
    --output text)"
  if [ -z "$id" ]; then
    echo "ERROR: no volume found for a ${NAME} instance." >&2
    exit 1
  fi
  echo "$id"
}

cmd_snapshot() {
  local volume snapshot
  volume="$(volume_id)"
  # Crash-consistent, not application-consistent: Postgres is running and this does not quiesce it.
  # Recovery replays the WAL exactly as it would after a power cut, which is the same guarantee the
  # instance already relies on, and far better than having no copy at all.
  snapshot="$(aws ec2 create-snapshot --region "$REGION" \
    --volume-id "$volume" \
    --description "beduno ${NAME} $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --tag-specifications "ResourceType=snapshot,Tags=[{Key=Name,Value=${NAME}},{Key=Project,Value=beduno}]" \
    --query SnapshotId --output text)"
  echo "${snapshot} (from ${volume}); it completes in the background"
}

cmd_list() {
  aws ec2 describe-snapshots --region "$REGION" --owner-ids self \
    --filters "Name=tag:Project,Values=beduno" \
    --query 'reverse(sort_by(Snapshots,&StartTime))[].{Id:SnapshotId,Started:StartTime,State:State,GiB:VolumeSize}' \
    --output table
}

cmd_prune() {
  local days="${1:-$RETENTION_DAYS}" cutoff found=0
  # JMESPath's comparison operators are defined for numbers only, so filtering on StartTime there
  # matches nothing at all rather than erroring. ISO-8601 UTC strings sort correctly as text.
  cutoff="$(date -u -v-"${days}"d +%Y-%m-%dT%H:%M:%S 2>/dev/null \
    || date -u -d "${days} days ago" +%Y-%m-%dT%H:%M:%S)"
  while read -r snapshot started; do
    [ -n "$snapshot" ] || continue
    if [[ "$started" < "$cutoff" ]]; then
      echo "deleting ${snapshot} (${started})"
      aws ec2 delete-snapshot --region "$REGION" --snapshot-id "$snapshot"
      found=1
    fi
  done < <(aws ec2 describe-snapshots --region "$REGION" --owner-ids self \
    --filters "Name=tag:Project,Values=beduno" \
    --query 'Snapshots[].[SnapshotId,StartTime]' --output text)
  [ "$found" = 0 ] && echo "nothing older than ${days} days"
  return 0
}

# DLM snapshots by volume tag rather than by instance, which is what makes it work on a box that is
# stopped most of the time -- the volume exists and is tagged whether or not anything is running.
cmd_enable_daily() {
  local role_arn
  role_arn="$(aws iam get-role --role-name AWSDataLifecycleManagerDefaultRole \
    --query Role.Arn --output text 2>/dev/null || true)"
  if [ -z "$role_arn" ]; then
    echo "The DLM service role does not exist yet. Create it once with:" >&2
    echo "  aws dlm create-default-role --resource-type snapshot" >&2
    exit 1
  fi
  aws dlm create-lifecycle-policy --region "$REGION" \
    --description "beduno daily volume snapshots" \
    --state ENABLED \
    --execution-role-arn "$role_arn" \
    --policy-details "{
      \"PolicyType\": \"EBS_SNAPSHOT_MANAGEMENT\",
      \"ResourceTypes\": [\"VOLUME\"],
      \"TargetTags\": [{\"Key\": \"Project\", \"Value\": \"beduno\"}],
      \"Schedules\": [{
        \"Name\": \"daily\",
        \"CreateRule\": {\"Interval\": 24, \"IntervalUnit\": \"HOURS\", \"Times\": [\"03:00\"]},
        \"RetainRule\": {\"Count\": ${RETENTION_DAYS}},
        \"CopyTags\": true
      }]
    }" \
    --query PolicyId --output text
}

cmd_restore() {
  local snapshot="${1:-}"
  [ -n "$snapshot" ] || usage
  cat <<RESTORE
Restoring ${snapshot} replaces the instance's root volume, so it is deliberately manual:

  1. ${0%/*}/instance.sh stop --no-snapshot
  2. aws ec2 create-volume --region ${REGION} --snapshot-id ${snapshot} \\
       --availability-zone <the instance's AZ> --volume-type gp3 --encrypted
  3. aws ec2 detach-volume --region ${REGION} --volume-id <current volume>
  4. aws ec2 attach-volume --region ${REGION} --device /dev/xvda \\
       --instance-id <instance> --volume-id <new volume>
  5. ${0%/*}/instance.sh start

Keep the detached volume until the restored one has served real traffic; deleting it is the
step that cannot be undone.
RESTORE
}

case "${1:-}" in
  snapshot)     cmd_snapshot ;;
  list)         cmd_list ;;
  prune)        shift; cmd_prune "$@" ;;
  enable-daily) cmd_enable_daily ;;
  restore)      shift; cmd_restore "$@" ;;
  *)            usage ;;
esac
