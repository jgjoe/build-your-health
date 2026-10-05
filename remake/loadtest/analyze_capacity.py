"""Capacity results, including failed/dropped stages. Standard library only."""
import argparse
import csv
import gzip
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import timedelta
from pathlib import Path

sys.dont_write_bytecode = True
from analyze import ROOT, percentile, timestamp, snapshot


def stats(values):
    return {"n": len(values), "mean": sum(values) / len(values),
            "p50": percentile(values, .5), "p95": percentile(values, .95),
            "p99": percentile(values, .99), "max": max(values)}


def analyze(path):
    summary = json.loads((path / 'summary.json').read_text())
    samples, starts, counts = defaultdict(list), [], Counter()
    completions = []
    errors, drops, dropped_phase = [], 0, Counter()
    errors_by_kind = Counter()
    valid_completions = []
    started_count = Counter()
    with gzip.open(path / 'raw.json.gz', 'rt') as source:
        for line in source:
            point = json.loads(line)
            if point['type'] != 'Point':
                continue
            data, metric = point['data'], point['metric']
            kind = data['tags'].get('kind')
            if metric == 'measured_latency':
                samples['all'].append(data['value'])
                samples[kind].append(data['value'])
            elif metric == 'measured_started':
                starts.append(timestamp(data['time']))
                started_count[kind] += int(data['value'])
            elif metric == 'measured_requests':
                counts[kind] += int(data['value'])
                completions.append(timestamp(data['time']))
            elif metric == 'measured_errors':
                errors.append(data['value'])
                errors_by_kind[kind] += int(data['value'])
                if data['value'] == 0:
                    valid_completions.append(timestamp(data['time']))
            elif metric == 'dropped_iterations':
                drops += int(data['value'])
                dropped_phase[data['tags'].get('scenario', 'unknown')] += int(data['value'])
    total = sum(counts.values())
    assert total == len(samples['all']) == len(errors)
    assert total == summary['metrics']['measured_requests']['values']['count']
    assert sum(started_count.values()) == summary['metrics']['measured_started']['values']['count']
    assert drops == summary['metrics'].get('dropped_iterations', {}).get('values', {}).get('count', 0)
    latency = {kind: stats(values) for kind, values in samples.items()}
    for key, skey in [('p50', 'med'), ('p95', 'p(95)'), ('p99', 'p(99)')]:
        assert abs(latency['all'][key] - summary['metrics']['measured_latency']['values'][skey]) < 1e-6
    if drops == 0:
        assert sum(started_count.values()) in (summary['tps'] * summary['seconds'], summary['tps'] * summary['seconds'] + 1)
    assert all(counts[kind] <= started_count[kind] for kind in counts)
    if drops == 0 and total == sum(started_count.values()):
        expected = Counter()
        for i in range(total):
            slot = i % 20
            expected['products' if slot < 14 else 'history' if slot < 17 else 'login' if slot < 19 else 'create'] += 1
        assert counts == expected
    start, end = min(starts), min(starts) + timedelta(seconds=summary['seconds'])
    in_window = sum(start <= moment <= end for moment in completions)
    valid_in_window = sum(start <= moment <= end for moment in valid_completions)
    with (path / 'hikari.csv').open(encoding='utf-8-sig') as source:
        pool_rows = [row for row in csv.DictReader(source) if start <= timestamp(row['utc']) <= end]
    # Saturation can delay JMX itself. Retain sparse observations explicitly;
    # do not invent a 1 Hz series or discard a failed capacity stage.
    assert len(pool_rows) >= 10, (path, len(pool_rows))
    pool = {key: stats([int(row[key]) for row in pool_rows]) for key in ('active', 'awaiting', 'total', 'http_runnable', 'bcrypt_runnable')}
    gaps = [(timestamp(b['utc']) - timestamp(a['utc'])).total_seconds() for a, b in zip(pool_rows, pool_rows[1:])]
    elapsed = (timestamp(pool_rows[-1]['utc']) - timestamp(pool_rows[0]['utc'])).total_seconds()
    cpu_cores = (int(pool_rows[-1]['process_cpu_ns']) - int(pool_rows[0]['process_cpu_ns'])) / 1e9 / elapsed
    http_cpu_cores = sum(int(row['http_cpu_delta_ns']) for row in pool_rows[1:]) / 1e9 / elapsed
    runnable_http = sum(int(row['http_runnable']) for row in pool_rows)
    bcrypt_fraction = sum(int(row['bcrypt_runnable']) for row in pool_rows) / runnable_http if runnable_http else 0
    moment, docker = None, defaultdict(list)
    memory = defaultdict(list)
    for line in (path / 'docker-stats.txt').read_text(encoding='utf-8-sig').splitlines():
        if re.match(r'^\d{4}-\d{2}-\d{2}T', line):
            moment = timestamp(line)
        elif line.startswith('{') and moment and start <= moment <= end:
            row = json.loads(line)
            docker[row['Name']].append(float(row['CPUPerc'].rstrip('%')))
            usage = re.match(r'([\d.]+)([KMGT]?i?B)', row['MemUsage'])
            factors = {'B': 1 / 1048576, 'KiB': 1 / 1024, 'MiB': 1, 'GiB': 1024}
            memory[row['Name']].append(float(usage[1]) * factors[usage[2]])
    docker = {name: stats(values) for name, values in docker.items()}
    memory = {name: stats(values) for name, values in memory.items()}
    quota_rows, current = [], None
    for line in (path / 'cpu-quota.txt').read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('CPUUTC|'):
            current = {'utc': timestamp(line.split('|')[1])}
            quota_rows.append(current)
        elif current and re.match(r'^\w+ \d+$', line):
            key, value = line.split()
            current[key] = int(value)
    quota_rows = [row for row in quota_rows if start <= row['utc'] <= end]
    assert len(quota_rows) >= 10
    quota_delta = {key: quota_rows[-1][key] - quota_rows[0][key] for key in quota_rows[-1] if key != 'utc'}
    quota_delta['observed_seconds'] = (quota_rows[-1]['utc'] - quota_rows[0]['utc']).total_seconds()
    quota_delta['throttled_period_percent'] = 100 * quota_delta['nr_throttled'] / quota_delta['nr_periods'] if quota_delta['nr_periods'] else 0
    oracle_rows, current = [], None
    for line in (path / 'oracle-samples.txt').read_text(encoding='utf-8-sig').splitlines():
        cells = line.strip().split('|')
        if cells[0] == 'UTC':
            current = {'utc': timestamp(cells[1]), 'waits': {}, 'time': {}, 'sessions': []}
            oracle_rows.append(current)
        elif current and cells[0] == 'SYSWAIT':
            current['waits'][cells[1]] = [int(v) for v in cells[2:4]]
        elif current and cells[0] == 'DBTIME':
            current['time'][cells[1]] = int(cells[2])
        elif current and cells[0] == 'SESSION':
            current['sessions'].append(cells[1:])
    oracle_rows = [row for row in oracle_rows if start <= row['utc'] <= end]
    assert len(oracle_rows) >= 10
    first, last = oracle_rows[0], oracle_rows[-1]
    waits = {event: [v[i] - first['waits'].get(event, [0, 0])[i] for i in range(2)] for event, v in last['waits'].items()}
    assert all(v >= 0 for delta in waits.values() for v in delta)
    waits = {event: delta for event, delta in waits.items() if delta[0] or delta[1]}
    time_delta = {key: value - first['time'][key] for key, value in last['time'].items()}
    active_waits = Counter()
    active_samples = 0
    for row in oracle_rows:
        for session in row['sessions']:
            # sid,serial,status,state,wait_class,event,sql_id
            if session[2] == 'ACTIVE':
                active_samples += 1
                active_waits[session[5] if session[3] == 'WAITING' else 'ON_CPU_OR_RUNNABLE'] += 1
    _, before_sql = snapshot(path / 'db-before.txt')
    _, after_sql = snapshot(path / 'db-after.txt')
    sql = []
    for cursor, item in after_sql.items():
        delta = [v - before_sql.get(cursor, {}).get('counters', [0] * 8)[i] for i, v in enumerate(item['counters'])]
        if delta[0] > 0:
            sql.append({'cursor': cursor, 'plan': item['plan'], 'executions': delta[0],
                        'elapsed_ms': delta[2] / delta[0] / 1000, 'gets': delta[1] / delta[0],
                        'io_ms': delta[4] / 1000, 'concurrency_ms': delta[5] / 1000,
                        'application_ms': delta[6] / 1000, 'sql': item['sql']})
    for name in ('db-before.txt', 'db-clean.txt'):
        assert 'ROWS|22021|2001|600000|1499546' in (path / name).read_text()
    checks = (path / 'seed-checksums.txt').read_text().splitlines()
    for table in ('MEMBER', 'PRODUCT', 'ORDERS', 'ITEMS'):
        src = next(line for line in checks if line.startswith(f'SOURCE|{table}|'))
        clone = next(line for line in checks if line.startswith(f'CLONE|{table}|'))
        assert src.replace('SOURCE|', '') == clone.replace('CLONE|', '')
    error_percent = sum(errors) / total * 100
    reasons = []
    if latency['all']['p95'] > 500:
        reasons.append('mixed_p95_gt_500ms')
    if error_percent >= 1:
        reasons.append('errors_ge_1percent')
    if drops:
        reasons.append('dropped_iterations')
    return {'run': path.name, 'target_tps': summary['tps'], 'vus_per_scenario': summary['vus'],
            'actual_tps': in_window / summary['seconds'],
            'valid_response_tps': valid_in_window / summary['seconds'],
            'completed_in_window': in_window, 'completed_after_window': total - in_window,
            'completed_per_scheduled_second_including_grace': total / summary['seconds'],
            'completed': dict(counts), 'started': dict(started_count),
            'started_without_completed_sample': sum(started_count.values()) - total,
            'error_percent': error_percent, 'dropped': drops, 'dropped_by_phase': dict(dropped_phase),
            'errors_by_kind': dict(errors_by_kind),
            'stop_reasons': reasons, 'latency': latency, 'measured_start_utc': start.isoformat(),
            'pool_and_threads': pool, 'app_cpu_cores_jmx': cpu_cores, 'http_cpu_cores_jmx': http_cpu_cores,
            'jmx_sample_interval_seconds': stats(gaps),
            'bcrypt_fraction_of_runnable_http_stack_samples': bcrypt_fraction,
            'docker_cpu_percent': docker, 'cpu_quota_delta': quota_delta,
            'docker_memory_mib': memory,
            'oracle': {'samples': len(oracle_rows), 'seconds': (last['utc'] - first['utc']).total_seconds(),
                       'system_non_idle_wait_delta_count_us': waits, 'system_time_delta_us': time_delta,
                       'byh_load_active_session_samples': active_samples,
                       'byh_load_active_events': dict(active_waits)},
            'sql_including_setup_warmup': sql}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', default='capacity-before')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        parser.error('Invalid label')
    paths = sorted((ROOT / 'results').glob(f'{args.label}-*-r*'), key=lambda p: (int(p.name.split('-')[-2]), p.name))
    results = [analyze(p) for p in paths if (p / 'seed-checksums.txt').exists()]
    (ROOT / 'results' / f'analysis-{args.label}.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    for result in results:
        lat = result['latency']['all']
        print(f"{result['run']} | {result['actual_tps']:.2f} | {lat['p50']:.2f}/{lat['p95']:.2f}/{lat['p99']:.2f} | "
              f"err={result['error_percent']:.3f}% drops={result['dropped']} | "
              f"CPU={result['app_cpu_cores_jmx']:.2f} cores | stop={result['stop_reasons']}")
