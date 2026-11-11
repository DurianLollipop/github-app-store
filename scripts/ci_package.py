#!/usr/bin/env python3
"""Validate release identity and package only app APKs, with no external Python dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def release_version(tag, source, previous=None):
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)(-(?:alpha|beta|rc)\.[1-9]\d*)?", tag)
    if not match:
        raise ValueError("标签必须为 v0.3.1 或 v0.3.2-rc.1 这样的版本")
    version = tag[1:]
    if source["versionName"].removesuffix("-dev").split("-", 1)[0] != match[1]:
        raise ValueError("标签与源码版本不一致，请先修改 app/build.gradle.kts 的版本常量")
    code = source["versionCode"]
    if type(code) is not int or not 1 <= code <= 2_100_000_000:
        raise ValueError("versionCode 超出 Android 允许范围")
    if previous and code <= int(previous["versionCode"]):
        raise ValueError("versionCode 必须高于最近一次发布，包括预发布版本")
    return {"version": version, "code": code, "prerelease": str(bool(match[2])).lower()}


def package(variant, output, expected_version=None, source=None, previous=None):
    metadata_path = Path(f"app/build/outputs/apk/{variant}/output-metadata.json")
    metadata = json.loads(metadata_path.read_text())
    if metadata["applicationId"] != "dev.githubstore" or len(metadata["elements"]) != 1:
        raise ValueError("只允许打包 Orbit 的单一通用 APK，不能上传测试 APK 或拆分包")
    item = metadata["elements"][0]
    version, code = item["versionName"], item["versionCode"]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError("APK 版本名称不能用作产物文件名")
    if expected_version and (version != expected_version or code != source["versionCode"]):
        raise ValueError("实际 APK 版本与发布配置不一致")
    apk = metadata_path.parent / item["outputFile"]
    if apk.resolve().parent != metadata_path.parent.resolve() or not apk.is_file():
        raise ValueError("APK 输出路径不正确")
    if output.exists() and any(output.iterdir()):
        raise ValueError("产物目录非空，请换用空目录，避免混入旧 APK")
    output.mkdir(parents=True, exist_ok=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    name = f"orbit-tv-{version}.apk" if variant == "release" else f"orbit-tv-{version}-debug-{commit[:7]}.apk"
    target = output / name
    shutil.copy2(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    details = {"applicationId": metadata["applicationId"], "versionName": version, "versionCode": code,
               "variant": variant, "commit": commit, "apkFile": name, "apkBytes": target.stat().st_size,
               "apkSha256": digest, "buildUrl": os.environ.get("ORBIT_BUILD_URL", "")}
    if variant == "release":
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ValueError("需要 ANDROID_HOME 才能校验 Release 签名")
        signer = Path(sdk) / "build-tools/35.0.0/apksigner"
        verified = subprocess.check_output([str(signer), "verify", "--verbose", "--print-certs", str(target)], text=True)
        certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", verified)
        if len(certs) != 1:
            raise ValueError("Release 必须包含一个有效签名证书")
        certificate = certs[0].lower()
        expected_cert = os.environ.get("ORBIT_RELEASE_CERT_SHA256", "").replace(":", "").strip().lower()
        if expected_cert and expected_cert != certificate:
            raise ValueError("Release 签名与配置的预期证书不一致")
        if previous and previous.get("certificateSha256") != certificate:
            raise ValueError("Release 签名与最近版本不一致，不能进行覆盖更新")
        if "androiddebugkey" in verified.lower() or "cn=android debug" in verified.lower():
            raise ValueError("不能使用 Android 调试证书发布 Release")
        details["certificateSha256"] = certificate
    manifest = output / ("release-metadata.json" if variant == "release" else "build-metadata.json")
    manifest.write_text(json.dumps(details, ensure_ascii=False, indent=2) + "\n")
    checksums = [f"{digest}  {name}", f"{hashlib.sha256(manifest.read_bytes()).hexdigest()}  {manifest.name}"]
    (output / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
    print(json.dumps(details, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["version", "debug", "release"])
    parser.add_argument("--tag")
    parser.add_argument("--source", type=Path, default=Path("app/build/ci/source-version.json"))
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/artifacts"))
    args = parser.parse_args()
    try:
        source = json.loads(args.source.read_text()) if args.command != "debug" else None
        previous = json.loads(args.previous.read_text()) if args.previous and args.previous.exists() else None
        version = release_version(args.tag or "", source, previous) if source else None
        if args.command == "version":
            content = "".join(f"{key}={value}\n" for key, value in version.items())
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                    stream.write(content)
            print(content, end="")
        else:
            package(args.command, args.output, version["version"] if version else None, source, previous)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"构建产物检查失败：{error}\n")


if __name__ == "__main__":
    main()
