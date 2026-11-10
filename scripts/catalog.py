#!/usr/bin/env python3
"""Curate GitHub releases: sync candidates, explicitly approve, then bundle.

README content and GitHub release metadata are fetched. No repository scripts are executed.
GITHUB_TOKEN is optional and never written to candidate files or logs.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from readme_metadata import extract, https_url, merge_metadata, repository_urls

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "catalog/sources.json"
REVIEWED = ROOT / "catalog/reviewed.json"
METADATA = ROOT / "catalog/readme-metadata.json"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def fetch_json(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "OrbitStore-Catalog/0.1"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    try:
        with urlopen(Request(url, headers=headers), timeout=45) as response:
            return json.load(response)
    except HTTPError as error:
        if error.code in (403, 429):
            raise RuntimeError("GitHub 限流；等待额度恢复，或在环境变量中提供只读 GITHUB_TOKEN。") from None
        raise RuntimeError(f"GitHub 返回 HTTP {error.code}") from None


def validate(data):
    if data.get("schemaVersion") != 1:
        raise ValueError("目录 schemaVersion 必须为 1")
    ids, packages = set(), set()
    for app in data["apps"]:
        if not re.fullmatch(r"[a-z0-9-]+", app["id"]) or app["id"] in ids:
            raise ValueError("应用 ID 无效或重复")
        ids.add(app["id"])
        if app["packageName"] in packages:
            raise ValueError("同一首版目录不能重复声明包名")
        packages.add(app["packageName"])
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", app["repository"]):
            raise ValueError("无效 GitHub 仓库")
        for url in [app.get("iconUrl"), app.get("readmeUrl"), app.get("mediaSource"), *app.get("screenshots", [])]:
            if url and not https_url(url):
                raise ValueError("应用素材必须使用公开 HTTPS 地址")
        for digest in app.get("certificateSha256", []):
            if not re.fullmatch(r"[a-f0-9]{64}", digest):
                raise ValueError("证书指纹无效")
        for release in app.get("releases", []):
            for asset in release["assets"]:
                url = urlparse(asset["url"])
                if url.scheme != "https" or url.netloc != "github.com" or not url.path.startswith("/" + app["repository"] + "/releases/download/"):
                    raise ValueError("非该仓库官方发布地址")
                if not re.fullmatch(r"[a-f0-9]{64}", asset.get("sha256") or ""):
                    raise ValueError("审核资产必须具备 SHA-256")
                if asset["size"] <= 0 or asset.get("versionCode", 0) <= 0 or asset.get("minSdk", 0) <= 0:
                    raise ValueError("审核资产缺少真实包元数据")
    return data


def fetch_readme(app):
    config = app.get("readme")
    if not config:
        # New repositories need no path configuration. Pin ref/path to prefer a translation.
        repo = fetch_json(f"https://api.github.com/repos/{app['repository']}")
        result = fetch_json(f"https://api.github.com/repos/{app['repository']}/readme")
        ref, path = repo["default_branch"], result["path"]
        content = base64.b64decode(result["content"], validate=False)
    else:
        ref, path = config["ref"], config["path"]
        raw, _ = repository_urls(app["repository"], ref, path)
        # No GitHub token is forwarded to media/raw hosts.
        with urlopen(Request(raw, headers={"User-Agent": "OrbitStore-Catalog/0.1"}), timeout=45) as response:
            if urlparse(response.url).hostname != "raw.githubusercontent.com":
                raise ValueError("README 跳转至非 GitHub 原文地址")
            content = response.read(1024 * 1024 + 1)
    if len(content) > 1024 * 1024:
        raise ValueError("README 超过 1 MB")
    return content, ref, path


def metadata(args):
    apps = read(SOURCES)["apps"]
    if args.app:
        apps = [app for app in apps if app["id"] == args.app]
    if not apps:
        raise ValueError("未知应用")
    records = read(METADATA) if METADATA.exists() else {"schemaVersion": 1, "apps": {}}
    failures = []
    for app in apps:
        try:
            content, ref, path = fetch_readme(app)
            fields = extract(content.decode("utf-8-sig"), app["repository"], ref, path, app["name"])
            # A symlink/error page/empty README must not silently replace a good record.
            if not fields.get("description"):
                raise ValueError("未提取到介绍；请检查 README 路径或设置 metadataOverrides")
            record = {"repository": app["repository"], "ref": ref, "path": path,
                      "fetchedAt": int(time.time() * 1000), "contentSha256": hashlib.sha256(content).hexdigest(), "content": fields}
            validate({"schemaVersion": 1, "apps": [merge_metadata(dict(app), record)]})
            records["apps"][app["id"]] = record
            write(METADATA, records)
            print(f"{app['id']}: README 介绍、{'图标' if fields.get('iconUrl') else '无图标（保留回退）'}、{len(fields.get('screenshots', []))} 张截图")
        except Exception as error:
            failures.append(app["id"])
            print(f"{app['id']}: 保留原有资料；{error}", file=sys.stderr)
    if failures:
        raise ValueError("部分 README 同步失败：" + ", ".join(failures))


def sync(args):
    apps = read(SOURCES)["apps"]
    if args.app:
        apps = [app for app in apps if app["id"] == args.app]
    if not apps:
        raise ValueError("未知应用")
    spec = importlib.util.spec_from_file_location("apk_inspector", ROOT / "scripts/inspect-apk.py")
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    for app in apps:
        releases = fetch_json(f"https://api.github.com/repos/{app['repository']}/releases?per_page=20")
        candidates = []
        for release in releases:
            if release["draft"] or release["prerelease"]:
                continue
            assets = []
            for asset in release["assets"]:
                name = asset["name"]
                if not name.lower().endswith(".apk") or re.search(r"(^|[-_.])(debug|unsigned|test)([-_.]|$)", name, re.I):
                    continue
                result = {"id": asset["id"], "name": name, "url": asset["browser_download_url"], "size": asset["size"],
                          "sha256": (asset.get("digest") or "").removeprefix("sha256:"), "enabled": True}
                if args.inspect:
                    if asset["size"] > 512 * 1024 * 1024:
                        raise ValueError("自动检查暂不下载超过 512 MB 的资产")
                    cache = ROOT / "catalog/.cache" / str(asset["id"])
                    cache.parent.mkdir(parents=True, exist_ok=True)
                    expected = result["sha256"]
                    cached_hash = hashlib.sha256(cache.read_bytes()).hexdigest() if cache.exists() else None
                    if not expected or cached_hash != expected:
                        download = Request(result["url"], headers={"User-Agent": "OrbitStore-Catalog/0.1"})
                        with urlopen(download, timeout=90) as response, cache.open("wb") as output:
                            allowed = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
                            if urlparse(response.url).scheme != "https" or urlparse(response.url).hostname not in allowed:
                                raise ValueError("APK 跳转至未允许的来源")
                            total = 0
                            while block := response.read(1024 * 1024):
                                total += len(block)
                                if total > asset["size"]:
                                    raise ValueError("文件超出预期大小")
                                output.write(block)
                    metadata = inspector.inspect_apk(cache, args.sdk)
                    if metadata["packageName"] != app["packageName"] or metadata["size"] != result["size"]:
                        raise ValueError("APK 包名或大小与目录不符")
                    if expected and expected != metadata["sha256"]:
                        raise ValueError("APK 摘要与 GitHub 不符")
                    result.update({key: metadata[key] for key in ("sha256", "versionCode", "minSdk", "certificateSha256")})
                    result["abi"] = metadata["abis"][0] if len(metadata["abis"]) == 1 else "universal"
                assets.append(result)
            if assets:
                candidates.append({"id": release["id"], "tag": release["tag_name"], "name": release["name"] or release["tag_name"],
                                   "publishedAt": release["published_at"], "notes": (release["body"] or "")[:24000],
                                   "prerelease": release["prerelease"], "url": release["html_url"], "assets": assets})
            if len(candidates) >= args.limit:
                break
        write(ROOT / f"catalog/candidates/{app['id']}.json", {"appId": app["id"], "checkedAt": int(time.time() * 1000), "releases": candidates})
        print(f"{app['id']}: {len(candidates)} 个候选版本；未自动上架")
        time.sleep(1)


def approve(args):
    candidates = read(ROOT / f"catalog/candidates/{args.app}.json")
    release = next((r for r in candidates["releases"] if r["tag"] == args.tag), None)
    if not release:
        raise ValueError("候选版本不存在")
    expected = {value.lower() for value in args.expected_cert}
    for asset in release["assets"]:
        if set(asset.get("certificateSha256", [])) != expected or not asset.get("versionCode"):
            raise ValueError("请先执行 --inspect，且指纹必须与独立核对的发布证书一致")
    reviewed = read(REVIEWED) if REVIEWED.exists() else {"schemaVersion": 1, "apps": {}}
    previous = reviewed["apps"].get(args.app, {})
    if previous.get("certificateSha256") and set(previous["certificateSha256"]) != expected:
        raise ValueError("已有证书发生变化；需要单独审查证书轮换，不能通过本命令替换")
    releases = [r for r in previous.get("releases", []) if r["tag"] != args.tag]
    releases.insert(0, release)
    reviewed["apps"][args.app] = {"certificateSha256": sorted(expected), "checkedAt": candidates["checkedAt"], "releases": releases}
    write(REVIEWED, reviewed)
    print("已记录包元数据审核。此操作不等于 TV 真机认证，请另行记录实测版本。")


def bundle():
    source = read(SOURCES)
    reviewed = read(REVIEWED)["apps"] if REVIEWED.exists() else {}
    records = read(METADATA)["apps"] if METADATA.exists() else {}
    for app in source["apps"]:
        merge_metadata(app, records.get(app["id"]))
        app.update(reviewed.get(app["id"], {}))
    source["generatedAt"] = int(time.time() * 1000)
    validate(source)
    write(ROOT / "app/src/main/assets/catalog.json", source)
    print("已生成内置目录；仅使用显式审核过的记录")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    fetch = commands.add_parser("sync")
    fetch.add_argument("--app")
    fetch.add_argument("--limit", type=int, choices=range(1, 21), default=3)
    fetch.add_argument("--inspect", action="store_true")
    fetch.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", ROOT / ".tooling/android-sdk")))
    content = commands.add_parser("metadata", help="从项目 README 同步介绍、Logo 与截图，不修改安装包审核记录")
    content.add_argument("--app")
    review = commands.add_parser("approve")
    review.add_argument("--app", required=True)
    review.add_argument("--tag", required=True)
    review.add_argument("--expected-cert", action="append", required=True)
    commands.add_parser("bundle")
    commands.add_parser("validate")
    args = parser.parse_args()
    if args.command == "sync": sync(args)
    elif args.command == "metadata": metadata(args)
    elif args.command == "approve": approve(args)
    elif args.command == "bundle": bundle()
    else:
        validate(read(SOURCES)); validate(read(ROOT / "app/src/main/assets/catalog.json")); print("目录验证通过")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"目录操作失败：{error}", file=sys.stderr)
        sys.exit(1)
