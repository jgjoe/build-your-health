"""Recompute tables from retained raw k6 samples; no third-party dependencies."""
import csv
import argparse
import gzip
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def percentile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lo = int(index)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (index - lo)


def timestamp(text):
    return datetime.fromisoformat(text.replace('Z', '+00:00'))


def snapshot(path):
    waits, sql = {}, {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        cells = line.strip().split('|')
        if cells[0] == 'WAIT':
            waits[cells[1]] = [int(v) for v in cells[2:4]]
        elif cells[0] == 'SQL':
            sql[tuple(cells[1:3])] = {'plan': cells[3], 'counters': [int(v) for v in cells[4:12]], 'sql': '|'.join(cells[12:])}
    return waits, sql


def analyze(path):
    summary = json.loads((path / 'summary.json').read_text())
    samples, times = defaultdict(list), []
    counts, failed, http_failed = Counter(), [], []
    with gzip.open(path / 'raw.json.gz', 'rt') as source:
        for line in source:
            point = json.loads(line)
            if point['type'] != 'Point':
                continue
            data = point['data']
            metric = point['metric']
            if metric == 'measured_latency':
                samples['all'].append(data['value'])
                samples[data['tags']['kind']].append(data['value'])
                times.append(timestamp(data['time']) - timedelta(milliseconds=data['value']))
            elif metric == 'measured_requests':
                counts[data['tags']['kind']] += int(data['value'])
            elif metric == 'measured_errors':
                failed.append(data['value'])
            elif metric == 'http_req_failed' and data['tags'].get('phase') == 'measured':
                http_failed.append(data['value'])
    tps, seconds = summary['tps'], summary['seconds']
    total = sum(counts.values())
    # k6 can schedule one iteration at the exact duration boundary.
    assert total in (tps * seconds, tps * seconds + 1), (path, total)
    expected = Counter()
    for i in range(total):
        slot = i % 20
        expected['products' if slot < 14 else 'history' if slot < 17 else 'login' if slot < 19 else 'create'] += 1
    assert dict(counts) == expected, (path, counts, expected)
    assert total == len(samples['all']) == len(failed) == len(http_failed), path
    # k6 Counter rate includes setup/warmup; use the fixed measured window.
    latency = {kind: {'n': len(values), 'p50': percentile(values, .5),
                      'p95': percentile(values, .95), 'p99': percentile(values, .99)}
               for kind, values in samples.items()}
    # Independent raw-sample check against the summary's trend statistics.
    summary_all = summary['metrics']['measured_latency']['values']
    for key, summary_key in [('p50', 'med'), ('p95', 'p(95)'), ('p99', 'p(99)')]:
        assert abs(latency['all'][key] - summary_all[summary_key]) < 1e-6, path
    start = min(times)
    end = start + timedelta(seconds=seconds)
    with (path / 'hikari.csv').open(encoding='utf-8-sig') as source:
        hikari = list(csv.DictReader(source))
    hikari = [row for row in hikari if start <= timestamp(row['utc']) <= end]
    assert len(hikari) >= seconds - 5, (path, len(hikari))
    pool = {key + '_max': max(int(row[key]) for row in hikari) for key in ('active', 'awaiting', 'total')}
    pool['samples'] = len(hikari)
    stats = defaultdict(list)
    moment = None
    for line in (path / 'docker-stats.txt').read_text(encoding='utf-8-sig').splitlines():
        if re.match(r'^\d{4}-\d{2}-\d{2}T', line):
            moment = timestamp(line)
        elif line.startswith('{') and moment and start <= moment <= end:
            row = json.loads(line)
            usage = re.match(r'([\d.]+)([KMGT]?i?B)', row['MemUsage'])
            factors = {'B': 1/1048576, 'KiB': 1/1024, 'MiB': 1, 'GiB': 1024}
            stats[row['Name']].append((float(row['CPUPerc'].rstrip('%')), float(usage[1]) * factors[usage[2]]))
    resources = {name: {'samples': len(rows), 'cpu_percent_max': max(v[0] for v in rows),
                        'memory_mib_max': max(v[1] for v in rows)} for name, rows in stats.items()}
    before_waits, before_sql = snapshot(path / 'db-before.txt')
    after_waits, after_sql = snapshot(path / 'db-after.txt')
    waits = {event: [v[i] - before_waits.get(event, [0, 0])[i] for i in range(2)] for event, v in after_waits.items()}
    # Aggregates over currently alive sessions can lose counters. Never treat
    # negative deltas as reduced waiting, or clamp them to zero.
    wait_status = 'comparable' if all(v >= 0 for delta in waits.values() for v in delta) else 'not_comparable_counter_decrease'
    sql = []
    for cursor, item in after_sql.items():
        old = before_sql.get(cursor, {}).get('counters', [0] * 8)
        delta = [v - old[i] for i, v in enumerate(item['counters'])]
        if delta[0] > 0:
            sql.append({'cursor': cursor, 'plan': item['plan'], 'executions': delta[0],
                        'gets_per_execution': delta[1] / delta[0], 'elapsed_ms_per_execution': delta[2] / delta[0] / 1000,
                        'cpu_ms_per_execution': delta[3] / delta[0] / 1000, 'io_wait_ms': delta[4] / 1000,
                        'concurrency_wait_ms': delta[5] / 1000, 'application_wait_ms': delta[6] / 1000, 'sql': item['sql']})
    for file in ('db-before.txt', 'db-clean.txt'):
        assert 'ROWS|22021|2001|600000|1499546' in (path / file).read_text(), path
    checks = (path / 'seed-checksums.txt').read_text().splitlines()
    for table in ('MEMBER', 'PRODUCT', 'ORDERS', 'ITEMS'):
        source = next(line for line in checks if line.startswith(f'SOURCE|{table}|'))
        clone = next(line for line in checks if line.startswith(f'CLONE|{table}|'))
        assert source.replace('SOURCE|', '') == clone.replace('CLONE|', ''), path
    return {'run': path.name, 'target_tps': tps, 'actual_tps': total / seconds, 'requests': total,
            'error_percent': sum(failed) / total * 100, 'http_error_percent': sum(http_failed) / total * 100,
            'dropped': summary['metrics'].get('dropped_iterations', {}).get('values', {}).get('count', 0),
            'latency': latency, 'pool': pool, 'docker_measured_samples': resources,
            'db_session_wait_status': wait_status, 'db_waits_including_setup_warmup': waits,
            'db_sql_including_setup_warmup': sql, 'measured_start_utc': start.isoformat()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', default='baseline')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        parser.error('Invalid label')
    runs = sorted((ROOT / 'results').glob(f'{args.label}-*-r*'), key=lambda p: (int(p.name.split('-')[-2]), p.name))
    results = [analyze(p) for p in runs if (p / 'summary.json').exists()]
    output = 'analysis.json' if args.label == 'baseline' else f'analysis-{args.label}.json'
    (ROOT / 'results' / output).write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding='utf-8')
    for result in results:
        lat = result['latency']['all']
        print(f"{result['run']} | {result['target_tps']} | {result['actual_tps']:.2f} | "
              f"{lat['p50']:.2f}/{lat['p95']:.2f}/{lat['p99']:.2f} | {result['error_percent']:.2f}% | "
              f"dropped={result['dropped']} | pool={result['pool']}")
