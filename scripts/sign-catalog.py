#!/usr/bin/env python3
"""Sign reviewed catalog bytes with an externally supplied RSA private key."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/catalog.json"))
parser.add_argument("--key", type=Path, required=True)
parser.add_argument("--revision", type=int, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if args.revision <= 0:
    raise SystemExit("revision 必须大于零，并高于已发布版本")
data = json.loads(args.catalog.read_text())
data["revision"] = args.revision
payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / "payload.json"
    source.write_bytes(payload)
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.key), str(source)], capture_output=True, check=True).stdout
envelope = {"payload": base64.b64encode(payload).decode(), "signature": base64.b64encode(signature).decode()}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(envelope) + "\n")
print("已生成签名目录；私钥未写入产物")
