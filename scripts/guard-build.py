#!/usr/bin/env python3
"""Run a build with project-cache and free-disk monitoring; never delete data."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

GIB = 1024 ** 3
PROJECT = Path(__file__).resolve().parents[1]


def storage():
    candidates = [PROJECT / name for name in (
        '.tools', '.gradle', '.kotlin', '.codegraph', 'native/target',
        'app/build', 'core/build', 'build', 'artifacts',
        'app/src/main/jniLibs', 'docs/development/build-logs',
    )]
    for variable in ('NOMESSAGES_TOOLS_DIR', 'CARGO_TARGET_DIR', 'GRADLE_USER_HOME'):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]))
    roots = []
    for path in sorted({p.resolve() for p in candidates if p.exists()}, key=lambda p: len(p.parts)):
        if not any(path.is_relative_to(parent) for parent in roots):
            roots.append(path)
    used = 0
    if roots:
        result = subprocess.run(
            ['du', '-s', '-B1', '-c', '--', *map(str, roots)],
            capture_output=True, text=True, timeout=20,
        )
        # Atomic cache replacements may disappear while du traverses them.
        if result.returncode and any('No such file or directory' not in line
                                     for line in result.stderr.splitlines()):
            raise RuntimeError('Cannot measure build storage: ' + result.stderr.strip())
        used = int(result.stdout.splitlines()[-1].split()[0])
    free = min(os.statvfs(path).f_bavail * os.statvfs(path).f_frsize
               for path in [PROJECT, *roots])
    return used, free


def process_record(pid):
    try:
        fields = (Path('/proc') / str(pid) / 'stat').read_text().rsplit(') ', 1)[1].split()
        return int(fields[1]), fields[19], fields[0]  # parent, creation time, state
    except (OSError, IndexError, ValueError):
        return None


class BuildProcesses:
    """Track descendants across setsid/reparenting, checking PID creation times."""
    def __init__(self, pid):
        record = process_record(pid)
        self.owned = {pid: record[1]} if record else {}

    def live(self):
        records = {}
        children = {}
        for path in Path('/proc').iterdir():
            if path.name.isdigit():
                pid = int(path.name)
                record = process_record(pid)
                if record:
                    records[pid] = record
                    children.setdefault(record[0], []).append(pid)
        pending = [pid for pid, start in self.owned.items()
                   if pid in records and records[pid][1] == start]
        seen = set(pending)
        while pending:
            for pid in children.get(pending.pop(), []):
                if pid not in seen:
                    seen.add(pid)
                    self.owned[pid] = records[pid][1]
                    pending.append(pid)
        return [pid for pid in seen if records[pid][2] not in ('Z', 'X')]

    def send(self, pid, signum):
        record = process_record(pid)
        if record and record[1] == self.owned.get(pid):
            try:
                os.kill(pid, signum)
            except ProcessLookupError:
                pass

    def stop(self):
        # Freeze before stopping; collect again to catch children forked during
        # the first traversal. Never signal an unrelated or recycled PID.
        for _ in range(2):
            for pid in self.live():
                self.send(pid, signal.SIGSTOP)
        for pid in self.live():
            self.send(pid, signal.SIGTERM)
            self.send(pid, signal.SIGCONT)
        deadline = time.monotonic() + 4
        while self.live() and time.monotonic() < deadline:
            time.sleep(0.1)
        for pid in self.live():
            self.send(pid, signal.SIGKILL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-gib', type=float, default=8)
    parser.add_argument('--min-free-gib', type=float, default=10)
    parser.add_argument('--headroom-gib', type=float, default=1)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--watch-pid', type=int, help='Monitor an already-running process inside this project')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not (0 < args.headroom_gib < args.max_gib and args.min_free_gib >= 0):
        parser.error('Require 0 < headroom < max and min-free >= 0')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if args.watch_pid and command:
        parser.error('Use either watch-pid or a command')
    if args.watch_pid and not (Path('/proc') / str(args.watch_pid) / 'cwd').resolve().is_relative_to(PROJECT):
        parser.error('The watched process must belong to this project')
    if not args.check_only and not args.watch_pid and not command:
        parser.error('Pass the build command after --')

    def check(report=False):
        used, free = storage()
        message = f'Build storage: {used / GIB:.2f} GiB; free: {free / GIB:.2f} GiB'
        if report:
            print(message, flush=True)
        if used >= (args.max_gib - args.headroom_gib) * GIB:
            raise RuntimeError(message + '; cache stop threshold reached')
        if free <= (args.min_free_gib + args.headroom_gib) * GIB:
            raise RuntimeError(message + '; free-space stop threshold reached')

    child = None
    processes = BuildProcesses(args.watch_pid) if args.watch_pid else None
    try:
        check(report=True)
        if args.check_only:
            return 0
        if not args.watch_pid:
            environment = dict(os.environ, NOMESSAGES_BUILD_GUARDED='1')
            child = subprocess.Popen(command, cwd=PROJECT, env=environment, start_new_session=True)
            processes = BuildProcesses(child.pid)

        def interrupted(signum, _frame):
            raise KeyboardInterrupt(f'Signal {signum}')

        signal.signal(signal.SIGTERM, interrupted)
        last_report = time.monotonic()
        while (child is not None and child.poll() is None) or processes.live():
            processes.live()
            report = time.monotonic() - last_report >= 60
            check(report)
            if report:
                last_report = time.monotonic()
            try:
                if child is not None and child.poll() is None:
                    child.wait(timeout=1)
                else:
                    time.sleep(1)
            except subprocess.TimeoutExpired:
                pass
        check(report=True)
        return child.returncode if child is not None else 0
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        print(f'BUILD STOPPED: {error}', file=sys.stderr, flush=True)
        if processes is not None:
            processes.stop()
        if child is not None:
            child.wait()
        return 75


if __name__ == '__main__':
    sys.exit(main())
