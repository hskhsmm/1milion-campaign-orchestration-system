#!/usr/bin/env python3
"""Create a local, non-executing manifest for one load-test run."""

import argparse
import json
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ALLOWED_INFRASTRUCTURE = {
    "app_instance_count", "app_instance_type", "ami_id", "deployed_image",
    "kafka_partitions", "redis_node_type", "rds_instance_class",
}
ALLOWED_PARAMETERS = {
    "integrity": {
        "campaign_id", "total_requests", "max_vus", "duration_seconds",
        "user_id_offset", "request_timeout",
    },
    "capacity": {
        "target_rps", "rps_stages", "start_rps", "stage_seconds",
        "step_ramp_seconds", "steady_seconds", "pre_allocated_vus",
        "max_vus", "campaign_stock", "p95_ms", "max_fail_rate",
    },
}


def positive_int(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {
        "environment", "mode", "parameters", "infrastructure"
    }:
        raise ValueError("config must contain only environment, mode, parameters, infrastructure")
    if config["environment"] not in ("local", "prod"):
        raise ValueError("environment must be local or prod")
    mode = config["mode"]
    if mode not in ALLOWED_PARAMETERS:
        raise ValueError("mode must be integrity or capacity")
    params = config["parameters"]
    infra = config["infrastructure"]
    if not isinstance(params, dict) or not isinstance(infra, dict):
        raise ValueError("parameters and infrastructure must be objects")
    if unknown := set(params) - ALLOWED_PARAMETERS[mode]:
        raise ValueError(f"unsupported parameters: {', '.join(sorted(unknown))}")
    if unknown := set(infra) - ALLOWED_INFRASTRUCTURE:
        raise ValueError(f"unsupported infrastructure fields: {', '.join(sorted(unknown))}")
    if mode == "integrity":
        for key in ("campaign_id", "total_requests"):
            positive_int(params.get(key), key)
    else:
        positive_int(params.get("campaign_stock"), "campaign_stock")
        if "rps_stages" in params:
            stages = params["rps_stages"]
            if not isinstance(stages, list) or not stages:
                raise ValueError("rps_stages must be a non-empty list")
            for index, value in enumerate(stages):
                positive_int(value, f"rps_stages[{index}]")
        else:
            positive_int(params.get("target_rps"), "target_rps")
    for key in ("app_instance_count", "kafka_partitions"):
        if key in infra:
            positive_int(infra[key], key)


def git_sha():
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True,
        text=True, check=True
    )
    return result.stdout.strip()


def create_manifest(config, now=None, run_id=None):
    validate_config(config)
    timestamp = now or datetime.now(timezone.utc)
    return {
        "schema_version": 1,
        "run_id": run_id or f"{timestamp:%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}",
        "created_at_utc": timestamp.isoformat().replace("+00:00", "Z"),
        "git_sha": git_sha(),
        "status": "PREPARED",
        **config,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="allowlisted run configuration JSON")
    parser.add_argument(
        "--output-dir", type=Path, default=Path(__file__).parent / "runs",
        help="directory for ignored, per-run artifacts",
    )
    args = parser.parse_args()
    try:
        config = json.loads(args.config.read_text(encoding="utf-8"))
        manifest = create_manifest(config)
        run_dir = args.output_dir / manifest["run_id"]
        run_dir.mkdir(parents=True, exist_ok=False)
        output = run_dir / "manifest.json"
        with output.open("x", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except (OSError, ValueError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print(output)


if __name__ == "__main__":
    main()
