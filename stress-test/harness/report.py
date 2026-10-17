#!/usr/bin/env python3
"""증거 디렉터리에서 보고서 수치 초안(report-draft.md)을 만든다.

사용법: python3 stress-test/harness/report.py <증거 디렉터리> [안정 구간 시작 HH:MM:SS 끝 HH:MM:SS]

- 수치는 증거 파일에서 그대로 계산한다(옮겨 적기 오류 방지). 각 표에 출처 파일을 적는다.
- 안정 구간 기본값: capacity는 워밍업 끝 + 60초(rate[1m] 창이 채워지는 시간)부터 부하 끝 - 15초까지,
  integrity는 부하 시작부터 끝까지.
- 해석·결론·한계·다음 계획은 쓰지 않는다. 사람이나 에이전트가 표를 보고 채운다.
표준 라이브러리만 사용한다(Python 3.9 이상).
"""
import json
import re
import statistics as st
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import verdict as V  # noqa: E402

KST = timezone(timedelta(hours=9))


def hms(epoch):
    return datetime.fromtimestamp(epoch, KST).strftime("%H:%M:%S")


def num(v, digits=1):
    if v is None:
        return "없음"
    if isinstance(v, int) or float(v).is_integer():
        return f"{int(v):,}"
    return f"{v:,.{digits}f}"


def load(evid, name):
    try:
        return V.load_json(evid, name)
    except V.Missing:
        return None


