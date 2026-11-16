#!/usr/bin/env python3
"""evidence/<RUN_ID>/의 증거 파일을 읽어 판정(verdict.json, verdict.md)을 만든다.

사용법: python3 stress-test/harness/verdict.py <증거 디렉터리>

판정 규칙은 stress-test/TEST_GUIDE.md 8.3을 따른다.
- FAIL: 버전 불일치, 종료 후 Queue·in-flight·lag 잔량, drain 시간 초과, 접수·발행·DB 건수 불일치, 중복,
        integrity의 k6 실패·5xx
- INCONCLUSIVE: 필수 증거 누락, 실행 중 scale-out, 부하 미달(dropped_iterations),
        기준선 이상, 수집 오류, capacity 최종 검증의 기준 미합의·미달
- 탐색(explore)은 PASS를 내지 않는다. 문제가 없으면 VALID(관측값을 비교에 쓸 수 있음)다.
- 최종 검증(final)에서 문제가 없으면 PASS다.
- 장애 주입(fault)은 run.json의 fault.expected와 비교해 FAULT_DETECTED / FAULT_UNEXPECTED / FAULT_MISSED를 낸다.
표준 라이브러리만 사용한다(Python 3.9 이상).
"""
import json
import math
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
OK, FAIL, INCONCLUSIVE, NOTE = "OK", "FAIL", "INCONCLUSIVE", "NOTE"
K6_FILES = ("k6-load-test.js", "k6-tps-test.js", "run-test.sh")


class Missing(Exception):
    pass


def load_json(evid, name):
    path = evid / name
    if not path.is_file() or path.stat().st_size == 0:
        raise Missing(name)
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise Missing(f"{name} (JSON 파싱 실패: {e})")


def k6_count(metrics, name):
    # k6 summary-export는 한 번도 증가하지 않은 Counter를 내보내지 않는다.
    return int(metrics.get(name, {}).get("count", 0))


def read_redis_watch(evid):
    path = evid / "redis-watch.log"
    if not path.is_file():
        raise Missing("redis-watch.log")
    rows = []
    for line in path.read_text().splitlines():
        m = re.match(r"(\S+ \S+) inSet=(\S*) flag=(\S*) llen=(\d+)(?: inflight=(\d+))? stock=(\d*)", line)
        if m:
            ts = datetime.strptime(m[1], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST).timestamp()
            rows.append({
                "ts": ts, "llen": int(m[4]),
                "inflight": int(m[5]) if m[5] is not None else None,
                "stock": int(m[6]) if m[6] else None,
            })
    if not rows:
        raise Missing("redis-watch.log (기록 없음)")
    return rows


def read_db_counts(evid, live):
    path = evid / "db-counts.txt"
    if not path.is_file():
        raise Missing("db-counts.txt")
    text = path.read_text()
    values = {}
    duplicate_unit = "rows" if live else "groups"
    fields = ("total_rows", "success_count", f"dup_sequence_{duplicate_unit}", f"dup_user_{duplicate_unit}")
    if live:
        fields += ("first_sequence", "last_sequence", "dlq_rows")
    for key in fields:
        # mysql --table 출력: 헤더 행 다음의 값 행에서 같은 열 위치의 값을 읽는다.
        m = re.search(r"\|([^\n]*\b" + key + r"\b[^\n]*)\|\n\+[-+]+\+\n\|([^\n]*)\|", text)
        if not m:
            raise Missing(f"db-counts.txt ({key} 없음)")
        headers = [h.strip() for h in m[1].split("|")]
        cells = [c.strip() for c in m[2].split("|")]
        value = cells[headers.index(key)]
        values[key] = None if value == "NULL" else int(value)
    return values


def prom_scalar(doc):
    result = doc.get("data", {}).get("result", [])
    if not result:
        raise Missing("Prometheus 결과 비어 있음")
    try:
        value = float(result[0]["value"][1])
        if len(result) != 1 or not math.isfinite(value) or doc.get("status", "success") != "success":
            raise ValueError
        return value
    except (IndexError, KeyError, TypeError, ValueError) as e:
        raise Missing("Prometheus 단일 숫자 증거가 유효하지 않음") from e


def fmt_ts(epoch):
    return datetime.fromtimestamp(epoch, KST).strftime("%H:%M:%S")


