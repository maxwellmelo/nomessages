#!/usr/bin/env bash
# Read-only device evidence collection. Does not install, unlock, clear, stop, or erase apps.
set -euo pipefail
exec python3 - "$@" <<'PY'
import argparse, datetime, hashlib, json, os, pathlib, re, selectors, shutil, subprocess, sys, tarfile, time

parser = argparse.ArgumentParser(description='Collect read-only NoMessages evidence from an explicitly selected authorized test device. Never installs, unlocks, clears, or stops an app.')
parser.add_argument('--serial', required=True, help='Exact adb device serial; never selects a default device')
parser.add_argument('--device-ready', action='store_true', help='Attest this is an authorized test device, OS is unlocked and USB debugging authorized')
parser.add_argument('--package', default='dev.mx3.nomessages.debug')
parser.add_argument('--app-state', choices=['locked','unlocked','unknown'], default='unknown', help='Operator observation only, separate from OS screen lock')
parser.add_argument('--marker-file', type=pathlib.Path, help='UTF-8 harmless unique test marker, not a password; content is never printed')
parser.add_argument('--output', type=pathlib.Path, default=None, help='New evidence directory; existing nonempty directories are rejected')
parser.add_argument('--max-dump-mib', type=int, default=512)
args = parser.parse_args()
if not args.device_ready:
    parser.error('--device-ready is required: authorize the test device and unlock its OS manually; the app can remain locked')
if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}', args.serial): parser.error('invalid adb serial')
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', args.package): parser.error('invalid package name')
if not 1 <= args.max_dump_mib <= 2048: parser.error('--max-dump-mib must be between 1 and 2048')
marker = None
if args.marker_file:
    try:
        if not 8 <= args.marker_file.stat().st_size <= 4096: raise ValueError("marker must contain 8 to 4096 UTF-8 bytes")
        marker = args.marker_file.read_bytes()
        if not 8 <= len(marker) <= 4096: raise ValueError('marker must contain 8 to 4096 UTF-8 bytes')
        marker.decode('utf-8')
    except (OSError, UnicodeError, ValueError) as error: parser.error(str(error))
adb = shutil.which('adb')
if not adb: parser.error('adb not found on PATH')
os.umask(0o077)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out = args.output or pathlib.Path('artifacts/threat-gates') / stamp
if out.exists() and (not out.is_dir() or any(out.iterdir())): parser.error('output must be a new or empty directory')
out.mkdir(parents=True, exist_ok=True)
checks = []
def check(name, status, detail):
    checks.append(dict(name=name, status=status, detail=detail))

def capture(name, command, limit=8*1024*1024, timeout=45):
    """Bound stdout/stderr and elapsed time, storing artifacts without echoing their contents."""
    target = out / name
    errors = out / (name + '.stderr')
    proc = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ, 'stdout')
    selector.register(proc.stderr, selectors.EVENT_READ, 'stderr')
    sizes = {'stdout':0, 'stderr':0}
    complete = True
    started = time.monotonic()
    with target.open('wb') as output, errors.open('wb') as error_file:
        try:
            while selector.get_map():
                if time.monotonic()-started > timeout:
                    complete = False; break
                for key, _ in selector.select(timeout=0.2):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj); continue
                    stream = key.data
                    sizes[stream] += len(chunk)
                    budget = limit if stream == 'stdout' else 65536
                    if sizes[stream] > budget:
                        complete = False; break
                    (output if stream == 'stdout' else error_file).write(chunk)
                if not complete: break
        finally:
            if proc.poll() is None and not complete: proc.kill()
            try: result = proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill(); proc.wait(); result = -1; complete = False
            selector.close(); proc.stdout.close(); proc.stderr.close()
    return complete and result == 0

def device(name, *command, **kwargs):
    return capture(name, [adb, '-s', args.serial, *command], **kwargs)

def contents(name): return (out/name).read_text(errors='replace') if (out/name).exists() else ''

