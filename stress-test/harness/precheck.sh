#!/usr/bin/env bash
# 부하 전 상태를 읽어 <증거 디렉터리>/precheck.json에 기록한다. 읽기 전용이다.
#
# 사용법 (운영 PC, 저장소 루트): bash stress-test/harness/precheck.sh <증거 디렉터리>
#   EXPECTED_COMMIT  부하 발생기가 맞춰야 할 커밋 (기본: origin/main)
set -euo pipefail
source "$(dirname "$0")/../../ops/scripts/lib-ssm.sh"

EVID="${1:?사용법: $0 <증거 디렉터리>}"
mkdir -p "$EVID"
EXPECTED_COMMIT="${EXPECTED_COMMIT:-$(git rev-parse --short origin/main)}"
LOADGEN_REPO=/home/ssm-user/1milion-campaign-orchestration-system
FILES="k6-load-test.js k6-tps-test.js run-test.sh"
MCP=$(mcp_instance_id)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for f in $FILES; do
  git show "$EXPECTED_COMMIT:stress-test/$f" > "$TMP/expected-$f"
done

ssm_run "$MCP" "d=$LOADGEN_REPO
git -c safe.directory=\"\$d\" -C \"\$d\" log -1 --format=%h
for f in $FILES; do md5sum \"\$d/stress-test/\$f\"; done" > "$TMP/loadgen.txt"

aws_ ssm get-parameter --name /batch-kafka/prod/ECR_IMAGE --query Parameter.Value --output text > "$TMP/ssm-image.txt"

: > "$TMP/app-images.txt"
for i in $(app_instance_ids); do
  printf '%s %s\n' "$i" "$(ssm_run "$i" "docker inspect -f '{{.Config.Image}}' campaign-core-app 2>&1 | head -1")" >> "$TMP/app-images.txt"
done

aws_ autoscaling describe-auto-scaling-groups --auto-scaling-group-names batch-kafka-app-asg \
  --query 'AutoScalingGroups[0].{min:MinSize,desired:DesiredCapacity,max:MaxSize,in_service:length(Instances[?LifecycleState==`InService`]),instance_type:Instances[0].InstanceType}' \
  --output json > "$TMP/asg.json"

TG=$(aws_ elbv2 describe-target-groups --query 'TargetGroups[0].TargetGroupArn' --output text)
aws_ elbv2 describe-target-health --target-group-arn "$TG" \
  --query 'length(TargetHealthDescriptions[?TargetHealth.State==`healthy`])' --output text > "$TMP/alb-healthy.txt"

prom() { ssm_run "$MCP" "curl -fsSG localhost:9090/api/v1/query --data-urlencode 'query=$1'"; }
prom 'sum(kafka_consumergroup_lag{consumergroup="campaign-participation-group"})' > "$TMP/lag.json"
prom 'max(hikaricp_connections_pending)' > "$TMP/hikari.json"

H=$(redis_host)
ssm_run "$MCP" "docker run --rm redis:7-alpine redis-cli -c -h $H SMEMBERS active:campaigns" > "$TMP/active.txt"

python3 - "$TMP" "$EVID/precheck.json" "$EXPECTED_COMMIT" <<'PY'
import hashlib, json, sys
from pathlib import Path
tmp, out, expected_commit = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
files = ["k6-load-test.js", "k6-tps-test.js", "run-test.sh"]
lines = (tmp / "loadgen.txt").read_text().split()
loadgen_md5 = {Path(p).name: h for h, p in zip(lines[1::2], lines[2::2])}
def scalar(name):
    r = json.loads((tmp / name).read_text())["data"]["result"]
    return float(r[0]["value"][1]) if r else None
doc = {
    "expected_commit": expected_commit,
    "loadgen_commit": lines[0],
    "expected_md5": {f: hashlib.md5((tmp / f"expected-{f}").read_bytes()).hexdigest() for f in files},
    "loadgen_md5": loadgen_md5,
    "ssm_image": (tmp / "ssm-image.txt").read_text().strip(),
    "app_images": dict(l.split(" ", 1) for l in (tmp / "app-images.txt").read_text().splitlines() if l.strip()),
    "asg": json.loads((tmp / "asg.json").read_text()),
    "alb_healthy": int((tmp / "alb-healthy.txt").read_text().strip()),
    "kafka_lag": scalar("lag.json"),
    "hikari_pending": scalar("hikari.json"),
    "active_campaigns": sorted(int(x) for x in (tmp / "active.txt").read_text().split() if x.isdigit()),
}
out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({k: doc[k] for k in ("expected_commit", "loadgen_commit", "alb_healthy", "kafka_lag", "active_campaigns")}, ensure_ascii=False))
PY
