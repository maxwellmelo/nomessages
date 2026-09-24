#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
TOOLS_DIR="${NOMESSAGES_TOOLS_DIR:-$PROJECT_DIR/.tools/toolchains}"
SDK_ROOT="$TOOLS_DIR/android-sdk"
JDK_ROOT="$TOOLS_DIR/jdk-21"
DOWNLOAD_DIR="$TOOLS_DIR/downloads"

JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK_SHA256="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMDLINE_TOOLS_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
CMDLINE_TOOLS_VERSION="23.0"
PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip"
PLATFORM_TOOLS_SHA1="477254aa5f903c15cf51001717bdf347fb6b53e0"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r27d-linux.zip"
NDK_SHA1="22105e410cf29afcf163760cc95522b9fb981121"
NDK_VERSION="27.3.13750724"

with_ndk=false
while (($#)); do
    case "$1" in
        --with-ndk) with_ndk=true ;;
        -h|--help)
            printf 'Usage: %s [--with-ndk]\n' "$0"
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            exit 2
            ;;
    esac
    shift
done

for command_name in curl flock tar unzip sha1sum sha256sum; do
    command -v "$command_name" >/dev/null || {
        printf 'Missing required command: %s\n' "$command_name" >&2
        exit 1
    }
done

mkdir -p "$TOOLS_DIR" "$DOWNLOAD_DIR" "$SDK_ROOT/cmdline-tools"
exec 9>"$TOOLS_DIR/bootstrap.lock"
flock 9

if [[ ! -x "$JDK_ROOT/bin/javac" ]]; then
    jdk_archive="$DOWNLOAD_DIR/temurin-jdk21.tar.gz"
    curl --fail --location --retry 3 --output "$jdk_archive" "$JDK_URL"
    printf '%s  %s\n' "$JDK_SHA256" "$jdk_archive" | sha256sum --check --status
    jdk_stage="$(mktemp -d "$TOOLS_DIR/.jdk-stage.XXXXXX")"
    tar -xzf "$jdk_archive" -C "$jdk_stage" --strip-components=1
    rm -f "$jdk_archive"
    mv "$jdk_stage" "$JDK_ROOT"
fi

cmdline_root="$SDK_ROOT/cmdline-tools/$CMDLINE_TOOLS_VERSION"
if [[ ! -x "$cmdline_root/bin/sdkmanager" ]]; then
    cmdline_archive="$DOWNLOAD_DIR/android-command-line-tools.zip"
    curl --fail --location --retry 3 --output "$cmdline_archive" "$CMDLINE_TOOLS_URL"
    printf '%s  %s\n' "$CMDLINE_TOOLS_SHA1" "$cmdline_archive" | sha1sum --check --status
    cmdline_stage="$(mktemp -d "$TOOLS_DIR/.cmdline-stage.XXXXXX")"
    unzip -q "$cmdline_archive" -d "$cmdline_stage"
    rm -f "$cmdline_archive"
    mv "$cmdline_stage/cmdline-tools" "$cmdline_root"
    rmdir "$cmdline_stage"
fi

export JAVA_HOME="$JDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

if [[ ! -f "$SDK_ROOT/platforms/android-37.0/package.xml" || ! -x "$SDK_ROOT/build-tools/36.0.0/aapt2" ]]; then
    set +o pipefail
    yes | "$cmdline_root/bin/sdkmanager" --sdk_root="$SDK_ROOT" --licenses >/dev/null
    set -o pipefail

    # SDK Manager registers package.xml, required for decimal API levels such
    # as 37.0. Extracting the platform ZIP alone causes AGP to install it again.
    "$cmdline_root/bin/sdkmanager" \
        --sdk_root="$SDK_ROOT" \
        "platforms;android-37.0" \
        "build-tools;36.0.0"
fi

if [[ ! -x "$SDK_ROOT/platform-tools/adb" ]]; then
    platform_tools_archive="$DOWNLOAD_DIR/platform-tools_r37.0.1-linux.zip"
    curl --fail --location --retry 3 --output "$platform_tools_archive" "$PLATFORM_TOOLS_URL"
    printf '%s  %s\n' "$PLATFORM_TOOLS_SHA1" "$platform_tools_archive" | sha1sum --check --status
    unzip -oq "$platform_tools_archive" -d "$SDK_ROOT"
    unlink "$platform_tools_archive"
