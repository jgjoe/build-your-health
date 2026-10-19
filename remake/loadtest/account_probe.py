"""Real-clock lockout/restart smoke experiment; isolated synthetic IDs, no cookies in output."""
import argparse
import csv
from datetime import datetime, timezone
import http.client
import json
from pathlib import Path
import time

OUT = Path(__file__).resolve().parent / "results/v3-account-policy"
KNOWN = "u020001"
MISSING = "u990001"
FIELDS = ("utc", "phase", "kind", "status", "retry_after", "milliseconds", "session_issued")


def post(connection, writer, phase, kind, password, expected_status):
    user = KNOWN if kind == "known" else MISSING
    body = json.dumps({"id": user, "password": password}).encode("utf-8")
    start = time.perf_counter_ns()
    connection.request("POST", "/api/auth/login", body, {"Content-Type": "application/json"})
    response = connection.getresponse()
    payload = json.loads(response.read())
    milliseconds = (time.perf_counter_ns() - start) / 1e6
    retry = response.getheader("Retry-After")
    session = response.getheader("Set-Cookie") is not None
    writer.writerow((datetime.now(timezone.utc).isoformat(), phase, kind, response.status,
                     retry or "", milliseconds, session))
    if response.status != expected_status:
        raise RuntimeError(f"{phase}/{kind}: expected {expected_status}, got {response.status}")
    if expected_status == 429:
        if not retry or not 1 <= int(retry) <= 60 or payload != {
            "code": "TOO_MANY_REQUESTS", "message": "로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요."
        } or session:
            raise RuntimeError("Invalid throttling response")
    elif expected_status == 401:
        if payload != {"code": "UNAUTHORIZED", "message": "아이디 또는 비밀번호가 올바르지 않습니다."} or session:
            raise RuntimeError("Invalid authentication failure response")
    elif payload.get("id") != KNOWN or not session:
        raise RuntimeError("Successful login must identify the member and issue a session")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("start", "finish"), required=True)
    args = parser.parse_args()
    if args.phase == "start":
        OUT.mkdir()
    connection = http.client.HTTPConnection("127.0.0.1", 8082, timeout=5)
    try:
        with (OUT / "raw.csv").open("a", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            if args.phase == "start":
                writer.writerow(FIELDS)
                for _ in range(5):
                    for kind in ("known", "missing"):
                        post(connection, writer, "failures", kind, "incorrect-password", 401)
                # Server has already committed the lock by the fifth 401; wait at least 61s from here.
                checkpoint = {"recovery_not_before_epoch": time.time() + 61,
                              "start_utc": datetime.now(timezone.utc).isoformat()}
                for kind in ("known", "missing"):
                    post(connection, writer, "locked", kind, "incorrect-password", 429)
                (OUT / "checkpoint.json").write_text(json.dumps(checkpoint, indent=2), encoding="utf-8")
            else:
                checkpoint = json.loads((OUT / "checkpoint.json").read_text())
                for kind in ("known", "missing"):
                    post(connection, writer, "after_restart", kind, "demo1234", 429)
                target.flush()
                time.sleep(max(0, checkpoint["recovery_not_before_epoch"] - time.time()))
                for kind in ("known", "missing"):
                    post(connection, writer, "after_expiry", kind, "incorrect-password", 401)
                post(connection, writer, "reset_success", "known", "demo1234", 200)
                for _ in range(4):
                    post(connection, writer, "after_reset_failures", "known", "incorrect-password", 401)
                post(connection, writer, "after_reset_success", "known", "demo1234", 200)
    finally:
        connection.close()
    if args.phase == "finish":
        with (OUT / "raw.csv").open(encoding="utf-8") as source:
            rows = list(csv.DictReader(source))
        counts = {str(status): sum(int(r["status"]) == status for r in rows) for status in (200, 401, 429)}
        if len(rows) != 22 or counts != {"200": 2, "401": 16, "429": 4}:
            raise RuntimeError(f"Incomplete policy experiment: {counts}")
        summary = {
            "requests": len(rows), "counts": counts, "contract_checks": "pass",
            "lock_survived_container_restart": True, "real_clock_expiry": True, "success_reset": True,
            "started_utc": checkpoint["start_utc"],
            "finished_utc": datetime.now(timezone.utc).isoformat(),
            "rejections": [{k: r[k] for k in ("phase", "kind", "retry_after", "milliseconds")}
                           for r in rows if r["status"] == "429"],
        }
        (OUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary))


if __name__ == "__main__":
    main()
