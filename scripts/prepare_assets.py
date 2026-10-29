#!/usr/bin/env python3
"""Fetch and verify Google's public translation attribution asset."""

from __future__ import annotations

import hashlib
import io
import os
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


SOURCE_URL = "https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip"
OFFICIAL_HOST = "docs.cloud.google.com"
MEMBER_NAME = "png/color-regular@2x.png"
MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
EXPECTED_SIZE = 8322
EXPECTED_SHA256 = "13ba2624dd65b0bd00b41cf775df73f5a1d6c898c78ea7675485dc8b10715380"
TIMEOUT_SECONDS = 30


class OfficialHttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        parsed = urllib.parse.urlsplit(new_url)
        if parsed.scheme != "https" or parsed.hostname != OFFICIAL_HOST:
            raise urllib.error.URLError("redirect target is outside the verified official HTTPS host")
        return super().redirect_request(request, response, code, message, headers, new_url)


def download_archive() -> bytes:
    opener = urllib.request.build_opener(OfficialHttpsRedirectHandler())
    request = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "HeyRead-public-asset-preparer/1.0"})
    with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
        final_url = urllib.parse.urlsplit(response.geturl())
        if final_url.scheme != "https" or final_url.hostname != OFFICIAL_HOST:
            raise ValueError("download did not finish on the verified official HTTPS host")
        data = response.read(MAX_DOWNLOAD_BYTES + 1)
    if len(data) > MAX_DOWNLOAD_BYTES:
        raise ValueError("download exceeds the 8 MiB limit")
    return data


def verified_png(archive_bytes: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        matches = [item for item in archive.infolist() if item.filename == MEMBER_NAME]
        if len(matches) != 1 or matches[0].is_dir():
            raise ValueError("archive does not contain exactly one expected PNG member")
        if matches[0].file_size != EXPECTED_SIZE:
            raise ValueError("PNG member has an unexpected size")
        with archive.open(matches[0], "r") as source:
            png = source.read(EXPECTED_SIZE + 1)
    if len(png) != EXPECTED_SIZE:
        raise ValueError("PNG member is truncated or exceeds the expected size")
    digest = hashlib.sha256(png).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError("PNG member SHA-256 does not match the verified asset")
    return png


def output_path() -> Path:
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent
    return project_root / "app" / "src" / "main" / "res" / "drawable-nodpi" / "google_translate_attribution.png"


def prepare() -> Path:
    target = output_path()
    target.parent.mkdir(parents=True, exist_ok=True)

    if target.exists():
        existing = target.read_bytes()
        if len(existing) == EXPECTED_SIZE and hashlib.sha256(existing).hexdigest() == EXPECTED_SHA256:
            return target
        raise FileExistsError(f"refusing to replace an existing unverified file: {target}")

    png = verified_png(download_archive())
    file_descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(file_descriptor, "wb") as destination:
            destination.write(png)
            destination.flush()
            os.fsync(destination.fileno())
        os.link(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()
    return target


def main() -> int:
    try:
        target = prepare()
    except Exception as error:
        print(f"Asset preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified Google attribution asset is ready: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