fi

if [[ "$with_ndk" == true ]]; then
    ndk_root="$SDK_ROOT/ndk/$NDK_VERSION"
    ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
    if [[ ! -x "$ndk_bin/aarch64-linux-android31-clang" || ! -x "$ndk_bin/x86_64-linux-android31-clang" ]]; then
        ndk_archive="$DOWNLOAD_DIR/android-ndk-r27d-linux.zip"
        if ! printf '%s  %s\n' "$NDK_SHA1" "$ndk_archive" | sha1sum --check --status 2>/dev/null; then
            curl --fail --location --retry 3 --output "$ndk_archive" "$NDK_URL"
            printf '%s  %s\n' "$NDK_SHA1" "$ndk_archive" | sha1sum --check --status
        fi
        ndk_stage="$(mktemp -d "$TOOLS_DIR/.ndk-stage.XXXXXX")"
        ndk_prefix="android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64"
        unzip -q "$ndk_archive" -d "$ndk_stage" \
            "android-ndk-r27d/source.properties" \
            "$ndk_prefix/bin/clang" \
            "$ndk_prefix/bin/clang++" \
            "$ndk_prefix/bin/clang-18" \
            "$ndk_prefix/bin/ld" \
            "$ndk_prefix/bin/ld.lld" \
            "$ndk_prefix/bin/lld" \
            "$ndk_prefix/bin/llvm-ar" \
            "$ndk_prefix/bin/llvm-config" \
            "$ndk_prefix/bin/llvm-nm" \
            "$ndk_prefix/bin/llvm-objcopy" \
            "$ndk_prefix/bin/llvm-objdump" \
            "$ndk_prefix/bin/llvm-ranlib" \
            "$ndk_prefix/bin/llvm-readelf" \
            "$ndk_prefix/bin/llvm-readobj" \
            "$ndk_prefix/bin/llvm-strip" \
            "$ndk_prefix/bin/yasm" \
            "$ndk_prefix/bin/aarch64-linux-android31-clang" \
            "$ndk_prefix/bin/aarch64-linux-android31-clang++" \
            "$ndk_prefix/bin/x86_64-linux-android31-clang" \
            "$ndk_prefix/bin/x86_64-linux-android31-clang++" \
            "$ndk_prefix/lib/libc++.so.1" \
            "$ndk_prefix/lib/libedit.so.0" \
            "$ndk_prefix/lib/libncurses.so.6" \
            "$ndk_prefix/lib/libunwind.so" \
            "$ndk_prefix/lib/libxml2.so.2" \
            "$ndk_prefix/lib/x86_64-unknown-linux-gnu/*" \
            "$ndk_prefix/lib/clang/18/include/*" \
            "$ndk_prefix/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a" \
            "$ndk_prefix/lib/clang/18/lib/linux/libclang_rt.builtins-x86_64-android.a" \
            "$ndk_prefix/lib/clang/18/lib/linux/aarch64/libatomic.a" \
            "$ndk_prefix/lib/clang/18/lib/linux/aarch64/libunwind.a" \
            "$ndk_prefix/lib/clang/18/lib/linux/x86_64/libatomic.a" \
            "$ndk_prefix/lib/clang/18/lib/linux/x86_64/libunwind.a" \
            "$ndk_prefix/sysroot/*"
        mkdir -p "$SDK_ROOT/ndk"
        mv "$ndk_stage/android-ndk-r27d" "$ndk_root"
        rmdir "$ndk_stage"
        unlink "$ndk_archive"
    fi

    rustup target add aarch64-linux-android x86_64-linux-android
    "$ndk_bin/clang" --version
fi

escaped_sdk_root=${SDK_ROOT// /\\ }
local_properties_stage="$(mktemp "$PROJECT_DIR/.local.properties.XXXXXX")"
printf '# Generated by scripts/bootstrap-tools.sh\nsdk.dir=%s\n' "$escaped_sdk_root" > "$local_properties_stage"
mv "$local_properties_stage" "$PROJECT_DIR/local.properties"

printf 'NoMessages Android tools ready.\n'
printf 'JAVA_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$JAVA_HOME" "$ANDROID_SDK_ROOT"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
