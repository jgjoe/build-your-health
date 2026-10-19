"""Apply the pre-registered V2 P1/P2/P3 criteria; rejection is never login success."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results"


def evaluate_run(row, normal):
    login = row["login"]
    assert login["ok"] + login["rejected"] + login["unexpected_error"] + login["not_completed"] == login["started"]
    p95 = {kind: row["latency"][kind]["p95"] for kind in ("products", "history", "create")}
    rejected_p95 = row["latency"]["login_rejected"]["p95"] if login["rejected"] else None
    checks = {
        "nonlogin_p95_lt_500ms": all(value < 500 for value in p95.values()),
        "nonlogin_errors_zero": row["nonlogin_errors"] == 0,
        "all_phase_drops_zero": row["dropped"] == 0,
        "login_unexpected_errors_zero": login["unexpected_error"] == 0,
        "login_incomplete_zero": login["not_completed"] == 0,
        "rejection_p95_lt_100ms": rejected_p95 is None or rejected_p95 < 100,
    }
    if normal:
        checks["login_success_ge_99percent"] = login["started"] > 0 and login["ok"] * 100 >= login["started"] * 99
    return {
        "run": row["run"], "pass": all(checks.values()), "checks": checks,
        "login": login, "nonlogin_p95_ms": p95, "rejection_p95_ms": rejected_p95,
        "http_by_phase": row["http_by_phase"], "app_startup": row["app_startup"],
        "app_cpu_cores": row["app_cpu_cores_jmx"],
    }


def main():
    result = {}
    image_ids, starts = set(), set()
    for wait in (50, 75):
        runs = {}
        for state, rates in (("cold", (270,)), ("warm", (270, 320, 400))):
            label = f"v3-wait{wait}-{state}"
            rows = json.loads((ROOT / f"analysis-{label}.json").read_text(encoding="utf-8"))
            assert {r["run"] for r in rows} == {f"{label}-{tps}-r{repeat}" for tps in rates for repeat in (1, 2)}
            for row in rows:
                run = row["run"]
                repeat = run.rsplit("-r", 1)[1]
                cold = ROOT / f"v3-wait{wait}-cold-270-r{repeat}"
                config = (ROOT / run / "app-config.txt").read_text(encoding="utf-8-sig")
                first = config.splitlines()[0].split()
                cold_first = (cold / "app-config.txt").read_text(encoding="utf-8-sig").splitlines()[0].split()
                assert first[0] == cold_first[0], (run, "JVM changed within a session")
                assert "StartFlightRecording" not in config, (run, "JFR must be off for acceptance")
                assert f"BYH_LOGIN_MAX_WAIT_MS={wait}" in config
                assert "BYH_LOGIN_MAX_CONCURRENT=2" in config
                image_ids.add(first[2])
                if state == "cold":
                    starts.add(first[0])
                    assert (cold / "resource-limits.txt").read_text().strip() == "NanoCpus=2000000000 Memory=2147483648"
                runs[run] = evaluate_run(row, row["target_tps"] == 270)
        p1 = all(r["pass"] for name, r in runs.items() if "-warm-320-" in name or "-warm-400-" in name)
        p2 = all(r["pass"] for name, r in runs.items() if "-warm-270-" in name)
        p3 = all(r["pass"] for name, r in runs.items() if "-cold-270-" in name)
        result[str(wait)] = {"P1": p1, "P2": p2, "P3": p3, "eligible": p1 and p2 and p3, "runs": runs}
    assert len(image_ids) == 1, image_ids
    assert len(starts) == 4, starts
    output = {"image_ids": sorted(image_ids), "independent_jvm_sessions": len(starts), "settings": result}
    (ROOT / "v3-acceptance.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    for wait, row in result.items():
        print(wait, {key: row[key] for key in ("P1", "P2", "P3", "eligible")})


if __name__ == "__main__":
    main()