def evaluate(evid):
    checks = []

    def add(cid, status, detail, source):
        checks.append({"id": cid, "status": status, "detail": detail, "source": source})

    try:
        run = load_json(evid, "run.json")
    except Missing as e:
        add("run", INCONCLUSIVE, f"실행 정보 없음: {e}", "run.json")
        return finish(evid, {}, checks)

    mode, purpose = run.get("mode"), run.get("purpose", "explore")
    if mode not in {"integrity", "capacity"}:
        add("run", INCONCLUSIVE, f"실행 모드를 확인할 수 없음: {mode}", "run.json")
        return finish(evid, run, checks)
    final_integrity = mode == "integrity" and purpose == "final"
    live = "harness_sha" in run
    interruptions = [
        (name, (evid / name).read_text().strip())
        for name in ("controller-interruption", "interruption") if (evid / name).is_file()
    ]
    interrupted = any(reason for _, reason in interruptions)
    for name, reason in interruptions:
        if reason:
            add("interruption", INCONCLUSIVE, reason, name)
    if live and (interrupted or run.get("k6_exit_code") not in (0, 99) or not run.get("drain_end_epoch")):
        if not interrupted:
            add("execution", INCONCLUSIVE, "k6 종료 코드 또는 원격 drain 완료 시각 누락", "run.json")
        # 중단 뒤에는 응답을 세지 못한 접수도 있을 수 있다. 부분 파일을 보존한다.
        return finish(evid, run, checks)

    # ---- 사전 점검 ----
    try:
        pre = load_json(evid, "precheck.json")
        expected, actual = pre["expected_md5"], pre["loadgen_md5"]
        diff = [f for f in K6_FILES if not expected.get(f) or expected.get(f) != actual.get(f)]
        if diff:
            add("pre_version", FAIL,
                f"부하 발생기 스크립트가 기준 커밋과 다름: {', '.join(diff)} "
                f"(부하 발생기 {pre.get('loadgen_commit')}, 기준 {pre.get('expected_commit')})",
                "precheck.json")
        else:
            add("pre_version", OK, f"부하 발생기 {pre.get('loadgen_commit')} 스크립트 md5 일치", "precheck.json")

        ssm_image = pre["ssm_image"]
        bad = {i: img for i, img in pre["app_images"].items() if img != ssm_image}
        if not pre["app_images"]:
            add("pre_containers", INCONCLUSIVE, "앱 컨테이너 이미지를 조회하지 못함", "precheck.json")
        elif bad:
            add("pre_containers", FAIL, f"SSM 이미지와 다른 컨테이너: {bad}", "precheck.json")
        else:
            add("pre_containers", OK, f"앱 {len(pre['app_images'])}대 모두 {ssm_image.rsplit(':', 1)[-1][:7]}", "precheck.json")

        problems = []
        if pre.get("kafka_lag") not in (0, 0.0):
            problems.append(f"Kafka lag {pre.get('kafka_lag')}")
        if pre.get("hikari_pending") not in (0, 0.0):
            problems.append(f"Hikari pending {pre.get('hikari_pending')}")
        asg = pre.get("asg", {})
        if pre.get("alb_healthy") != asg.get("desired"):
            problems.append(f"ALB healthy {pre.get('alb_healthy')} ≠ desired {asg.get('desired')}")
        if problems:
            add("pre_baseline", INCONCLUSIVE, "부하 전 기준선 이상: " + ", ".join(problems), "precheck.json")
        else:
            add("pre_baseline", OK, "부하 전 lag 0, Hikari pending 0, ALB healthy = desired", "precheck.json")
        if live and (not pre.get("checks") or not all(pre["checks"].values())):
            add("pre_aws", INCONCLUSIVE, "실환경 사전 점검을 통과하지 못함", "precheck.json")
        if pre.get("active_campaigns"):
            add("pre_active_set", NOTE, f"부하 전 active Set에 남은 캠페인: {pre['active_campaigns']}", "precheck.json")
    except (Missing, KeyError) as e:
        add("pre", INCONCLUSIVE, f"사전 점검 증거 누락: {e}", "precheck.json")

    if run.get("aborted"):
        add("run_aborted", NOTE, f"사전 점검 실패로 부하를 시작하지 않음: {run['aborted']}", "run.json")
        return finish(evid, run, checks)

    # ---- k6 ----
    accepted = None
    try:
        metrics = load_json(evid, "k6-summary.json")["metrics"]
        fivexx = k6_count(metrics, "participation_5xx")
        failed = metrics.get("http_req_failed", {}).get("passes", 0)
        if mode == "integrity":
            accepted = k6_count(metrics, "participation_success")
            k6_fail = k6_count(metrics, "participation_fail")
            total = run.get("total_requests")
            iterations = metrics.get("iterations", {}).get("count")
            if live and (iterations != total or k6_count(metrics, "dropped_iterations")):
                add("k6_target", INCONCLUSIVE,
                    f"목표 {total}, 완료 iteration {iterations}, 202 {accepted}", "k6-summary.json")
            elif accepted != total:
                add("k6_target", FAIL, f"participation_success {accepted} ≠ TOTAL_REQUESTS {total}", "k6-summary.json")
            else:
                add("k6_target", OK, f"participation_success {accepted}", "k6-summary.json")
            if fivexx or k6_fail or failed:
                add("k6_errors", FAIL, f"5xx {fivexx}, participation_fail {k6_fail}, http_req_failed {failed}", "k6-summary.json")
            else:
                add("k6_errors", OK, "5xx 0, participation_fail 0, http_req_failed 0", "k6-summary.json")
            latency = metrics.get("http_req_duration", {})
            limits = latency.get("thresholds", {})
            if "p(95)<1000" in limits:
                latency_result = "FAIL" if limits["p(95)<1000"] else "PASS"
                add("client_latency", NOTE,
                    f"k6 p95 1초 기준 {latency_result} (p95 {latency.get('p(95)')}ms, p99 {latency.get('p(99)')}ms)",
                    "k6-summary.json")
        else:
            accepted = k6_count(metrics, "participation_accepted_202")
            dropped = k6_count(metrics, "dropped_iterations")
            if dropped:
                add("k6_target", INCONCLUSIVE, f"dropped_iterations {dropped}: 목표 부하 미달", "k6-summary.json")
            else:
                add("k6_target", OK, f"dropped_iterations 0, 202 {accepted}", "k6-summary.json")
            errors = k6_count(metrics, "participation_error")
            detail = f"5xx {fivexx}, 예상 밖 응답 {errors}, http_req_failed {failed}"
            if purpose == "final":
                crit_path = evid / "criteria.json"
                if not crit_path.is_file():
                    add("k6_errors", INCONCLUSIVE, f"{detail}; 사전 합의 기준(criteria.json) 없음", "k6-summary.json")
                else:
                    crit = json.loads(crit_path.read_text())
                    p95 = metrics["http_req_duration"]["p(95)"]
                    rate = metrics.get("http_req_failed", {}).get("value", 0)
                    ok = p95 <= crit["p95_ms"] and rate <= crit["max_fail_rate"] and fivexx == 0
                    add("k6_errors", OK if ok else FAIL,
                        f"{detail}, p95 {p95:.1f}ms (기준 {crit['p95_ms']}), 실패율 {rate} (기준 {crit['max_fail_rate']})",
                        "k6-summary.json, criteria.json")
            else:
                add("k6_errors", NOTE, detail, "k6-summary.json")
    except Missing as e:
        add("k6", INCONCLUSIVE, f"k6 결과 누락: {e}", "k6-summary.json")

    # ---- Redis Queue ----
    try:
        rows = read_redis_watch(evid)
        must_start = run["load_start_epoch"] if mode == "integrity" else run.get("steady_start_epoch", run["load_start_epoch"])
        if rows[0]["ts"] > must_start:
            add("queue_watch", INCONCLUSIVE,
                f"Queue 감시 시작 {fmt_ts(rows[0]['ts'])}이 기준 시각 {fmt_ts(must_start)}보다 늦음",
                "redis-watch.log")
        else:
            add("queue_watch", OK, f"Queue 감시 {fmt_ts(rows[0]['ts'])}부터 {len(rows)}건 기록", "redis-watch.log")
        last = rows[-1]
        peak = max(r["llen"] for r in rows)
        if live and last["ts"] < run.get("load_end_epoch", float("inf")):
            raise Missing("부하 종료 이후 Redis 표본 없음")
        if last["llen"] != 0:
            add("queue_final", FAIL, f"마지막 기록 {fmt_ts(last['ts'])}의 LLEN {last['llen']} (최대 {peak})", "redis-watch.log")
        else:
            add("queue_final", OK, f"마지막 기록 {fmt_ts(last['ts'])} LLEN 0 (최대 {peak})", "redis-watch.log")
        if last["inflight"] is not None:
            remaining = last["inflight"]
            peak_inflight = max(row["inflight"] for row in rows if row["inflight"] is not None)
            add("inflight_final", FAIL if remaining else OK,
                f"마지막 기록 {fmt_ts(last['ts'])} ZCARD {remaining} (최대 {peak_inflight})", "redis-watch.log")
        elif final_integrity:
            add("inflight_final", INCONCLUSIVE, "종료 in-flight 증거 누락", "redis-watch.log")
    except (Missing, KeyError) as e:
        add("queue", INCONCLUSIVE, f"Queue 증거 누락: {e}", "redis-watch.log")

    try:
        drain = load_json(evid, "drain.json")
        if drain.get("drained") is True:
            add("drain", OK, f"부하 종료 후 {drain.get('waited_seconds')}초 안에 Queue·in-flight·lag 0", "drain.json")
        elif drain.get("drained") is None:
            add("drain", INCONCLUSIVE, "제한 시간 안에 연속 배출 완료를 확인하지 못함", "drain.json")
        else:
            add("drain", FAIL,
                f"{drain.get('waited_seconds')}초 안에 drain 안 됨 (마지막 LLEN {drain.get('last_llen')}, lag {drain.get('last_lag')})",
                "drain.json")
    except Missing as e:
        add("drain", INCONCLUSIVE, f"drain 확인 누락: {e}", "drain.json")

    if live:
        try:
            peaks = {}
            for line in (evid / "redis-memory.tsv").read_text().splitlines():
                _, node, used = line.split()
                peaks[node] = max(peaks.get(node, 0), int(used))
            if len(peaks) != run.get("redis_primaries"):
                raise ValueError("primary 메모리 표본 수 불일치")
            add("redis_memory", OK,
                f"primary별 최대 관측값 {', '.join(str(v) for v in sorted(peaks.values(), reverse=True))} bytes "
                f"(중단 기준 {run.get('redis_guard_bytes')} bytes)", "redis-memory.tsv")
        except (OSError, ValueError) as e:
            add("redis_memory", INCONCLUSIVE, f"Redis 메모리 기록 확인 불가: {e}", "redis-memory.tsv")

    # ---- Kafka ----
    try:
        lag = prom_scalar(load_json(evid, "kafka-lag-final.json"))
        add("kafka_final", OK if lag == 0 else FAIL, f"종료 후 consumer group lag {lag:.0f}", "kafka-lag-final.json")
    except Missing as e:
        add("kafka_final", INCONCLUSIVE, f"Kafka lag 누락: {e}", "kafka-lag-final.json")

    if final_integrity:
        try:
            published = prom_scalar(load_json(evid, "bridge-published-final.json"))
            if live and prom_scalar(load_json(evid, "bridge-counter-resets.json")) != 0:
                raise Missing("측정 구간의 Bridge counter reset을 확인함")
            if not published.is_integer() or published < 0:
                raise Missing("Kafka 발행 건수가 유효하지 않음")
            if accepted is None:
                raise Missing("k6 202 결과 없음")
            add("bridge_published", OK if published == accepted else FAIL,
                f"캠페인 Kafka 발행 {published:g}, k6 202 {accepted}", "bridge-published-final.json")
        except (Missing, KeyError, IndexError, TypeError, ValueError) as e:
            add("bridge_published", INCONCLUSIVE, f"Kafka 발행 증거 누락 또는 파싱 실패: {e}",
                "bridge-published-final.json")

    # ---- DB·상태 API ----
    try:
        db = read_db_counts(evid, live)
        status = load_json(evid, "status.json")["data"]
        api_success = status.get("successCount")
        counts = {"k6 202": accepted, "status API": api_success, "DB SUCCESS": db["success_count"]}
        if accepted is None:
            add("db_counts", INCONCLUSIVE, f"k6 접수 건수가 없어 비교 불가: {counts}", "db-counts.txt, status.json")
        elif len(set(counts.values())) != 1 or db["total_rows"] != db["success_count"]:
            add("db_counts", FAIL, f"건수 불일치: {counts}, DB 전체 행 {db['total_rows']}", "db-counts.txt, status.json")
        else:
            add("db_counts", OK, f"k6 202 = status API = DB SUCCESS = {accepted}", "db-counts.txt, status.json")
        duplicate_unit = "rows" if live else "groups"
        dup_sequences, dup_users = (db[f"dup_{key}_{duplicate_unit}"] for key in ("sequence", "user"))
        add("db_duplicates", FAIL if dup_sequences or dup_users else OK,
            f"sequence 중복 {dup_sequences}, user 중복 {dup_users}", "db-counts.txt")
        if live:
            first, last = db["first_sequence"], db["last_sequence"]
            if db["total_rows"]:
                continuous = first == 1 and last == db["total_rows"] and dup_sequences == 0
                add("db_sequence_range", OK if continuous else FAIL,
                    f"sequence {first}~{last}, DB 전체 행 {db['total_rows']}", "db-counts.txt")
            if status.get("failCount") is None:
                add("status_fail", INCONCLUSIVE, "상태 API failCount 없음", "status.json")
            else:
                add("status_fail", FAIL if status["failCount"] else OK, f"failCount {status['failCount']}", "status.json")
            add("db_dlq", FAIL if db["dlq_rows"] else OK, f"캠페인 DLQ 행 {db['dlq_rows']}", "db-counts.txt")
        if mode == "integrity" and status.get("currentStock") != 0:
            add("stock_final", FAIL, f"종료 후 재고 {status.get('currentStock')}", "status.json")
    except (Missing, KeyError) as e:
        add("db", INCONCLUSIVE, f"DB·상태 증거 누락: {e}", "db-counts.txt, status.json")

    # ---- 정합성 dry-run (TEST_GUIDE 8.2: 최종 검증 필수). anomalyCount는 4.4 기준이 아니므로 유형만 기록한다(8.3). ----
    try:
        cons = load_json(evid, "consistency.json")["data"]
        exe = cons["execution"]
        if exe.get("status") != "COMPLETED":
            add("consistency", INCONCLUSIVE, f"정합성 dry-run 상태 {exe.get('status')}", "consistency.json")
        else:
            kinds = sorted({f"{r.get('anomalyType')}({r.get('severity')})" for r in cons.get("results", []) if r.get("anomalyType")})
            add("consistency", NOTE if exe.get("anomalyCount") else OK,
                f"dry-run 실행 {exe.get('id')}, anomalyCount {exe.get('anomalyCount')}" + (f": {', '.join(kinds)}" if kinds else ""),
                "consistency.json")
    except (Missing, KeyError, TypeError) as e:
        if purpose == "final":
            add("consistency", INCONCLUSIVE, f"정합성 dry-run 결과 누락: {e}", "consistency.json")

    # ---- 실행 중 변경 ----
    try:
        acts = load_json(evid, "scaling.json")
        if live:
            acts += load_json(evid, "worker-scaling.json")
        # 중간에 중단된 실행은 끝 시각이 없으므로 판정 시각까지 본다.
        start = run["load_start_epoch"]
        end = run.get("drain_end_epoch") or run.get("load_end_epoch") or datetime.now(timezone.utc).timestamp()
        during = [a for a in acts
                  if start <= datetime.fromisoformat(a["StartTime"].replace("Z", "+00:00")).timestamp() <= end]
        if during:
            add("scaling", INCONCLUSIVE, "실행 중 scaling activity: " + "; ".join(a["Description"] for a in during), "scaling.json")
        else:
            add("scaling", OK, "실행 중 scaling activity 없음", "scaling.json")
    except (Missing, KeyError) as e:
        add("scaling", INCONCLUSIVE, f"scaling 기록 누락: {e}", "scaling.json")

    errors_path = evid / "collect-errors.txt"
    if errors_path.is_file() and errors_path.read_text().strip():
        add("collect", INCONCLUSIVE, "수집 오류: " + errors_path.read_text().strip().replace("\n", "; "), "collect-errors.txt")

    return finish(evid, run, checks)


