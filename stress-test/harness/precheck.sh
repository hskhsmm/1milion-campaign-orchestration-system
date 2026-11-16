#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/live-common.sh"

EVID=$1 MCP=$2 BASE_URL=$3
commit=$(json_field "$EVID/run.json" loadgen_sha)
app_sha=$(json_field "$EVID/run.json" expected_app_sha)
app_count=$(json_field "$EVID/run.json" expected_apps)

group() {
  aws_ autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$1" \
    --query 'AutoScalingGroups[0].{min:MinSize,desired:DesiredCapacity,max:MaxSize,instances:Instances[].{id:InstanceId,type:InstanceType,state:LifecycleState,health:HealthStatus,template:LaunchTemplate.Version},target_groups:TargetGroupARNs}' \
    --output json
}
group "$APP_ASG" > "$EVID/app-asg.json"
group "$WORKER_ASG" > "$EVID/worker-asg.json"
# ASG가 실제로 사용하는 대상 그룹에서 healthy 인스턴스 ID를 가져온다.
target=$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["target_groups"]; assert len(t)==1; print(t[0])' "$EVID/app-asg.json")
aws_ elbv2 describe-target-health --target-group-arn "$target" \
  --query 'TargetHealthDescriptions[?TargetHealth.State==`healthy`].Target.Id' --output json > "$EVID/alb-healthy.json"
layout=$(aws_ ssm get-parameter --name /batch-kafka/prod/CAMPAIGN_LAYOUT --query Parameter.Value --output text)
[[ "$layout" == combined-fixed ]] || { echo "combined-fixed 필요. 현재 $layout" >&2; exit 2; }
[[ "$(json_field "$EVID/worker-asg.json" desired)" == 0 && -z "$(app_instance_ids "$WORKER_ASG")" ]] ||
  { echo "전용 워커가 실행 중입니다." >&2; exit 2; }
image=$(aws_ ecr describe-images --repository-name batch-kafka-system --image-ids "imageTag=$app_sha" \
  --query 'imageDetails[0].imageDigest' --output text)
[[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]]
image="631124976154.dkr.ecr.$AWS_REGION.amazonaws.com/batch-kafka-system@$image"
read -ra apps <<< "$(app_instance_ids)"
app_snapshot "${apps[@]}" > "$EVID/apps-before.txt"

files='k6-load-test.js k6-tps-test.js run-test.sh'
for f in $files; do
  hash=$(git -C "$HERE" show "$commit:stress-test/$f" | python3 -c 'import hashlib,sys; print(hashlib.md5(sys.stdin.buffer.read()).hexdigest())')
  printf '%s %s\n' "$hash" "$f"
done > "$EVID/loadgen-expected.md5"
ssm_run "$MCP" "set -e
export PATH=$LOADGEN_PATH
cd $LOADGEN_REPO
git -c safe.directory=$LOADGEN_REPO rev-parse HEAD
k6 version
cd stress-test
md5sum $files" > "$EVID/loadgen-before.txt"
ssm_run "$MCP" "$(prom_command query "$LAG")" > "$EVID/pre-lag.json"
ssm_run "$MCP" "$(prom_command query 'max(hikaricp_connections_pending)')" > "$EVID/pre-hikari.json"
curl --max-time 10 -fsS "$BASE_URL/actuator/health" > "$EVID/alb-health.json"

python3 - "$EVID" "$commit" "$app_count" "$image" <<'PY'
import json, sys
from pathlib import Path

root, commit, count, image = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
def read(name):
    return json.loads((root / name).read_text())
def scalar(name):
    doc = read(name)
    result = doc["data"]["result"]
    return float(result[0]["value"][1]) if doc["status"] == "success" and len(result) == 1 else None
def hashes(lines):
    return {name: digest for digest, name in map(str.split, lines)}
loadgen, k6, *actual = (root / "loadgen-before.txt").read_text().splitlines()
expected = hashes((root / "loadgen-expected.md5").read_text().splitlines())
actual = hashes(actual)
apps = [line.split() for line in (root / "apps-before.txt").read_text().splitlines() if line.strip()]
asg, healthy = read("app-asg.json"), read("alb-healthy.json")
lag, hikari = scalar("pre-lag.json"), scalar("pre-hikari.json")
checks = {
    "loadgen 파일·SHA": expected == actual and loadgen == commit,
    "ASG 고정 용량": asg["min"] == asg["desired"] == asg["max"] == count,
    "ALB·ASG healthy": len(healthy) == len(asg["instances"]) == len(apps) == count
        and set(healthy) == {a[0] for a in apps} == {i["id"] for i in asg["instances"]
                                                    if i["state"] == "InService" and i["health"] == "Healthy"}
        and read("alb-health.json")["status"] == "UP",
    "배포 이미지·역할": len(apps) == count and all(a[1] == image and "CAMPAIGN_ROLE=combined" in a[3:]
        and "CAMPAIGN_SCHEDULED_BATCH_ENABLED=false" in a[3:] for a in apps),
    "Kafka lag·Hikari pending": lag == hikari == 0,
}
doc = dict(expected_md5=expected, loadgen_md5=actual, expected_commit=commit, loadgen_commit=loadgen,
           k6_version=k6, ssm_image=image, app_images={a[0]: a[1] for a in apps}, asg=asg,
           alb_healthy=len(healthy), kafka_lag=lag, hikari_pending=hikari, checks=checks)
(root / "precheck.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
failed = [name for name, passed in checks.items() if not passed]
if failed:
    sys.exit("사전 점검 실패: " + ", ".join(failed))
PY
