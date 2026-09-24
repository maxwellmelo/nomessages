#!/usr/bin/env python3
"""Inspect the packaged APK without extracting files or launching the app."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit(f"APK verification failed: {message}")


def check_backup_rules(aapt, apk, manifest):
    reference = re.search(r"android:dataExtractionRules\b[^\n]*=@(0x[0-9a-f]+)\b", manifest)
    require(reference, "missing compiled data extraction rules reference")
    resources = subprocess.check_output([str(aapt), "dump", "resources", str(apk)], text=True)
    paths = set()
    selected = False
    for line in resources.splitlines():
        resource = re.match(r"\s*resource (0x[0-9a-f]+) (\S+)", line)
        if resource:
            selected = resource[1] == reference[1] and resource[2] == "xml/data_extraction_rules"
        if selected:
            entry = re.search(r"\(file\) (\S+) type=XML", line)
            if entry:
                paths.add(entry[1])
    require(paths, "data extraction rules reference does not resolve to XML")
    for path in sorted(paths):
        tree = subprocess.check_output(
            [str(aapt), "dump", "xmltree", "--file", path, str(apk)], text=True)
        roots, stack = [], []
        for line in tree.splitlines():
            element = re.match(r"(\s*)E: ([\w-]+)\b", line)
            if element:
                depth = len(element[1])
                while stack and stack[-1][0] >= depth:
                    stack.pop()
                node = {"name": element[2], "attributes": {}, "children": []}
                (stack[-1][1]["children"] if stack else roots).append(node)
                stack.append((depth, node))
            else:
                attribute = re.match(r'\s*A: (\w+)="([^"]*)"', line)
                if attribute and stack:
                    stack[-1][1]["attributes"][attribute[1]] = attribute[2]
        require(len(roots) == 1 and roots[0]["name"] == "data-extraction-rules",
                f"{path}: invalid extraction rules root")
        for mode in ("cloud-backup", "device-transfer"):
            sections = [node for node in roots[0]["children"] if node["name"] == mode]
            require(len(sections) == 1, f"{path}: missing or duplicate {mode}")
            exclusions = set()
            for node in sections[0]["children"]:
                require(node["name"] == "exclude", f"{path}: unexpected backup inclusion")
                if node["attributes"].get("path") == ".":
                    exclusions.add(node["attributes"].get("domain"))
            require({"root", "file", "database", "sharedpref", "external"} <= exclusions,
                    f"{path}: {mode} does not exclude all app storage domains")
    print("PASS compiled cloud-backup and device-transfer exclusions for app storage")


def check_elf(data, machine, name):
    require(data[:6] == b"\x7fELF\x02\x01", f"{name}: expected little-endian ELF64")
    require(len(data) >= 64, f"{name}: truncated ELF header")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    require(header[0] == 3 and header[1] == machine, f"{name}: wrong ELF type/architecture")
    phoff, shoff, phsize, phcount, shsize, shcount = (
        header[4], header[5], header[8], header[9], header[10], header[11]
    )
    require(phsize == 56 and phoff + phsize * phcount <= len(data), f"{name}: invalid program headers")
    loads = []
    for index in range(phcount):
        program = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
        if program[0] == 1:
            loads.append(program)
            require(program[7] >= 16384 and program[2] % 16384 == program[3] % 16384,
                    f"{name}: PT_LOAD is incompatible with 16 KiB pages")
    require(loads, f"{name}: no loadable segment")
    if not name.endswith("/libnomessages.so"):
        return
    require(shsize == 64 and shoff + shsize * shcount <= len(data), f"{name}: invalid section headers")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shsize) for i in range(shcount)]
    exports = set()
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        require(section[9] == 24 and section[6] < shcount, f"{name}: invalid dynamic symbol table")
        strings = sections[section[6]]
        require(strings[4] + strings[5] <= len(data) and section[4] + section[5] <= len(data),
                f"{name}: truncated dynamic symbols")
        names = data[strings[4]:strings[4] + strings[5]]
        for offset in range(section[4], section[4] + section[5], 24):
            symbol, info, visibility, defined, _, _ = struct.unpack_from("<IBBHQQ", data, offset)
            if defined and info >> 4 in (1, 2) and visibility & 3 in (0, 3):
                exports.add(names[symbol:].split(b"\0", 1)[0].decode("ascii"))
    expected = {"JNI_OnLoad"}
    expected.update("Java_dev_mx3_nomessages_core_crypto_NativeCrypto_native" + operation
                    for operation in ("Derive", "Hash", "Mac", "Open", "Random", "Seal", "Sign", "SigningKeyPair", "Verify"))
    expected.update("Java_dev_mx3_nomessages_core_nativebridge_" + bridge + "_transact"
                    for bridge in ("TorNative", "MlsNative"))
    require(expected <= exports, f"{name}: missing JNI exports: {sorted(expected - exports)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--tools", type=Path,
                        default=Path(os.environ.get("NOMESSAGES_TOOLS_DIR", str(Path(__file__).resolve().parents[1] / ".tools/toolchains"))))
    args = parser.parse_args()
    architectures = {"arm64-v8a": 183, "x86_64": 62}
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP entries")
        require(not any(name.lower().endswith((".dll", ".dylib")) for name in names),
                "desktop native libraries included")
        native = [name for name in names if name.endswith(".so")]
        for name in native:
            parts = name.split("/")
            require(len(parts) == 3 and parts[0] == "lib" and parts[1] in architectures,
                    f"unexpected native library: {name}")
            require("testing" not in parts[2], f"test JNI included: {name}")
            require(archive.getinfo(name).file_size <= 256 * 1024 * 1024, f"oversized library: {name}")
            check_elf(archive.read(name), architectures[parts[1]], name)
            print(f"PASS ELF64 / 16 KiB: {name}")
        for abi in architectures:
            for library in ("libnomessages.so", "libsignal_jni.so", "libsqlcipher.so"):
                require(f"lib/{abi}/{library}" in native, f"missing {abi}/{library}")
    aapt = args.tools / "android-sdk/build-tools/36.0.0/aapt2"
    manifest = subprocess.check_output([str(aapt), "dump", "xmltree", "--file", "AndroidManifest.xml", str(args.apk)], text=True)
    for attribute in ("allowBackup", "fullBackupContent", "usesCleartextTraffic"):
        require(re.search(rf"android:{attribute}\b[^\n]*=false|android:{attribute}\b[^\n]*0x0\b", manifest),
                f"{attribute} is not explicitly false")
    require(re.search(r"android:minSdkVersion\b[^\n]*=(?:31|0x1f)\b", manifest), "minSdk must be 31")
    package = re.search(r'\bpackage="([^"]+)"', manifest)
    require(package is not None and package[1] in ("dev.mx3.nomessages", "dev.mx3.nomessages.debug"),
            "unexpected application package")
    for unwanted in ("androidx.emoji2.text.EmojiCompatInitializer",
                     "androidx.compose.ui.tooling.PreviewActivity",
                     "androidx.activity.ComponentActivity"):
        require(unwanted not in manifest, f"unexpected automatic initializer or auxiliary activity: {unwanted}")
    print("PASS packaged manifest: API 31 / no backup / no cleartext / no font download initializer or auxiliary activities")
    check_backup_rules(aapt, args.apk, manifest)
    zipalign = args.tools / "android-sdk/build-tools/36.0.0/zipalign"
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(args.apk)], check=True)
    print("PASS ZIP alignment for 16 KiB pages")
    apksigner = args.tools / "android-sdk/build-tools/36.0.0/apksigner"
    signing_env = dict(os.environ, JAVA_HOME=str(args.tools / "jdk-21"))
    subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(args.apk)],
                   check=True, env=signing_env)
    print("PASS APK signature")
    with args.apk.open("rb") as apk:
        digest = hashlib.file_digest(apk, "sha256").hexdigest()
    print(f"SHA-256 {digest}  {args.apk}")


if __name__ == "__main__":
    main()