def finish(evid, run, checks):
    statuses = {c["status"] for c in checks}
    if FAIL in statuses:
        verdict = FAIL
    elif INCONCLUSIVE in statuses:
        verdict = INCONCLUSIVE
    elif run.get("aborted"):
        verdict = INCONCLUSIVE
    else:
        verdict = "PASS" if run.get("purpose") == "final" else "VALID"
    result = {"run_id": evid.name, "mode": run.get("mode"), "purpose": run.get("purpose"),
              "verdict": verdict, "checks": checks}
    if "harness_sha" in run:
        result["conditions"] = {key: run.get(key) for key in (
            "expected_app_sha", "loadgen_sha", "harness_sha", "start_condition", "total_requests", "max_vus"
        )}
    if run.get("purpose") == "fault":
        result.update(judge_fault(run.get("fault", {}), checks, verdict))
    return result


def judge_fault(fault, checks, raw_verdict):
    """일부러 낸 장애를 하네스가 기대대로 잡았는지 판정한다.

    fault.expected: {"항목 id": "FAIL" 또는 "INCONCLUSIVE"}. 장애 실행 전에 정한다.
    - FAULT_DETECTED: 기대 항목이 모두 기대 상태이고, 그 밖의 항목에 FAIL·INCONCLUSIVE가 없음
    - FAULT_UNEXPECTED: 기대대로 잡았지만 다른 항목도 FAIL·INCONCLUSIVE (원인 확인 필요)
    - FAULT_MISSED: 기대 항목 중 하나라도 기대 상태가 아님 (하네스가 장애를 놓침)
    """
    expected = fault.get("expected") or {}
    by_id = {c["id"]: c["status"] for c in checks}
    if not expected:
        missed, unexpected = ["fault.expected 없음"], []
    else:
        missed = [f"{cid}: 기대 {want}, 실제 {by_id.get(cid, '없음')}"
                  for cid, want in expected.items() if by_id.get(cid) != want]
        unexpected = [f"{c['id']}: {c['status']}" for c in checks
                      if c["status"] in (FAIL, INCONCLUSIVE) and c["id"] not in expected]
    if missed:
        verdict = "FAULT_MISSED"
    elif unexpected:
        verdict = "FAULT_UNEXPECTED"
    else:
        verdict = "FAULT_DETECTED"
    return {"verdict": verdict, "raw_verdict": raw_verdict, "fault": fault,
            "fault_missed": missed, "fault_unexpected": unexpected}


