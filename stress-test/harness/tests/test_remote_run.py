"""외부 자원 없이 원격 실행자의 부하 종료·배출·가드 경로를 검증한다."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class RemoteRunTest(unittest.TestCase):
    def run_remote(self, memory, exit_code=99, guard_during_load=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        bin_dir = root / "bin"
        run_dir = root / "run"
        bin_dir.mkdir()
        run_dir.mkdir()

        def stub(name, script):
            path = bin_dir / name
            path.write_text("#!/usr/bin/env bash\nset -eu\n" + script)
            path.chmod(0o755)

        stub("flock", "exit 0\n")
        stub("timeout", 'shift\nexec "$@"\n')
        stub("sleep", "exit 0\n")
        stub("curl", """echo '{"status":"success","data":{"result":[{"value":[1,"0"]}]}}'""")
        stub("docker", '''
case " $* " in
  *' CLUSTER NODES '*) echo "abc 10.1.1.1:6379@16379 myself,master - 0 0 1 connected 0-16383" ;;
  *' INFO memory '*)
    if [[ "$GUARD_DURING_LOAD" == yes && -s load-start ]]; then
      echo used_memory:100
    else
      echo "used_memory:$REDIS_TEST_MEMORY"
    fi
    ;;
  *' LLEN '*|*' ZCARD '*) echo 0 ;;
  *' GET '*)
    if [[ -f k6-exit ]]; then echo 0; else echo 2; fi
    ;;
esac
''')
        stub("k6", '''
echo args > k6-invoked
if [[ "$GUARD_DURING_LOAD" == yes ]]; then
  trap 'exit 105' TERM
  while :; do :; done
fi
printf '{"metrics":{"iterations":{"count":2}}}' > "$K6_SUMMARY_EXPORT"
exit "$K6_TEST_EXIT_CODE"
''')
        (run_dir / "run.env").write_text(
            "RUN_ID=test-run\nLOADGEN_REPO=/fake\nLAG=lag\nREDIS_HOST=redis\n"
            "REDIS_PRIMARIES=1\nREDIS_GUARD_BYTES=100\nCAMPAIGN_ID=1\nTOTAL_REQUESTS=2\n"
            "MAX_VUS=1\nDURATION=30\nBASE_URL=http://example.test\nDRAIN_TIMEOUT=30\n"
        )
        env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", REDIS_TEST_MEMORY=str(memory),
                   K6_TEST_EXIT_CODE=str(exit_code), GUARD_DURING_LOAD="yes" if guard_during_load else "no")
        shell = Path(__file__).resolve().parents[1] / "remote-run.sh"
        result = subprocess.run(["bash", str(shell)], cwd=run_dir, env=env, capture_output=True, text=True, timeout=10)
        return run_dir, result

    def test_threshold_exit_keeps_evidence_and_waits_for_drain(self):
        run, result = self.run_remote(20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("99", (run / "k6-exit").read_text().strip())
        self.assertTrue((run / "drain-end").is_file())
        self.assertIn('"drained":true', (run / "drain.json").read_text())
        samples = (run / "redis-watch.log").read_text().splitlines()
        self.assertIn("inflight=0 stock=2", samples[0])
        self.assertIn("inflight=0 stock=0", samples[-1])

    def test_memory_guard_blocks_launch_on_full_shard(self):
        run, result = self.run_remote(100)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("redis-memory-preflight", (run / "interruption").read_text().strip())
        self.assertFalse((run / "load-start").exists())
        self.assertFalse((run / "k6-invoked").exists())

    def test_memory_guard_interrupts_its_load_and_waits_for_drain(self):
        run, result = self.run_remote(20, guard_during_load=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("redis-memory-guard", (run / "interruption").read_text().strip())
        self.assertTrue((run / "drain-end").exists())
