#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [[ "${NOMESSAGES_BUILD_GUARDED:-}" != 1 ]]; then
    # NOMESSAGES_GUARD_ARGS retunes the disk guard without editing this script: a CI
    # runner has a different cache budget and free-space floor than a developer
    # machine. Empty (the default) keeps guard-build.py's own defaults.
    guard_arguments=()
    if [[ -n "${NOMESSAGES_GUARD_ARGS:-}" ]]; then
        read -r -a guard_arguments <<< "${NOMESSAGES_GUARD_ARGS}"
    fi
    exec python3 "$PROJECT_DIR/scripts/guard-build.py" \
        ${guard_arguments[@]+"${guard_arguments[@]}"} -- bash "$0" "$@"
fi
TOOLS_DIR="${NOMESSAGES_TOOLS_DIR:-$PROJECT_DIR/.tools/toolchains}"
export JAVA_HOME="$TOOLS_DIR/jdk-21"
export ANDROID_HOME="$TOOLS_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$TOOLS_DIR/gradle-home"
# AGP generates and uses the debug signing keystore under ANDROID_USER_HOME.
# The default keeps it beside the toolchain, but an inherited value wins so a CI
# job can place that private key outside every cached directory. Same pattern as
# CARGO_TARGET_DIR and NOMESSAGES_NATIVE_DIR below.
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$TOOLS_DIR/android-user}"
export TMPDIR="$TOOLS_DIR/tmp"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$PROJECT_DIR/native/target}"
export NOMESSAGES_NATIVE_DIR="${NOMESSAGES_NATIVE_DIR:-$CARGO_TARGET_DIR/debug}"
mkdir -p "$ANDROID_USER_HOME" "$TMPDIR"

core_only=false
app_only=false
skip_gradle=false
skip_native=false
with_native_host=true
with_native_android=true
with_ndk=false
bootstrap=false
supported_abis=("arm64-v8a" "x86_64")
selected_abis=()
# Cargo parallelism. The default is every core the machine reports: the former
# hard-coded "--jobs 1" made the Rust stage take ~150 min, which does not fit a
# 180 min CI budget. CARGO_BUILD_JOBS (or --jobs) overrides it for constrained
# hosts; "auto" resolves to nproc here so Cargo never sees a non-numeric value.
cargo_jobs="${CARGO_BUILD_JOBS:-auto}"
gradle_workers="${NOMESSAGES_GRADLE_WORKERS:-1}"

usage() {
    cat <<'USAGE'
Usage: build-android.sh [options]

Toolchain
  --bootstrap              Install or repair JDK, Android SDK and Gradle home.
  --with-ndk               Install the NDK subset even without an Android native
                           build (AGP strips packaged .so files with it).

Native (Rust)
  --with-native-host       Build and test the host JNI library (default).
  --with-native-android    Cross-build the Android libraries (default).
  --no-native-host         Skip the host JNI library and its cargo tests.
  --no-native-android      Skip the Android cross-builds.
  --skip-native            Skip every native build (both of the above).
  --abi <arm64-v8a|x86_64> Restrict the Android cross-build to one ABI.
                           Repeatable; defaults to every supported ABI.
  --jobs <n|auto>          Cargo parallelism (default: CARGO_BUILD_JOBS, else
                           every core reported by nproc).

Gradle and verification
  --core-only              Run only the :core tasks (implies --no-native-android).
  --app-only               Run only the :app tasks, the Python suite and
                           verify-apk.py. It does not imply any native skip:
                           add --skip-native to reuse prebuilt jniLibs.
  --skip-gradle            Run no Gradle task, no Python suite and no APK check.

Environment: NOMESSAGES_TOOLS_DIR, CARGO_TARGET_DIR, NOMESSAGES_GUARD_ARGS,
CARGO_BUILD_JOBS, NOMESSAGES_GRADLE_WORKERS.
USAGE
}

abi_is_supported() {
    local candidate="$1" abi
    for abi in "${supported_abis[@]}"; do
        if [[ "$abi" == "$candidate" ]]; then
            return 0
        fi
    done
    return 1
}