def write(evid, result):
    (evid / "verdict.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    lines = [f"# 판정: {result['verdict']}", ""]
    if result.get("purpose") == "fault":
        fault = result.get("fault", {})
        lines += ["> **장애 주입 실행이다.** 시스템 성능·정합성 기록이나 실행 간 비교에 쓰지 않는다.", "",
                  f"- 주입한 장애: {fault.get('description', '(기록 없음)')}",
                  f"- 기대 판정: {fault.get('expected')}",
                  f"- 장애 표시를 빼고 판정한 결과: {result.get('raw_verdict')}"]
        for label, key in (("기대와 다른 항목", "fault_missed"), ("기대 밖 FAIL·INCONCLUSIVE", "fault_unexpected")):
            if result.get(key):
                lines.append(f"- {label}: " + "; ".join(result[key]))
    lines += [f"- RUN_ID: `{result['run_id']}`",
              f"- 모드·목적: {result['mode']} / {result['purpose']}",
              "- 기준: stress-test/TEST_GUIDE.md 8.3 (탐색은 PASS 대신 VALID)"]
    lines.extend(f"- {key}: `{value}`" for key, value in result.get("conditions", {}).items())
    lines += ["", "| 항목 | 결과 | 근거 | 출처 |", "| --- | --- | --- | --- |"]
    for c in result["checks"]:
        lines.append(f"| {c['id']} | {c['status']} | {c['detail']} | `{c['source']}` |")
    (evid / "verdict.md").write_text("\n".join(lines) + "\n")


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    evid = Path(sys.argv[1])
    try:
        result = evaluate(evid)
    except (AttributeError, KeyError, IndexError, TypeError, ValueError) as e:
        check = {"id": "invalid_evidence", "status": INCONCLUSIVE, "detail": str(e), "source": "evidence"}
        result = finish(evid, {}, [check])
    write(evid, result)
    print(f"판정: {result['verdict']}")
    for c in result["checks"]:
        if c["status"] != OK:
            print(f"  [{c['status']}] {c['id']}: {c['detail']}")
    return {"FAIL": 1, "INCONCLUSIVE": 2, "FAULT_MISSED": 1, "FAULT_UNEXPECTED": 2}.get(result["verdict"], 0)


if __name__ == "__main__":
    sys.exit(main())