def steady_window(run, args, day):
    if len(args) == 2:
        f = lambda t: datetime.strptime(f"{day} {t}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST).timestamp()
        return f(args[0]), f(args[1]), "지정"
    start, end = run.get("load_start_epoch"), run.get("load_end_epoch")
    if run.get("mode") == "capacity" and run.get("steady_start_epoch"):
        return run["steady_start_epoch"] + 60, (end or 0) - 15, "기본 (워밍업 끝 + 60초 ~ 부하 끝 - 15초)"
    return start, end, "기본 (부하 시작 ~ 끝)"


def prom_series(evid, name, t0, t1):
    """[(라벨, [값...])]: 안정 구간 안의 표본만."""
    doc = load(evid, f"prometheus/{name}.json")
    if not doc:
        return None
    out = []
    for r in doc.get("data", {}).get("result", []):
        vals = [float(v) for t, v in r["values"] if t0 <= float(t) <= t1 and v not in ("NaN", "+Inf", "-Inf")]
        if vals:
            out.append((r["metric"].get("instance", "전체"), vals))
    return out


def prom_max_all(evid, name):
    doc = load(evid, f"prometheus/{name}.json")
    if not doc:
        return None
    vals = [float(v) for r in doc["data"]["result"] for _, v in r["values"] if v not in ("NaN", "+Inf", "-Inf")]
    return max(vals) if vals else None


def slope(points):
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    mx, my = st.mean(xs), st.mean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def build(evid, args=()):
    run = load(evid, "run.json") or {}
    pre = load(evid, "precheck.json") or {}
    verdict = load(evid, "verdict.json") or V.evaluate(evid)
    day = datetime.fromtimestamp(run.get("load_start_epoch", 0), KST).strftime("%Y-%m-%d")
    t0, t1, how = steady_window(run, list(args), day)
    L = []
    w = L.append

    w(f"# 보고서 초안: `{evid.name}`")
    w("")
    w("> `stress-test/harness/report.py`가 증거 파일에서 만든 수치 초안이다. **해석·결론·한계는 아직 쓰지 않았다.**")
    w("> 수치를 고치지 말고, 다른 구간이 필요하면 `report.py <디렉터리> <시작> <끝>`으로 다시 만든다.")
    w("")

    w("## 판정")
    w("")
    w(f"- **{verdict['verdict']}** (`verdict.md`)")
    for c in verdict["checks"]:
        if c["status"] != V.OK:
            w(f"- [{c['status']}] {c['id']}: {c['detail']}")
    if verdict.get("purpose") == "fault":
        w("- 장애 주입 실행이다. 성능·정합성 기록이나 비교에 쓰지 않는다.")
    w("")

    w("## 실행 조건 (`run.json`, `precheck.json`)")
    w("")
    asg = pre.get("asg", {})
    rows = [
        ("모드·목적", f"{run.get('mode')} / {run.get('purpose')}"),
        ("캠페인", run.get("campaign_id")),
        ("부하", f"TARGET_RPS {run['target_rps']}, 워밍업 {run.get('warmup_seconds')}초, 유지 {run.get('steady_seconds')}초"
         if run.get("mode") == "capacity" else f"TOTAL_REQUESTS {num(run.get('total_requests'))}, MAX_VUS {run.get('max_vus')}"),
        ("부하 시각 (KST)", f"{hms(run['load_start_epoch'])} ~ {hms(run['load_end_epoch'])}"
         if run.get("load_start_epoch") and run.get("load_end_epoch") else "기록 없음"),
        ("앱 이미지", (pre.get("ssm_image") or "없음").rsplit(":", 1)[-1][:12]),
        ("부하 발생기 커밋", f"{pre.get('loadgen_commit')} (기준 {pre.get('expected_commit')})"),
        ("ASG", f"min {asg.get('min')} / desired {asg.get('desired')} / max {asg.get('max')}, InService {asg.get('in_service')}, {asg.get('instance_type')}"),
        ("부하 전 active Set", pre.get("active_campaigns")),
    ]
    w("| 항목 | 값 |")
    w("| --- | --- |")
    for k, v in rows:
        w(f"| {k} | {v} |")
    w("")

    k6 = load(evid, "k6-summary.json")
    w("## k6 (전체 실행, `k6-summary.json`)")
    w("")
    if k6:
        m = k6["metrics"]
        acc = "participation_success" if run.get("mode") == "integrity" else "participation_accepted_202"
        d = m.get("http_req_duration", {})
        w("| 항목 | 값 |")
        w("| --- | --- |")
        w(f"| 202 접수 | {num(V.k6_count(m, acc))} |")
        w(f"| 5xx / http_req_failed | {num(V.k6_count(m, 'participation_5xx'))} / {num(m.get('http_req_failed', {}).get('passes', 0))} |")
        if run.get("mode") == "integrity":
            w(f"| participation_fail | {num(V.k6_count(m, 'participation_fail'))} |")
        else:
            w(f"| dropped_iterations | {num(V.k6_count(m, 'dropped_iterations'))} |")
        w(f"| 클라이언트 지연 p95 / 최대 | {num(d.get('p(95)'))} / {num(d.get('max'))} ms |")
        w(f"| 최대 VU | {num(m.get('vus_max', {}).get('value'))} |")
        w("")
        w("k6 요약에는 p99가 없다. 클라이언트 지연은 전체 실행 기준이며 안정 구간 값이 아니다.")
    else:
        w("k6 결과 없음.")
    w("")

    w(f"## 안정 구간 처리율·지연 ({hms(t0)} ~ {hms(t1)} KST, {how})")
    w("")
    w("Prometheus 10초 step 표본의 평균 / 최소 / 최대. 쿼리 원문은 `prometheus/queries.tsv`.")
    w("")
    w("| 지표 | 평균 | 최소 | 최대 | 출처 |")
    w("| --- | ---: | ---: | ---: | --- |")
    labels = [("api_202_rps", "API 202 (건/s)"), ("bridge_published_rps", "Bridge 발행 (건/s)"),
              ("db_committed_rps", "DB 성공 경로 (건/s)"), ("api_p95_ms", "서버 p95 (ms)"),
              ("api_p99_ms", "서버 p99 (ms)"), ("queue_gauge", "Queue Gauge (건)"),
              ("kafka_lag", "Kafka lag"), ("hikari_pending_max", "Hikari pending 최대"),
              ("cpu_by_instance", "앱 CPU (호스트, 0~1)")]
    any_prom = False
    for name, label in labels:
        series = prom_series(evid, name, t0, t1)
        if series is None:
            continue
        any_prom = True
        for inst, vals in series:
            lab = label if inst == "전체" else f"{label} {inst}"
            w(f"| {lab} | {num(st.mean(vals))} | {num(min(vals))} | {num(max(vals))} | `prometheus/{name}.json` |")
    if not any_prom:
        w("| (Prometheus 증거 없음) | | | | |")
    w("")
    w("`consumer_db_committed_total`(DB 성공 경로)은 물리 INSERT 수가 아니다. 최종 건수는 아래 DB 직접 조회를 본다.")
    w("")

    w("## Queue (`redis-watch.log`, 1초 단위 직접 `LLEN`)")
    w("")
    try:
        rows_ = V.read_redis_watch(evid)
        steady = [(r["ts"], r["llen"]) for r in rows_ if t0 <= r["ts"] <= t1]
        peak = max(rows_, key=lambda r: r["llen"])
        last_nz = max((r["ts"] for r in rows_ if r["llen"] > 0), default=None)
        w("| 항목 | 값 |")
        w("| --- | --- |")
        w(f"| 감시 구간 | {hms(rows_[0]['ts'])} ~ {hms(rows_[-1]['ts'])} ({len(rows_)}건) |")
        w(f"| 전체 최대 | {num(peak['llen'])}건 ({hms(peak['ts'])}) |")
        if steady:
            w(f"| 안정 구간 평균 / 최대 | {num(st.mean(p[1] for p in steady))} / {num(max(p[1] for p in steady))}건 |")
            w(f"| 안정 구간 기울기 (선형회귀) | {slope(steady):+.3f}건/s |")
        w(f"| 마지막 LLEN > 0 | {hms(last_nz) if last_nz else '없음'} |")
        w(f"| 마지막 기록 LLEN | {num(rows_[-1]['llen'])} |")
    except V.Missing as e:
        w(f"Queue 증거 없음: {e}")
    w("")

    w("## drain·lag·DB")
    w("")
    w("| 항목 | 값 | 출처 |")
    w("| --- | --- | --- |")
    drain = load(evid, "drain.json")
    if drain:
        w(f"| drain | {'완료' if drain.get('drained') else '미완료'} ({drain.get('waited_seconds')}초, 마지막 LLEN {drain.get('last_llen')}, lag {drain.get('last_lag')}) | `drain.json` |")
    lag_max = prom_max_all(evid, "kafka_lag")
    lag_final = load(evid, "kafka-lag-final.json")
    w(f"| Kafka lag 구간 최대 / 종료 후 | {num(lag_max)} / {num(V.prom_scalar(lag_final)) if lag_final else '없음'} | `kafka-lag-range.json`, `kafka-lag-final.json` |")
    status = load(evid, "status.json")
    if status:
        s = status["data"]
        w(f"| 상태 API successCount / failCount / currentStock | {num(s.get('successCount'))} / {num(s.get('failCount'))} / {num(s.get('currentStock'))} | `status.json` |")
    try:
        db = V.read_db_counts(evid)
        w(f"| DB 전체 행 / SUCCESS | {num(db['total_rows'])} / {num(db['success_count'])} | `db-counts.txt` |")
        w(f"| sequence 중복 / user 중복 (그룹 수) | {db['dup_sequence_groups']} / {db['dup_user_groups']} | `db-counts.txt` |")
    except V.Missing as e:
        w(f"| DB | 없음 ({e}) | |")
    w("")

    logs = sorted(evid.glob("app-*.errwarn.log"))
    if logs:
        w("## 앱 로그 (테스트 구간 ERROR/WARN/Exception 줄 수)")
        w("")
        for p in logs:
            n = sum(1 for line in p.read_text().splitlines() if line.strip())
            w(f"- `{p.name}`: {n}줄")
        w("")

    w("## 해석 (작성 필요)")
    w("")
    w("- 결론: 위 수치가 무엇을 보여 주는가. 판정 범위(탐색/최종)를 넘어서 주장하지 않는다.")
    w("- 이전 실행과 비교: 조건 차이(`manifest`·`run.json`)를 먼저 적고, 다른 조건이면 비교하지 않는다.")
    w("- 한계: 반복 횟수, 측정하지 않은 지표(접수→DB 지연 등), 증거 누락.")
    w("- 다음 계획")
    return "\n".join(L) + "\n"


def main():
    if len(sys.argv) not in (2, 4):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    evid = Path(sys.argv[1])
    text = build(evid, sys.argv[2:])
    (evid / "report-draft.md").write_text(text)
    print(f"작성: {evid / 'report-draft.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
