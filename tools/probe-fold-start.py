#!/usr/bin/env python3
"""Compare vendor sensor timestamps, public hinge values and the flip switch.
Read-only, time-bounded diagnostic; never starts a visual effect.
"""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = re.compile(r'\s*\d+ \(ts=([\d.]+), wall=[^)]*\) (.*)')

def samples(dump, label):
    result = []
    active = False
    for line in dump.splitlines():
        if line.startswith(label) and ': last ' in line:
            active = True
            continue
        if active:
            match = SAMPLE.fullmatch(line)
            if match:
                result.append((float(match[1]), match[2]))
            elif line and not line[0].isspace():
                break
    return sorted(result)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--interval', type=float, default=.25)
    parser.add_argument('--serial')
    args = parser.parse_args()
    if not 1 <= args.seconds <= 300 or not .1 <= args.interval <= 2:
        parser.error('duration 1..300 seconds; interval .1..2 seconds')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
    adb = [str(sdk / 'platform-tools/adb')]
    if args.serial:
        adb += ['-s', args.serial]
    def shell(command):
        return subprocess.run(adb + ['shell', command], capture_output=True, text=True,
                              check=True, timeout=8).stdout
    inventory = shell('getevent -lp')
    flip = None
    for block in re.split(r'(?=add device \d+:)', inventory):
        if re.search(r'name:\s+"flip"', block) and 'SW_LID' in block:
            match = re.search(r'/dev/input/event\d+', block)
            if match:
                flip = match[0]
                break
    out = ROOT / 'build/system-inspection' / ('fold-start-' + time.strftime('%Y%m%d-%H%M%S') + '.jsonl')
    out.parent.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    durations = []
    counts = {'vendor': 0, 'public': 0, 'lid': 0}
    last = {}
    public_angle = None
    with out.open('w') as file:
        def emit(kind, **fields):
            row = dict(kind=kind, observed_monotonic=time.monotonic(), **fields)
            with lock:
                file.write(json.dumps(row) + '\n')
                file.flush()
                print(json.dumps(row), flush=True)
        proc = None
        lid_thread = None
        if flip:
            # Read only this dedicated lid device, never touchscreen/key devices.
            proc = subprocess.Popen(adb + ['shell', f'timeout {args.seconds} getevent -lt {flip}'],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            def read_lid():
                for line in proc.stdout:
                    if 'SW_LID' in line:
                        counts['lid'] += 1
                        emit('lid', event=line.strip())
                    elif line.strip():
                        emit('lid_diagnostic', message=line.strip())
            lid_thread = threading.Thread(target=read_lid, daemon=True)
            lid_thread.start()
        emit('ready', duration=args.seconds, interval=args.interval, lid_device=flip, output=str(out))
        deadline = time.monotonic() + args.seconds
        try:
            while time.monotonic() < deadline:
                start = time.monotonic()
                dump = shell('dumpsys sensorservice')
                elapsed = time.monotonic() - start
                durations.append(elapsed)
                for key, label in [('vendor', 'Folding Angle'), ('public', 'hinge_angle')]:
                    rows = samples(dump, label)
                    if not rows:
                        if key not in last:
                            emit('missing', source=key)
                            last[key] = -1
                        continue
                    latest = rows[-1][0]
                    if key not in last or last[key] == -1:
                        last[key] = latest
                        if key == 'public':
                            public_angle = rows[-1][1]
                        emit('baseline', source=key, sensor_ts=latest, value=rows[-1][1])
                        continue
                    new = [row for row in rows if row[0] > last[key]]
                    if new:
                        counts[key] += len(new)
                        if key == 'public':
                            public_angle = rows[-1][1]
                        emit(key, first_sensor_ts=new[0][0], last_sensor_ts=latest,
                             samples=len(new), public_angle=public_angle,
                             angle_samples=new if key == 'public' else None,
                             dump_ms=round(elapsed * 1000, 1))
                    last[key] = latest
                remaining = args.interval - (time.monotonic() - start)
                if remaining > 0:
                    time.sleep(min(remaining, max(0, deadline - time.monotonic())))
        finally:
            if proc:
                proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill()
                if lid_thread:
                    lid_thread.join(timeout=3)
            emit('summary', counts=counts, dumps=len(durations),
                 median_dump_ms=round(statistics.median(durations) * 1000, 1) if durations else None,
                 max_dump_ms=round(max(durations) * 1000, 1) if durations else None)

if __name__ == '__main__':
    main()
