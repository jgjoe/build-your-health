"""Summarize JFR execution samples inside a run's measured window.

jdk.ExecutionSample samples threads running Java code, so the share of samples approximates the
share of Java CPU time. GC, JIT compiler and native time are not in these samples.
"""
import argparse
import gzip
import json
import re
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def frame_name(frame):
    method = frame['method']
    # JFR records class names in internal form (java/lang/String).
    return f"{method['type']['name'].replace('/', '.')}.{method['name']}"


def category(names):
    if any('.crypto.bcrypt.' in name for name in names):
        return 'bcrypt'
    if any(name.startswith('oracle.jdbc.') or name.startswith('com.zaxxer.hikari.') for name in names):
        return 'jdbc_and_pool'
    if any(name.startswith('org.apache.ibatis.') or name.startswith('org.mybatis.') for name in names):
        return 'mybatis'
    if any(name.startswith('com.fasterxml.jackson.') for name in names):
        return 'json'
    return 'other'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+', args.run):
        parser.error('Invalid run')
    path = ROOT / 'results' / args.run
    summary = json.loads((path / 'summary.json').read_text())
    label = args.run.rsplit('-', 2)[0]
    analysis = next(r for r in json.loads((ROOT / 'results' / f'analysis-{label}.json').read_text(encoding='utf-8'))
                    if r['run'] == args.run)
    start = timestamp(analysis['measured_start_utc'])
    end = start + timedelta(seconds=summary['seconds'])
    with gzip.open(path / 'jfr-samples.json.gz', 'rt', encoding='utf-8') as source:
        events = json.load(source)['recording']['events']
    categories, top_frames, threads = Counter(), Counter(), Counter()
    total = 0
    for event in events:
        values = event['values']
        if not start <= timestamp(values['startTime']) <= end:
            continue
        names = [frame_name(frame) for frame in values['stackTrace']['frames']]
        total += 1
        categories[category(names)] += 1
        top_frames[names[0]] += 1
        thread = values['sampledThread']['javaName'] or ''
        threads['http' if thread.startswith('http-nio') else 'other'] += 1
    assert total > 1000, (args.run, total)
    result = {'run': args.run, 'measured_start_utc': start.isoformat(), 'samples_in_window': total,
              'samples_in_recording': len(events), 'stack_depth': 16,
              'share_by_category': {k: v / total for k, v in categories.most_common()},
              'share_by_thread': {k: v / total for k, v in threads.most_common()},
              'top_frames': {k: v / total for k, v in top_frames.most_common(15)}}
    (path / 'jfr-summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(args.run, total, {k: round(v, 3) for k, v in result['share_by_category'].items()})


if __name__ == '__main__':
    main()
