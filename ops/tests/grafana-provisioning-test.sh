#!/usr/bin/env bash
# Run the actual provisioning script with umask 077 and mocked sudo/docker.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
printf '#!/bin/sh\nexec "$@"\n' > "$tmp/bin/sudo"
printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/docker"
chmod +x "$tmp/bin/sudo" "$tmp/bin/docker"
(
 umask 077
 PATH="$tmp/bin:$PATH" MONITORING_ROOT="$tmp/monitoring" \
  bash "$repo/app/campaign-core/monitoring/grafana/apply-provisioning.sh" >/dev/null
)
python3 - "$tmp/monitoring/grafana/provisioning/dashboards" "$repo/app/campaign-core/monitoring/grafana/dashboards/campaign.json" <<'PY'
from pathlib import Path
import sys,stat,json
root=Path(sys.argv[1]);source=Path(sys.argv[2])
for file in [root/'dashboard.yml',root/'json/campaign.json']:
 assert stat.S_IMODE(file.stat().st_mode)==0o644, file
for directory in [root,root/'json']:
 assert stat.S_IMODE(directory.stat().st_mode)==0o755, directory
assert (root/'json/campaign.json').read_bytes()==source.read_bytes()
assert 'path: /etc/grafana/provisioning/dashboards/json' in (root/'dashboard.yml').read_text()
json.loads((root/'json/campaign.json').read_text())
print('PASS: non-secret provider/dashboard readable under umask 077; content preserved')
PY
