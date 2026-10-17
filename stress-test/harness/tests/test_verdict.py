"""verdict.py 시험: 정상 실행 3건과 고장 시나리오.

정상 fixture는 2026-10-17 실환경 capacity 실행(500·750·1,000 RPS)의 실제 증거다.
run.json·precheck.json·drain.json·scaling.json은 당시 수동 실행에 없던 파일이라
manifest.md 기록에서 재구성했다(각 파일의 "reconstructed" 항목).
고장 시나리오는 정상 fixture를 복사해 한 가지만 바꾼다. 과거 사건을 재현한 경우 출처 문서를 적는다.

실행: python3 -m unittest discover -s stress-test/harness/tests
"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import verdict  # noqa: E402

FIXTURES = HERE / "fixtures"
BASE = FIXTURES / "normal-capacity-1000"


class Run:
    """정상 fixture를 임시 디렉터리에 복사해 한 가지를 바꾼 뒤 판정한다."""

    def __init__(self, base=BASE):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / base.name
        shutil.copytree(base, self.dir)

    def edit_json(self, name, fn):
        path = self.dir / name
        doc = json.loads(path.read_text())
        fn(doc)
        path.write_text(json.dumps(doc))

    def judge(self):
        try:
            return verdict.evaluate(self.dir)
        finally:
            self.tmp.cleanup()


def status_of(result, check_id):
    return {c["id"]: c["status"] for c in result["checks"]}.get(check_id)


class NormalRunsTest(unittest.TestCase):

    def test_normal_capacity_runs_are_valid_without_false_alarm(self):
        for name in ("normal-capacity-500", "normal-capacity-750", "normal-capacity-1000"):
            with self.subTest(name):
                result = Run(FIXTURES / name).judge()
                self.assertEqual("VALID", result["verdict"], result["checks"])
                bad = [c for c in result["checks"] if c["status"] in ("FAIL", "INCONCLUSIVE")]
                self.assertEqual([], bad)

    def test_normal_integrity_run_passes(self):
        # 2026-10-17 run.sh로 실행한 integrity 1만 건의 실제 증거 (정합성 dry-run 포함).
        result = Run(FIXTURES / "normal-integrity-10k").judge()
        self.assertEqual("PASS", result["verdict"], result["checks"])

    def test_explore_never_passes(self):
        self.assertNotEqual("PASS", Run().judge()["verdict"])


class PastIncidentTest(unittest.TestCase):
    """실제로 겪은 사건을 재현한다."""

    def test_1_stale_load_generator_script(self):
        # 출처: 2026-10-15 #105 실환경 검증. 부하 발생기 저장소가 fbbbc11(#86)에 머물러 k6 스크립트가 main과 달랐다.
        r = Run()
        r.edit_json("precheck.json", lambda d: (d.update(loadgen_commit="fbbbc11"),
                                                d["loadgen_md5"].update({"k6-tps-test.js": "0" * 32})))
        result = r.judge()
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual("FAIL", status_of(result, "pre_version"))

    def test_2_old_container_baked_into_ami(self):
        # 출처: docs/reports/2026-10-03-ami-container-remediation.md. AMI에 구워진 구버전 컨테이너가 남아 있었다.
        r = Run()
        r.edit_json("precheck.json", lambda d: d["app_images"].update(
            {"i-082864e2c8afe630b": d["ssm_image"].rsplit(":", 1)[0] + ":fbbbc11"}))
        result = r.judge()
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual("FAIL", status_of(result, "pre_containers"))

    def test_3_gauge_zero_but_real_queue_isolated(self):
        # 재구성. 출처: docs/reports/2026-09-28-zombie-container-root-cause.md.
        # Gauge는 0으로 떨어졌지만 실제 queue:campaign:{62}에 약 130만 건이 고립됐다.
        r = Run()
        log = r.dir / "redis-watch.log"
        lines = log.read_text().splitlines()
        lines[-1] = lines[-1].replace("inSet=1", "inSet=0").replace("llen=0", "llen=1300000")
        log.write_text("\n".join(lines) + "\n")
        result = r.judge()
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual("FAIL", status_of(result, "queue_final"))

    def test_4_accepted_but_not_in_db(self):
        # 재구성. 출처: 같은 문서. 202로 접수됐지만 고립된 Queue 때문에 DB에 도달하지 않았다.
        r = Run()
        db = r.dir / "db-counts.txt"
        db.write_text(db.read_text().replace("618249 |        618249", "318249 |        318249"))
        result = r.judge()
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual("FAIL", status_of(result, "db_counts"))


class FaultScenarioTest(unittest.TestCase):
    """판정 규칙마다 한 가지씩 깨뜨린다."""

    def test_db_duplicate_fails(self):
        r = Run()
        db = r.dir / "db-counts.txt"
        text = db.read_text()
        head, tail = text.rsplit("dup_user_groups", 1)
        db.write_text(head + "dup_user_groups" + tail.replace("|               0 |", "|               1 |", 1))
        result = r.judge()
        self.assertEqual("FAIL", status_of(result, "db_duplicates"))
        self.assertEqual("FAIL", result["verdict"])

    def test_drain_timeout_fails(self):
        r = Run()
        r.edit_json("drain.json", lambda d: d.update(drained=False, waited_seconds=20, last_lag=5000))
        self.assertEqual("FAIL", r.judge()["verdict"])

    def test_kafka_lag_left_fails(self):
        r = Run()
        r.edit_json("kafka-lag-final.json", lambda d: d["data"]["result"][0].update(value=[0, "120"]))
        self.assertEqual("FAIL", r.judge()["verdict"])

    def test_each_missing_evidence_is_inconclusive(self):
        for name in ("precheck.json", "k6-summary.json", "redis-watch.log", "drain.json",
                     "kafka-lag-final.json", "db-counts.txt", "status.json", "scaling.json"):
            with self.subTest(name):
                r = Run()
                (r.dir / name).unlink()
                self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_missing_run_json_is_inconclusive(self):
        r = Run()
        (r.dir / "run.json").unlink()
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_truncated_evidence_is_inconclusive(self):
        r = Run()
        (r.dir / "k6-summary.json").write_text('{"metrics": {"particip')
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_scale_out_during_run_is_inconclusive(self):
        r = Run()
        r.edit_json("scaling.json", lambda d: d.insert(0, {
            "StartTime": "2026-10-17T10:48:00.000000+00:00",
            "Description": "Launching a new EC2 instance: i-0000000000000000a"}))
        result = r.judge()
        self.assertEqual("INCONCLUSIVE", result["verdict"])
        self.assertEqual("INCONCLUSIVE", status_of(result, "scaling"))

    def test_dropped_iterations_is_inconclusive(self):
        r = Run()
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["dropped_iterations"].update(count=70090))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_late_queue_watch_is_inconclusive(self):
        r = Run()
        r.edit_json("run.json", lambda d: d.update(steady_start_epoch=d["steady_start_epoch"] - 600))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_baseline_lag_is_inconclusive(self):
        r = Run()
        r.edit_json("precheck.json", lambda d: d.update(kafka_lag=500))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_interrupted_run_without_end_time_still_checks_scaling(self):
        # run.sh가 k6 대기 중 중단되면 load_end_epoch·drain_end_epoch이 없다 (실환경 시험 G)
        r = Run()
        r.edit_json("run.json", lambda d: (d.pop("load_end_epoch"), d.pop("drain_end_epoch")))
        result = r.judge()
        self.assertEqual("OK", status_of(result, "scaling"))

    def test_collect_error_is_inconclusive(self):
        r = Run()
        (r.dir / "collect-errors.txt").write_text("db-counts: SSM 명령 대기 시간 초과\n")
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_aborted_run_is_inconclusive(self):
        r = Run()
        r.edit_json("run.json", lambda d: d.update(aborted="pre_version"))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_final_capacity_without_criteria_is_inconclusive(self):
        r = Run()
        r.edit_json("run.json", lambda d: d.update(purpose="final"))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])

    def test_final_capacity_with_met_criteria_passes(self):
        r = Run()
        r.edit_json("run.json", lambda d: d.update(purpose="final"))
        # capacity 정상 fixture에는 정합성 dry-run이 없어 integrity fixture의 결과를 빌려 쓴다(판정 형식 확인용).
        shutil.copy(FIXTURES / "normal-integrity-10k" / "consistency.json", r.dir / "consistency.json")
        (r.dir / "criteria.json").write_text('{"p95_ms": 1000, "max_fail_rate": 0.01}')
        self.assertEqual("PASS", r.judge()["verdict"])

    def test_final_without_consistency_is_inconclusive(self):
        # TEST_GUIDE 8.2: 최종 검증은 정합성 dry-run 결과가 필수 (10-17 에이전트 시험에서 빠진 것을 발견)
        r = Run(FIXTURES / "normal-integrity-10k")
        (r.dir / "consistency.json").unlink()
        result = r.judge()
        self.assertEqual("INCONCLUSIVE", result["verdict"])
        self.assertEqual("INCONCLUSIVE", status_of(result, "consistency"))

    def test_explore_does_not_require_consistency(self):
        self.assertIsNone(status_of(Run().judge(), "consistency"))

    def test_final_capacity_with_missed_criteria_fails(self):
        r = Run()
        r.edit_json("run.json", lambda d: d.update(purpose="final"))
        (r.dir / "criteria.json").write_text('{"p95_ms": 5, "max_fail_rate": 0.01}')
        self.assertEqual("FAIL", r.judge()["verdict"])


class IntegrityRuleTest(unittest.TestCase):

    def test_5xx_fails_integrity(self):
        r = Run(FIXTURES / "normal-integrity-10k")
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["participation_5xx"].update(count=3))
        self.assertEqual("FAIL", r.judge()["verdict"])

    def test_request_shortfall_fails_integrity(self):
        r = Run(FIXTURES / "normal-integrity-10k")
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["participation_success"].update(count=9990))
        self.assertEqual("FAIL", r.judge()["verdict"])

    def test_stock_left_fails_integrity(self):
        r = Run(FIXTURES / "normal-integrity-10k")
        r.edit_json("status.json", lambda d: d["data"].update(currentStock=10))
        self.assertEqual("FAIL", r.judge()["verdict"])

    def test_queue_watch_after_load_start_is_inconclusive(self):
        r = Run(FIXTURES / "normal-integrity-10k")
        r.edit_json("run.json", lambda d: d.update(load_start_epoch=d["load_start_epoch"] - 60))
        self.assertEqual("INCONCLUSIVE", r.judge()["verdict"])


class FaultJudgeTest(unittest.TestCase):
    """PURPOSE=fault: 일부러 낸 장애를 기대대로 잡았는지 판정한다."""

    def fault_run(self, expected, base=FIXTURES / "normal-integrity-10k"):
        r = Run(base)
        r.edit_json("run.json", lambda d: d.update(purpose="fault", fault={
            "description": "시험용 장애", "expected": expected}))
        return r

    def test_detected_when_expected_items_fail(self):
        r = self.fault_run({"k6_errors": "FAIL"})
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["participation_5xx"].update(count=3))
        result = r.judge()
        self.assertEqual("FAULT_DETECTED", result["verdict"])
        self.assertEqual("FAIL", result["raw_verdict"])

    def test_missed_when_fault_not_caught(self):
        # 장애를 냈다고 기록했지만 증거에 흔적이 없다 → 하네스가 놓친 것
        result = self.fault_run({"k6_errors": "FAIL"}).judge()
        self.assertEqual("FAULT_MISSED", result["verdict"])

    def test_unexpected_when_other_items_also_fail(self):
        r = self.fault_run({"k6_errors": "FAIL"})
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["participation_5xx"].update(count=3))
        db = r.dir / "db-counts.txt"
        db.write_text(db.read_text().replace("10000 |         10000", " 9000 |          9000"))
        result = r.judge()
        self.assertEqual("FAULT_UNEXPECTED", result["verdict"])
        self.assertIn("db_counts: FAIL", result["fault_unexpected"])

    def test_missed_without_expectation(self):
        self.assertEqual("FAULT_MISSED", self.fault_run({}).judge()["verdict"])

    def test_aborted_precheck_counts_as_detected(self):
        # 실환경 시험 B: 부하 발생기를 구버전으로 바꾸고 실행하면 부하 전에 중단돼야 한다
        r = self.fault_run({"pre_version": "FAIL"})
        r.edit_json("precheck.json", lambda d: d.update(loadgen_commit="fbbbc11") or
                    d["loadgen_md5"].update({"k6-load-test.js": "0" * 32}))
        r.edit_json("run.json", lambda d: d.update(aborted="pre_version"))
        self.assertEqual("FAULT_DETECTED", r.judge()["verdict"])

    def test_fault_report_is_marked(self):
        r = self.fault_run({"k6_errors": "FAIL"})
        r.edit_json("k6-summary.json", lambda d: d["metrics"]["participation_5xx"].update(count=3))
        verdict.write(r.dir, verdict.evaluate(r.dir))
        text = (r.dir / "verdict.md").read_text()
        r.tmp.cleanup()
        self.assertIn("장애 주입 실행", text)


class ReportTest(unittest.TestCase):
    """report.py: 증거 파일의 수치를 그대로 옮기고, 증거가 빠져도 멈추지 않는다."""

    def test_capacity_report_has_source_numbers(self):
        import report
        r = Run()
        text = report.build(r.dir)
        r.tmp.cleanup()
        self.assertIn("| 202 접수 | 618,249 |", text)
        self.assertIn("| DB 전체 행 / SUCCESS | 618,249 / 618,249 |", text)
        self.assertIn("## 해석 (작성 필요)", text)

    def test_integrity_report(self):
        import report
        r = Run(FIXTURES / "normal-integrity-10k")
        text = report.build(r.dir)
        r.tmp.cleanup()
        self.assertIn("**PASS**", text)
        self.assertIn("| participation_fail | 0 |", text)

    def test_short_load_skips_prometheus_table(self):
        import report
        r = Run(FIXTURES / "normal-integrity-10k")
        text = report.build(r.dir)
        r.tmp.cleanup()
        self.assertIn("짧아 Prometheus 처리율·지연 표를 만들지 않는다", text)
        self.assertNotIn("| API 202 (건/s) |", text)
        self.assertIn("정합성 dry-run", text)

    def test_report_survives_missing_evidence(self):
        import report
        r = Run()
        for name in ("k6-summary.json", "redis-watch.log", "db-counts.txt", "status.json"):
            (r.dir / name).unlink()
        text = report.build(r.dir)
        r.tmp.cleanup()
        self.assertIn("k6 결과 없음", text)
        self.assertIn("Queue 증거 없음", text)
        self.assertIn("INCONCLUSIVE", text)


class CliTest(unittest.TestCase):

    def test_exit_code_and_files(self):
        r = Run()
        r.edit_json("drain.json", lambda d: d.update(drained=False))
        proc = subprocess.run([sys.executable, str(HERE.parent / "verdict.py"), str(r.dir)],
                              capture_output=True, text=True)
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertTrue((r.dir / "verdict.md").read_text().startswith("# 판정: FAIL"))
        r.tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
