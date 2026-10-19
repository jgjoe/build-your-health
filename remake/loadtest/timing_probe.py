"""Sequential, paired login timing experiment; criteria: docs/load-test-v3.md section 2.1."""
import argparse
import csv
from datetime import datetime, timezone
import http.client
import json
import math
from pathlib import Path
import random
import statistics
import time

ROOT = Path(__file__).resolve().parent
PAIRS = 1000
BLOCKS = 2
WARMUP_PAIRS = 100
SEED = 20261002


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def summarize(pairs, block):
    known = [pair["known"] for pair in pairs]
    missing = [pair["missing"] for pair in pairs]
    rng = random.Random(SEED + block)
    bootstrap = []
    for _ in range(2000):
        sample = rng.choices(pairs, k=len(pairs))
        a = statistics.median(pair["known"] for pair in sample)
        b = statistics.median(pair["missing"] for pair in sample)
        bootstrap.append((b - a) / a)
    interval = [percentile(bootstrap, .025), percentile(bootstrap, .975)]
    return {
        "block": block, "pairs": len(pairs),
        "known": {"median_ms": statistics.median(known), "p95_ms": percentile(known, .95)},
        "missing": {"median_ms": statistics.median(missing), "p95_ms": percentile(missing, .95)},
        "paired_median_difference_ms": statistics.median(b - a for a, b in zip(known, missing)),
        "relative_median_difference": (statistics.median(missing) - statistics.median(known))
        / statistics.median(known),
        "relative_difference_bootstrap_95ci": interval,
        "equivalent_within_10_percent": interval[0] >= -.10 and interval[1] <= .10,
    }


def request(connection, category, sequence):
    user = f"u{sequence if category == 'known' else 900000 + sequence:06d}"
    body = json.dumps({"id": user, "password": "incorrect-password"}).encode("utf-8")
    start = time.perf_counter_ns()
    connection.request("POST", "/api/auth/login", body, {"Content-Type": "application/json"})
    response = connection.getresponse()
    payload = response.read()
    elapsed_ms = (time.perf_counter_ns() - start) / 1e6
    expected = {
        "code": "UNAUTHORIZED",
        "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
    }
    if response.status != 401 or json.loads(payload) != expected or response.getheader("Set-Cookie"):
        raise RuntimeError(f"Unexpected login contract: {category}, status={response.status}")
    return elapsed_ms, response.status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", choices=("before", "after"), required=True)
    args = parser.parse_args()
    out = ROOT / "results" / f"v3-timing-{args.label}"
    out.mkdir()  # Never overwrite a failed or completed experiment.
    started = datetime.now(timezone.utc).isoformat()
    blocks = []
    connection = http.client.HTTPConnection("127.0.0.1", 8082, timeout=5)
    try:
        with (out / "raw.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            writer.writerow(("block", "pair", "position", "kind", "milliseconds", "status"))
            for sequence in range(10001, 10001 + WARMUP_PAIRS):
                for category in ("known", "missing"):
                    request(connection, category, sequence)
            for block in range(1, BLOCKS + 1):
                rng = random.Random(SEED + block)
                sequences = list(range(1, PAIRS + 1))
                rng.shuffle(sequences)
                pairs = []
                for sequence in sequences:
                    kinds = ["known", "missing"]
                    rng.shuffle(kinds)
                    pair = {}
                    for position, kind in enumerate(kinds):
                        elapsed, status = request(connection, kind, sequence)
                        pair[kind] = elapsed
                        writer.writerow((block, sequence, position, kind, elapsed, status))
                    pairs.append(pair)
                target.flush()
                blocks.append((block, pairs))
                if block != BLOCKS:
                    time.sleep(10)
    finally:
        connection.close()
    # Analyze only after collection, so bootstrap CPU cannot perturb the next block.
    results = [summarize(pairs, block) for block, pairs in blocks]
    summary = {
        "label": args.label, "started_utc": started,
        "finished_utc": datetime.now(timezone.utc).isoformat(),
        "pairs_per_block": PAIRS, "blocks": BLOCKS, "warmup_pairs": WARMUP_PAIRS,
        "seed": SEED, "timeout_seconds": 5, "concurrent_requests": 1,
        "results": results, "unexpected_responses": 0,
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(json.dumps(result))


if __name__ == "__main__":
    main()