connected = device('device-state.txt', 'get-state') and contents('device-state.txt').strip() == 'device'
check('explicit_device', 'PASSED' if connected else 'FAILED', 'Selected serial is accessible' if connected else 'Selected serial is unavailable or unauthorized; no app collection attempted')
if connected:
    device('package.txt', 'shell', 'dumpsys', 'package', args.package)
    device('apk-path.txt', 'shell', 'pm', 'path', args.package)
    apk_paths = [line.removeprefix('package:') for line in contents('apk-path.txt').splitlines() if line.startswith('package:/')]
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    aapt_candidates = sorted(pathlib.Path(sdk).glob('build-tools/*/aapt2')) if sdk else []
    aapt = shutil.which('aapt2') or (str(aapt_candidates[-1]) if aapt_candidates else None)
    apk = next((path for path in apk_paths if path.endswith('/base.apk') and re.fullmatch(r'/[A-Za-z0-9_./=+~-]+', path)), None)
    manifest_ok = False
    if apk and aapt:
        if device('base.apk', 'exec-out', 'cat', apk, limit=256*1024*1024, timeout=60):
            manifest_ok = capture('manifest.txt', [aapt, 'dump', 'xmltree', str(out/'base.apk'), '--file', 'AndroidManifest.xml'])
    check('manifest_collection', 'PASSED' if manifest_ok else 'NOT_RUN', 'Compiled APK manifest captured' if manifest_ok else 'Package snapshot exists; compiled manifest needs readable APK and aapt2')
    device('windows.txt', 'shell', 'dumpsys', 'window', 'windows')
    device('notifications.txt', 'shell', 'dumpsys', 'notification')
    device('processes.txt', 'shell', 'ps', '-A', '-o', 'USER,PID,PPID,NAME')
    device('sockets.txt', 'shell', 'ss', '-tunap')
    device('app-pids.txt', 'shell', 'pidof', args.package)
    tor_pid_ok = device('tor-pids.txt', 'shell', 'pidof', args.package + ':tor')
    device('shell-identity.txt', 'shell', 'id')
    dumped = device('app-data.tar', 'exec-out', 'run-as', args.package, 'tar', '-c', '-f', '-', '.', limit=args.max_dump_mib*1024*1024, timeout=60)
    check('private_dump_collection', 'PASSED' if dumped else 'NOT_RUN', 'Complete run-as archive collected' if dumped else 'run-as unavailable, app absent, archive exceeded budget, or collection failed; partial archive is not proof')
    found = False
    lengths = {}
    inspected = 0
    archive_ok = dumped
    if dumped:
        try:
            total = 0
            entries = 0
            names = set()
            with tarfile.open(out/'app-data.tar', mode='r:') as archive:
                for entry in archive:
                    entries += 1
                    if entries > 10000 or entry.size < 0: raise ValueError('archive entry budget exceeded')
                    if not entry.isfile(): continue
                    if entry.name in names: raise ValueError('duplicate archive entry')
                    names.add(entry.name)
                    total += entry.size
                    if total > args.max_dump_mib*1024*1024: raise ValueError('archive expanded budget exceeded')
                    name = entry.name.removeprefix('./')
                    if name in ('files/vault/real.db','files/vault/decoy.db'):
                        lengths[name] = entry.size
                    inspected += 1
                    source = archive.extractfile(entry)
                    if source is None: raise ValueError('unreadable archive entry')
                    # No extraction, path traversal or symlink following; scan only regular entry streams.
                    previous = b''
                    while True:
                        data = source.read(65536)
                        if not data: break
                        if marker and marker in previous+data: found = True
                        previous = data[-(len(marker)-1):] if marker else b''
                    source.close()
        except (tarfile.TarError, OSError, ValueError):
            archive_ok = False
    check('archive_inspection', 'PASSED' if archive_ok else 'NOT_RUN', f'{inspected} regular entries examined without extraction' if archive_ok else 'Archive unavailable or incomplete')
    if not marker or not archive_ok:
        check('plaintext_marker_scan', 'NOT_RUN', 'Requires a complete archive and caller-provided marker file')
    else:
        check('plaintext_marker_scan', 'FAILED' if found and args.app_state=='locked' else ('PASSED' if not found else 'NOT_RUN'), 'Marker detected; contents suppressed' if found else 'Marker absent from this archive only; this does not prove encryption or resistance to wrong keys')
    (out/'database-lengths.json').write_text(json.dumps(lengths, indent=2)+'\n')
    real = lengths.get('files/vault/real.db'); decoy = lengths.get('files/vault/decoy.db')
    equal = archive_ok and real is not None and decoy is not None and real>0 and real==decoy
    check('equal_database_lengths', 'PASSED' if equal else 'NOT_RUN', 'Database-file lengths are equal in this snapshot; encryption was not evaluated' if equal else 'Both lengths were not available/equal; no acceptable size tolerance was asserted')
    check('secure_window_snapshot', 'NOT_RUN', 'Window flags collected; verify the target window and screenshot/recents behavior manually')
    check('notification_privacy', 'NOT_RUN', 'Redacted notification snapshot collected; empty title/body cannot be proven from redaction')
    tor_present = tor_pid_ok and bool(re.fullmatch(r'[0-9]+(?:\s+[0-9]+)*', contents('tor-pids.txt').strip()))
    check('tor_process_after_lock', 'FAILED' if tor_present and args.app_state=='locked' else 'NOT_RUN', 'Tor process observed while operator reports app locked' if tor_present and args.app_state=='locked' else 'PID snapshot alone cannot prove socket closure')
    check('zero_owned_sockets', 'NOT_RUN', 'ss/process snapshots collected; Android shell usually cannot attribute every app socket. Privileged capture is a separate authorized test')
    flags = contents('package.txt')
    check('backup_manifest_snapshot', 'FAILED' if 'ALLOW_BACKUP' in flags else 'NOT_RUN', 'Package declares ALLOW_BACKUP' if 'ALLOW_BACKUP' in flags else 'No ALLOW_BACKUP flag observed; extraction rules and actual backup refusal still need verification')
    check('wrong_key_sqlcipher', 'NOT_RUN', 'Must open a copied database with the same SQLCipher build using empty/PIN/password keys and confirm failure; strings scan is not that challenge')
