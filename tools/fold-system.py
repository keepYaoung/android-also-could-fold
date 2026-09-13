#!/usr/bin/env python3
"""Build/test/run the temporary ADB-shell fold compositor prototype."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
JDK = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
OUT = ROOT / 'build/system-backend'
MAIN = 'dev.tommy.foldshell.system.FoldShell'

def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)

def build():
    classes = OUT / 'classes'
    dex = OUT / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    android = SDK / 'platforms/android-36/android.jar'
    source = ROOT / 'system/src/dev/tommy/foldshell/system'
    run([JDK / 'bin/javac', '-cp', android, '-d', classes, *source.glob('*.java'),
         ROOT / 'system/tests/FoldMotionTest.java'])
    run([JDK / 'bin/java', '-cp', classes, 'FoldMotionTest'])
    env = dict(os.environ, JAVA_HOME=str(JDK))
    run([SDK / 'build-tools/36.0.0/d8', '--min-api', '31', '--lib', android,
         '--output', dex, *classes.glob('dev/**/*.class')], env=env)
    print(dex / 'classes.dex', flush=True)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('command', choices=['build', 'run', 'stop', 'probe'])
parser.add_argument('--v2', action='store_true', help='cover snapshot and black gradient instead of live blur')
parser.add_argument('--v3', action='store_true', help='flat screen with a gyro-driven black mask; no capture')
parser.add_argument('--no-edge-blur', action='store_true', help='temporary V2 comparison without additional border blur')
parser.add_argument('--early', action='store_true', help='experimental event-timestamp start detection')
parser.add_argument('--serial', help='ADB serial, required when multiple devices are attached')
parser.add_argument('--seconds', type=int, default=600, help='auto-stop after 1..3600 seconds')
args = parser.parse_args()
if not 1 <= args.seconds <= 3600:
    parser.error('--seconds must be 1..3600')
if args.v2 and args.v3:
    parser.error('choose only one of --v2 / --v3')
adb = [SDK / 'platform-tools/adb']
if args.serial:
    adb += ['-s', args.serial]
if args.command == 'stop':
    # Check process identity before using a PID that may have been recycled.
    run([*adb, 'shell', """fold_pid=$(cat /data/local/tmp/fold-transition-system.lock 2>/dev/null)
case "$fold_pid" in ''|*[!0-9]*) echo 'No recorded helper'; exit 0;; esac
fold_cmd=$(tr '\\000' ' ' < /proc/"$fold_pid"/cmdline 2>/dev/null)
case "$fold_cmd" in *'app_process /system/bin dev.tommy.foldshell.system.FoldShell '*)
kill -TERM "$fold_pid";; *) echo 'Helper is not running';; esac"""])
    raise SystemExit(0)
build()
if args.command in ('run', 'probe'):
    adb = [SDK / 'platform-tools/adb']
    if args.serial:
        adb += ['-s', args.serial]
    dex_file = OUT / 'dex/classes.dex'
    digest = hashlib.sha256(dex_file.read_bytes()).hexdigest()[:16]
    remote = f'/data/local/tmp/fold-transition-system-{digest}.dex'
    # Never overwrite a DEX mapped by an already-running app_process.
    run([*adb, 'push', dex_file, remote])
    entry = MAIN if args.command == 'run' else 'dev.tommy.foldshell.system.FoldSensorProbe'
    duration = args.seconds if args.command == 'run' else min(args.seconds, 120)
    print('Physical folds only; automatic expiry.' if args.command == 'run' else 'Read-only sensor probe; no visual effects.', flush=True)
    mode = ' early' if args.early and args.command == 'run' else ''
    if args.v2 and args.command == 'run': mode += ' v2'
    if args.v3 and args.command == 'run': mode += ' v3'
    if args.no_edge_blur and args.command == 'run': mode += ' no-edge-blur'
    run([*adb, 'shell', f'CLASSPATH={remote} app_process /system/bin {entry} {duration}{mode}'])
