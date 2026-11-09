#!/usr/bin/env python3
"""Inspect an APK without running its code; emit metadata suitable for review."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def inspect_apk(path: Path, sdk: Path):
    tools = sorted((sdk / "build-tools").iterdir(), key=lambda p: tuple(int(x) for x in p.name.split(".") if x.isdigit()))[-1]
    env = os.environ.copy()
    result = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(path)], capture_output=True, text=True, check=True, env=env)
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", result.stdout)
    if not certificates:
        raise ValueError("APK has no verified signer")
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(path)], capture_output=True, text=True, check=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not package:
        raise ValueError("Cannot read package metadata")
    minimum = re.search(r"sdkVersion:'(\d+)'", badging)
    with zipfile.ZipFile(path) as archive:
        abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")})
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    digest = checksum.hexdigest()
    return {"packageName": package[1], "versionCode": int(package[2]), "versionName": package[3],
            "minSdk": int(minimum[1]) if minimum else 1, "abis": abis,
            "certificateSha256": sorted(set(certificates)), "sha256": digest, "size": path.stat().st_size}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", ".tooling/android-sdk")))
    args = parser.parse_args()
    print(json.dumps(inspect_apk(args.apk, args.sdk), indent=2, ensure_ascii=False))
