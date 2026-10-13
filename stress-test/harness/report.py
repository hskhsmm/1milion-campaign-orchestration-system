#!/usr/bin/env python3
"""Summarize an existing k6 JSON export without claiming end-to-end success."""

import argparse
import json
import math
from pathlib import Path


def metric_value(metrics, name, field):
    metric = metrics.get(name)
    if not isinstance(metric, dict) or not isinstance(metric.get("values"), dict):
        return None
    value = metric["values"].get(field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not math.isfinite(value) or value < 0:
        return None
    return value


def summarize(manifest, summary):
    if manifest.get("schema_version") != 1 or manifest.get("status") != "PREPARED":
        raise ValueError("expected a schema v1 PREPARED manifest")
    if not isinstance(summary, dict) or not isinstance(summary.get("metrics"), dict):
        raise ValueError("expected a k6 summary object with metrics")
    mode = manifest.get("mode")
    if mode not in ("integrity", "capacity"):
        raise ValueError("unsupported manifest mode")

    metrics = summary["metrics"]
    required = {
        "iterations": ("iterations", "count"),
        "dropped_iterations": ("dropped_iterations", "count"),
        "http_failure_rate": ("http_req_failed", "rate"),
    }
    if mode == "integrity":
        required.update({
            "accepted_202": ("participation_success", "count"),
            "non_202": ("participation_fail", "count"),
            "api_p95_ms_all_phases": ("http_req_duration", "p(95)"),
            "api_p99_ms_all_phases": ("http_req_duration", "p(99)"),
        })
    else:
        required.update({
            "accepted_202": ("participation_accepted_202", "count"),
            "rejected_400": ("participation_rejected_400", "count"),
            "rate_limited_429": ("participation_rate_limited_429", "count"),
            "other_responses": ("participation_error", "count"),
            "api_p95_ms_all_phases": ("api_duration_ms", "p(95)"),
            "api_p99_ms_all_phases": ("api_duration_ms", "p(99)"),
        })

    observations = {key: metric_value(metrics, *spec) for key, spec in required.items()}
    missing = [key for key, value in observations.items() if value is None]
    violations = []
    if observations["dropped_iterations"] is not None and observations["dropped_iterations"] > 0:
        violations.append("load_generator_dropped_iterations")
    if mode == "integrity":
        expected = manifest["parameters"]["total_requests"]
        if observations["iterations"] is not None and observations["iterations"] != expected:
            violations.append("iteration_count_mismatch")
        if observations["accepted_202"] is not None and observations["accepted_202"] != expected:
            violations.append("accepted_count_mismatch")
        if observations["non_202"] is not None and observations["non_202"] > 0:
            violations.append("non_202_responses")
    elif observations["other_responses"] is not None and observations["other_responses"] > 0:
        violations.append("unexpected_responses")

    return {
        "schema_version": 1,
        "run_id": manifest["run_id"],
        "mode": mode,
        "status": "FAIL" if violations else "INCONCLUSIVE",
        "scope": "k6_all_phases_only",
        "observations": observations,
        "missing_k6_metrics": missing,
        "violations": violations,
        "unverified": [
            "steady_state_p95_p99", "http_5xx_count", "redis_queue_drain",
            "kafka_lag_drain", "db_new_rows_and_consistency", "end_to_end_latency",
            "deployment_and_infrastructure_match",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--k6-summary", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = json.loads((args.run_dir / "manifest.json").read_text(encoding="utf-8"))
        summary = json.loads(args.k6_summary.read_text(encoding="utf-8"))
        report = summarize(manifest, summary)
        output = args.run_dir / "report.json"
        with output.open("x", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    print(output)


if __name__ == "__main__":
    main()