else:
    for name in ['private_dump_collection','plaintext_marker_scan','equal_database_lengths','secure_window_snapshot','notification_privacy','zero_owned_sockets','wrong_key_sqlcipher']:
        check(name, 'NOT_RUN', 'Device unavailable')

gates = [dict(gate=i, status='NOT_RUN', detail='Requires the procedure and evidence in docs/release-checklist.md') for i in range(1,14)]
if any(c['name']=='plaintext_marker_scan' and c['status']=='FAILED' for c in checks): gates[0]['status']='FAILED'
if any(c['name']=='equal_database_lengths' and c['status']=='PASSED' for c in checks):
    gates[1].update(status='PASSED', detail='Equal encrypted database lengths observed in this snapshot only')
if any(c['name']=='tor_process_after_lock' and c['status']=='FAILED' for c in checks): gates[9]['status']='FAILED'
if any(c['name']=='backup_manifest_snapshot' and c['status']=='FAILED' for c in checks): gates[6]['status']='FAILED'
report = dict(schema=1, collected_utc=stamp, package=args.package, serial=args.serial, app_state_operator_report=args.app_state,
              release_status='FAILED' if any(c['status']=='FAILED' for c in checks) else 'NOT_RUN',
              marker_sha256=hashlib.sha256(marker).hexdigest() if marker else None, checks=checks, specification_gates=gates,
              note='Collection is read-only. No automated result is a full security audit; unknown or unexecuted tests remain NOT_RUN.')
(out/'report.json').write_text(json.dumps(report, indent=2)+'\n')
print('Evidence directory:', out)
for item in checks: print(item['status'] + ' ' + item['name'])
print('Release status:', report['release_status'])
sys.exit(1 if report['release_status']=='FAILED' else 0)
PY