abi_is_selected() {
    local candidate="$1" abi
    for abi in "${selected_abis[@]}"; do
        if [[ "$abi" == "$candidate" ]]; then
            return 0
        fi
    done
    return 1
}

while (($#)); do
    case "$1" in
        --core-only) core_only=true ;;
        --app-only) app_only=true ;;
        --skip-gradle) skip_gradle=true ;;
        --with-native-host) with_native_host=true ;;
        --with-native-android) with_native_android=true ;;
        --no-native-host) with_native_host=false ;;
        --no-native-android) with_native_android=false ;;
        --with-ndk) with_ndk=true ;;
        --skip-native) skip_native=true ;;
        --bootstrap) bootstrap=true ;;
        --abi)
            shift
            if (($# == 0)) || ! abi_is_supported "$1"; then
                printf 'Expected an ABI after --abi: %s\n' "${supported_abis[*]}" >&2
                exit 2
            fi
            if ! abi_is_selected "$1"; then
                selected_abis+=("$1")
            fi
            ;;
        --jobs)
            shift
            if (($# == 0)); then
                printf 'Expected a job count after --jobs\n' >&2
                exit 2
            fi
            cargo_jobs="$1"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

if [[ "$core_only" == true && "$app_only" == true ]]; then
    printf 'Use either --core-only or --app-only, not both\n' >&2
    exit 2
fi
if [[ "$core_only" == true ]]; then
    with_native_android=false
fi
if [[ "$skip_native" == true ]]; then
    with_native_host=false
    with_native_android=false
fi
if ((${#selected_abis[@]} == 0)); then
    selected_abis=("${supported_abis[@]}")
fi
if [[ "$cargo_jobs" == auto || "$cargo_jobs" == nproc ]]; then
    cargo_jobs="$(nproc)"
fi
if [[ ! "$cargo_jobs" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid Cargo job count: %s\n' "$cargo_jobs" >&2
    exit 2
fi
if [[ ! "$gradle_workers" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid Gradle worker count: %s\n' "$gradle_workers" >&2
    exit 2
fi
export CARGO_BUILD_JOBS="$cargo_jobs"

# Gradle work is split in two waves so the Python regression can sit exactly
# where it belongs: after :app:testDebugUnitTest and before packaging. A broken
# outbox query then stops the run without paying for assembly and lint.
jvm_test_tasks=()
package_tasks=()
if [[ "$skip_gradle" == false ]]; then
    if [[ "$app_only" == false ]]; then
        jvm_test_tasks+=(":core:test" ":core:nativeBridgeProbe" ":core:groupCapacityProbe")
    fi
    if [[ "$core_only" == false ]]; then
        jvm_test_tasks+=(":app:testDebugUnitTest")
        package_tasks+=(":app:assembleDebug" ":app:assembleDebugAndroidTest" ":app:lintDebug")
    fi
fi
# The Python regression and the packaged-APK inspection only make sense when the
# :app tasks produced fresh unit-test results and a debug APK.
run_app_checks=false
if [[ "$skip_gradle" == false && "$core_only" == false ]]; then
    run_app_checks=true
fi

needs_jvm_toolchain=false
if [[ "$skip_gradle" == false || "$with_ndk" == true || "$with_native_android" == true ]]; then
    needs_jvm_toolchain=true
fi

if [[ "$bootstrap" == true ]] || [[ "$needs_jvm_toolchain" == true && (
    ! -x "$JAVA_HOME/bin/javac" ||
    ! -f "$ANDROID_HOME/platforms/android-37.0/android.jar" ||
    ! -x "$ANDROID_HOME/build-tools/36.0.0/aapt2" ||
    ! -x "$ANDROID_HOME/platform-tools/adb") ]]; then
    # Called through "bash" on purpose: gradlew and scripts/*.sh are recorded in
    # Git with mode 100644, so a fresh clone cannot execute them directly. This
    # keeps the documented invocation working without a manual chmod; the index
    # modes still need to be corrected to 100755.
    bash "$PROJECT_DIR/scripts/bootstrap-tools.sh"
fi

if [[ "$with_native_android" == true || "$with_ndk" == true ]]; then
    bash "$PROJECT_DIR/scripts/bootstrap-tools.sh" --with-ndk
fi

mkdir -p "$GRADLE_USER_HOME" "$PROJECT_DIR/docs/development/build-logs"
build_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
log_file="$PROJECT_DIR/docs/development/build-logs/android-$build_stamp.log"

if [[ "$with_native_host" == true ]]; then
    cargo build \
        --manifest-path "$PROJECT_DIR/native/Cargo.toml" \
        --all-features \
        --locked \
        --jobs "$cargo_jobs" 2>&1 | tee "$PROJECT_DIR/docs/development/build-logs/native-host-build-$build_stamp.log"
    cargo test \
        --manifest-path "$PROJECT_DIR/native/Cargo.toml" \
        --all-features \
        --locked \
        --jobs "$cargo_jobs" 2>&1 | tee "$PROJECT_DIR/docs/development/build-logs/native-host-$build_stamp.log"
fi

if [[ "$with_native_android" == true ]]; then
    ndk_bin="$ANDROID_HOME/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android31-clang"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android31-clang"
    export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
    export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
    export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
    export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
    export RANLIB_aarch64_linux_android="$ndk_bin/llvm-ranlib"
    export RANLIB_x86_64_linux_android="$ndk_bin/llvm-ranlib"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

    for target_spec in "aarch64-linux-android:arm64-v8a" "x86_64-linux-android:x86_64"; do
        rust_target="${target_spec%%:*}"
        android_abi="${target_spec##*:}"
        if ! abi_is_selected "$android_abi"; then
            continue
        fi
        cargo build \
            --manifest-path "$PROJECT_DIR/native/Cargo.toml" \
            --all-features \
            --locked \
            --release \
            --target "$rust_target" \
            --jobs "$cargo_jobs" 2>&1 | tee "$PROJECT_DIR/docs/development/build-logs/native-$android_abi-$build_stamp.log"
        jni_directory="$PROJECT_DIR/app/src/main/jniLibs/$android_abi"
        mkdir -p "$jni_directory"
        cp "$CARGO_TARGET_DIR/$rust_target/release/libnomessages.so" "$jni_directory/libnomessages.so"
        "$ndk_bin/llvm-readelf" -h -l "$jni_directory/libnomessages.so" \
            > "$PROJECT_DIR/docs/development/build-logs/elf-$android_abi-$build_stamp.log"
    done
fi

cd "$PROJECT_DIR"

run_gradle() {
    (($# > 0)) || return 0
    # "bash ./gradlew" for the same reason as bootstrap-tools.sh above: the
    # wrapper is recorded with mode 100644 in the index.
    bash ./gradlew \
        --no-daemon \
        "--max-workers=$gradle_workers" \
        --stacktrace \
        "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR" \
        "$@" 2>&1 | tee -a "$log_file"
}

if [[ "$skip_gradle" == false ]]; then
    : > "$log_file"
fi

run_gradle ${jvm_test_tasks[@]+"${jvm_test_tasks[@]}"}

if [[ "$run_app_checks" == true ]]; then
    # Production SQL policy regression (task T4.4), immediately after
    # :app:testDebugUnitTest. It needs only CPython with the stdlib sqlite3
    # module: the suite reads `readyQuery` out of MessagingPolicy.kt by path and
    # replays it against an in-memory database. `set -o pipefail` makes a failing
    # assertion abort the build before anything is packaged.
    python3 -m unittest discover \
        -s "$PROJECT_DIR/app/src/test/python" \
        -t "$PROJECT_DIR/app/src/test/python" \
        -p 'test_*.py' --verbose 2>&1 \
        | tee "$PROJECT_DIR/docs/development/build-logs/python-tests-$build_stamp.log"
fi

run_gradle ${package_tasks[@]+"${package_tasks[@]}"}

if [[ "$run_app_checks" == true ]]; then
    python3 "$PROJECT_DIR/scripts/verify-apk.py" \
        "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" \
        --tools "$TOOLS_DIR" | tee "$PROJECT_DIR/docs/development/build-logs/apk-verification-$build_stamp.log"
fi

if [[ "$skip_gradle" == false ]]; then
    printf 'Build evidence: %s\n' "$log_file"
else
    printf 'Build evidence: %s\n' "$PROJECT_DIR/docs/development/build-logs"
fi
