"""Correlate real JFR phases with k6; durations are wall time, not compiler CPU attribution."""
from collections import Counter, defaultdict
from datetime import timedelta
import gzip
import json
from pathlib import Path

from analyze_capacity import stats
from analyze_jfr import category, frame_name, timestamp
from v3_jfr import duration_ms, events

ROOT = Path(__file__).resolve().parent / "results"


def summarize(bucket):
    return {
        "event_counts": dict(bucket["counts"]),
        "timings_ms": {name: stats(values) for name, values in bucket["timings"].items()},
        "cpu_fractions": {name: stats(values) for name, values in bucket["cpu"].items()},
        "decision_counts": dict(bucket["decisions"]),
        "work_outcomes": dict(bucket["outcomes"]),
        "compilation_levels": dict(bucket["levels"]),
        "compilation_wall_ms_sum": sum(bucket["timings"].get("compilation", [])),
        "java_execution_samples": dict(bucket["samples"]),
        "bcrypt_java_sample_fraction": bucket["samples"]["bcrypt"] / sum(bucket["samples"].values())
        if bucket["samples"] else None,
    }


def bucket():
    return {"counts": Counter(), "timings": defaultdict(list), "decisions": Counter(),
            "outcomes": Counter(), "levels": Counter(), "samples": Counter(), "cpu": defaultdict(list)}


def analyze(row):
    path = ROOT / row["run"]
    begins, ends, login_http = {}, {}, Counter()
    with gzip.open(path / "raw.json.gz", "rt", encoding="utf-8") as source:
        for line in source:
            point = json.loads(line)
            if point["type"] != "Point" or point["metric"] != "http_req_duration":
                continue
            data = point["data"]
            phase = data["tags"]["phase"]
            finish = timestamp(data["time"])
            start = finish - timedelta(milliseconds=data["value"])
            begins[phase] = min(begins.get(phase, start), start)
            ends[phase] = max(ends.get(phase, finish), finish)
            if data["tags"]["kind"] == "login":
                login_http[phase] += 1
    origin = begins["setup"]
    measured = timestamp(row["measured_start_utc"])
    measured_end = measured + timedelta(seconds=120)

    def phase_at(moment):
        if moment < origin:
            return "startup"
        if moment < begins["warmup"]:
            return "setup"
        if moment <= ends["warmup"]:
            return "warmup"
        if moment < measured:
            return "gap"
        return "measured" if moment < measured_end else "drain"

    phases, timeline = defaultdict(bucket), defaultdict(bucket)
    compilations = []
    compiler_snapshots = []
    total_admissions, total_work, admitted = 0, 0, 0
    for event in events(path / "jfr-samples.json.gz"):
        name, value = event["type"], event["values"]
        moment = timestamp(value["startTime"])
        phase = phase_at(moment)
        bins = (phases[phase], timeline[int((moment - origin).total_seconds() // 10)])
        for current in bins:
            current["counts"][name] += 1
            if name == "byh.LoginAdmission":
                decision = value["decision"]
                current["decisions"][decision] += 1
                current["timings"][f"permit_wait_{decision}"].append(value["waitNanos"] / 1e6)
                if decision == "admitted":
                    current["timings"]["permit_service"].append(value["serviceNanos"] / 1e6)
            elif name == "byh.LoginWork":
                current["outcomes"][value["outcome"]] += 1
                for key in ("accountReadNanos", "lookupNanos", "bcryptNanos", "accountWriteNanos"):
                    current["timings"][key].append(value[key] / 1e6)
            elif name == "jdk.Compilation":
                current["levels"][str(value["compileLevel"])] += 1
                current["timings"]["compilation"].append(duration_ms(value["duration"]))
            elif name == "jdk.GarbageCollection":
                current["timings"]["gc_cycle"].append(duration_ms(value["duration"]))
                current["timings"]["gc_longest_pause"].append(duration_ms(value["longestPause"]))
            elif name == "jdk.SafepointBegin":
                current["timings"]["safepoint_begin"].append(duration_ms(value["duration"]))
            elif name == "jdk.CPULoad":
                for key in ("jvmUser", "jvmSystem", "machineTotal"):
                    current["cpu"][key].append(value[key])
            elif name == "jdk.ExecutionSample":
                current["samples"][category([frame_name(frame) for frame in value["stackTrace"]["frames"]])] += 1
        if name == "byh.LoginAdmission":
            total_admissions += 1
            admitted += value["decision"] == "admitted"
        elif name == "byh.LoginWork":
            total_work += 1
        elif name == "jdk.CompilerStatistics":
            compiler_snapshots.append({"utc": value["startTime"], "phase": phase,
                                       "compileCount": value["compileCount"],
                                       "totalTimeSpent_ms": duration_ms(value["totalTimeSpent"]),
                                       "peakTimeSpent_ms": duration_ms(value["peakTimeSpent"])})
        elif name == "jdk.Compilation":
            method = value["method"]
            method_name = method["type"]["name"].replace("/", ".") + "." + method["name"]
            if ".crypto.bcrypt." in method_name:
                compilations.append({"utc": value["startTime"], "phase": phase,
                                     "relative_to_first_http_seconds": (moment-origin).total_seconds(),
                                     "method": method_name, "compiler": value["compiler"],
                                     "level": value["compileLevel"], "osr": value["isOsr"],
                                     "wall_ms": duration_ms(value["duration"])})
    assert total_admissions == sum(login_http.values()), (row["run"], total_admissions, login_http)
    assert total_work == admitted, (total_work, admitted)
    result = {
        "run": row["run"], "login_http_counts": dict(login_http), "admission_events": total_admissions,
        "work_events": total_work, "first_http_utc": origin.isoformat(), "measured_start_utc": measured.isoformat(),
        "phases": {name: summarize(value) for name, value in phases.items()},
        "timeline_10s": [{"second_from_first_http": second * 10, **summarize(value)}
                         for second, value in sorted(timeline.items())],
        "bcrypt_compilations": compilations,
        "compiler_first_snapshot": compiler_snapshots[0], "compiler_last_snapshot": compiler_snapshots[-1],
        "compiler_snapshots_by_phase": {
            phase: {"first": selected[0], "last": selected[-1]}
            for phase in phases if (selected := [s for s in compiler_snapshots if s["phase"] == phase])
        },
        "capacity_login": row["login"], "cpu_quota_delta": row["cpu_quota_delta"],
        "vm_and_host_cpu": row["vm_and_host_cpu"],
        "interpretation_limits": [
            "Compilation event durations/cumulative compiler time are not a measured compiler CPU fraction.",
            "Execution samples cover Java stacks, excluding compiler/GC/native CPU.",
            "GC cycle and safepoint-begin duration are not interchangeable with the full stop-the-world pause.",
            "JFR diagnostic runs do not substitute for unprofiled acceptance runs.",
        ],
    }
    (path / "startup-analysis.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    measured_phase = result["phases"]["measured"]
    print(row["run"], json.dumps({
        "measured_decisions": measured_phase["decision_counts"],
        "measured_timings_ms": measured_phase["timings_ms"],
        "bcrypt_level4_phases": sorted({r["phase"] for r in compilations if r["level"] == 4}),
        "measured_compilation_wall_ms_sum": measured_phase["compilation_wall_ms_sum"],
    }))


if __name__ == "__main__":
    for row in json.loads((ROOT / "analysis-v3-diag.json").read_text(encoding="utf-8")):
        analyze(row)
