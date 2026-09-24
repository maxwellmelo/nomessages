#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
TOOLS_DIR="${NOMESSAGES_TOOLS_DIR:-$PROJECT_DIR/.tools/toolchains}"
NATIVE_DIR="${NOMESSAGES_NATIVE_DIR:-${CARGO_TARGET_DIR:-$PROJECT_DIR/native/target}/debug}"

if [[ "${NOMESSAGES_LIVE_TOR:-}" != 1 ]]; then
    printf 'This opt-in probe opens Tor sockets and sends random bytes only to itself.\nSet NOMESSAGES_LIVE_TOR=1 to run it.\n' >&2
    exit 2
fi
if [[ ! -s "$NATIVE_DIR/libnomessages.so" || ! -f "$PROJECT_DIR/core/build/classes/kotlin/main/dev/mx3/nomessages/core/nativebridge/TorNative.class" ]]; then
    printf 'Build the full host JNI library and :core:classes before running this probe.\n' >&2
    exit 2
fi

stdlib=$(python3 - "$PROJECT_DIR" "$TOOLS_DIR" <<'PY'
from pathlib import Path
import sys
import tomllib
project, tools = map(Path, sys.argv[1:])
with (project / "gradle/libs.versions.toml").open("rb") as source:
    version = tomllib.load(source)["versions"]["kotlin"]
cache = tools / "gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version
jars = list(cache.glob(f"*/kotlin-stdlib-{version}.jar"))
if len(jars) != 1:
    raise SystemExit("Pinned Kotlin standard library is missing from the build cache")
print(jars[0])
PY
)
probe_root=$(mktemp -d "${TMPDIR:-/tmp}/nomessages-live-probe.XXXXXX")
trap 'rm -rf -- "$probe_root"' EXIT

# No Gradle daemon or compiler is needed during this network experiment.
# The outer deadline also bounds an unresponsive native call. It is derived from the probe's own
# budgets instead of guessed, because a deadline shorter than the sum of them cannot fail for a
# reason that says anything about Tor: the harness would simply cut a healthy run short.
#   180s  bootstrap inside tor::start
# + 300s  descriptor publication (TorNative.MAX_READY_TIMEOUT_MILLIS)
# + 240s  self-connect retries after publication
# +  60s  two 30s polls for the frame and its acknowledgement
# + 120s  margin for JVM start, Arti teardown and a slow directory fetch
probe_deadline=$((180 + 300 + 240 + 60 + 120))
timeout --signal=TERM --kill-after=10s "${probe_deadline}s" \
    "$TOOLS_DIR/jdk-21/bin/java" -Xmx128m \
    "-Djava.io.tmpdir=$probe_root" \
    "-Djava.library.path=$NATIVE_DIR" \
    --class-path "$PROJECT_DIR/core/build/classes/kotlin/main:$stdlib" \
    "$PROJECT_DIR/core/src/test/native/TorDeliveryProbe.java"
