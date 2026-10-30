#!/usr/bin/env python3
"""Validate, align, optionally sign, and package the public Android release APK."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


APPLICATION_ID = "cn.heyread.app"
SIGNING_VARIABLES = (
    "RELEASE_KEYSTORE_BASE64",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
BADGING_PACKAGE = re.compile(
    r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
    re.MULTILINE,
)
SAFE_VERSION = re.compile(r"^[A-Za-z0-9._+-]{1,64}$")


class PackageError(Exception):
    """A short, safe-to-print packaging failure."""


def find_tool(build_tools: Path, name: str) -> Path:
    suffixes = (".exe", ".bat", "") if os.name == "nt" else ("",)
    for suffix in suffixes:
        candidate = build_tools / f"{name}{suffix}"
        if candidate.is_file():
            return candidate
    raise PackageError(f"required Android build tool is missing: {name}")


def run_tool(
    tool: Path,
    args: list[str],
    *,
    env: dict[str, str] | None = None,
    allow_failure: bool = False,
) -> bytes | None:
    try:
        result = subprocess.run(
            [str(tool), *args],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
    except OSError as error:
        raise PackageError(f"could not run Android build tool: {tool.name}") from error
    if result.returncode != 0:
        if allow_failure:
            return None
        raise PackageError(f"Android build tool failed: {tool.name} (exit {result.returncode})")
    return result.stdout


def run_apksigner(
    tool: Path,
    args: list[str],
    *,
    env: dict[str, str] | None = None,
    allow_failure: bool = False,
) -> bytes | None:
    if tool.suffix.lower() not in {".bat", ".cmd"}:
        return run_tool(tool, args, env=env, allow_failure=allow_failure)

    jar = tool.parent / "lib" / "apksigner.jar"
    if not jar.is_file():
        raise PackageError("Android apksigner JAR is missing")
    java_home = os.environ.get("JAVA_HOME")
    java_candidates = [Path(java_home) / "bin" / "java.exe"] if java_home else []
    java_path = shutil.which("java")
    if java_path:
        java_candidates.append(Path(java_path))
    java = next((candidate for candidate in java_candidates if candidate.is_file()), None)
    if java is None:
        raise PackageError("Java executable is unavailable for Android apksigner")
    return run_tool(java, ["-jar", str(jar), *args], env=env, allow_failure=allow_failure)


def resolve_apk(project_root: Path) -> tuple[Path, str, int, str]:
    metadata_path = project_root / "app" / "build" / "outputs" / "apk" / "release" / "output-metadata.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PackageError("release APK output metadata is missing or invalid") from error

    if not isinstance(metadata, dict):
        raise PackageError("release APK output metadata is invalid")
    artifact_type = metadata.get("artifactType")
    elements = metadata.get("elements")
    if (
        not isinstance(artifact_type, dict)
        or artifact_type.get("type") != "APK"
        or not isinstance(elements, list)
        or len(elements) != 1
    ):
        raise PackageError("release metadata must describe exactly one APK")
    if metadata.get("applicationId") != APPLICATION_ID:
        raise PackageError("release metadata has an unexpected application ID")

    element = elements[0]
    if not isinstance(element, dict):
        raise PackageError("release metadata has an invalid APK element")
    version_name = element.get("versionName")
    version_code = element.get("versionCode")
    output_name = element.get("outputFile")
    if not isinstance(version_name, str) or not SAFE_VERSION.fullmatch(version_name):
        raise PackageError("release metadata has an invalid version name")
    if type(version_code) is not int or version_code <= 0:
        raise PackageError("release metadata has an invalid version code")
    if (
        not isinstance(output_name, str)
        or not output_name
        or "/" in output_name
        or "\\" in output_name
        or not output_name.lower().endswith(".apk")
        or Path(output_name).name != output_name
    ):
        raise PackageError("release metadata has an unsafe APK output path")

    metadata_directory = metadata_path.parent.resolve()
    apk = (metadata_path.parent / output_name).resolve()
    try:
        apk.relative_to(metadata_directory)
    except ValueError as error:
        raise PackageError("release APK output resolves outside the metadata directory") from error
    if not apk.is_file():
        raise PackageError("release APK listed in metadata does not exist")
    return apk, version_name, version_code, metadata["applicationId"]


def validate_apk(apk: Path, aapt: Path, expected_version: str, expected_code: int) -> None:
    badging = run_tool(aapt, ["dump", "badging", str(apk)]).decode("utf-8", errors="replace")
    match = BADGING_PACKAGE.search(badging)
    if match is None:
        raise PackageError("aapt could not read release APK package metadata")
    application_id, version_code, version_name = match.groups()
    if application_id != APPLICATION_ID or version_code != str(expected_code) or version_name != expected_version:
        raise PackageError("APK package or version does not match Gradle output metadata")
    if re.search(r"^application-debuggable\b", badging, re.MULTILINE):
        raise PackageError("release APK is marked debuggable")


def has_apk_signature_material(apk: Path) -> bool:
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            upper_name = name.upper()
            if upper_name.startswith("META-INF/") and upper_name.endswith(
                (".SF", ".RSA", ".DSA", ".EC")
            ):
                return True
        central_directory_offset = archive.start_dir
    if central_directory_offset < 16:
        return False
    with apk.open("rb") as source:
        source.seek(central_directory_offset - 16)
        return source.read(16) == b"APK Sig Block 42"


def signing_configuration() -> dict[str, str] | None:
    values = {name: os.environ.get(name, "") for name in SIGNING_VARIABLES}
    present = [bool(value) for value in values.values()]
    if not any(present):
        return None
    if not all(present):
        raise PackageError("release signing secrets are partially configured; provide all four or none")
    return values


def commit_sha() -> str:
    value = os.environ.get("GITHUB_SHA", "")
    return value.lower() if re.fullmatch(r"[0-9a-fA-F]{40}", value) else "unknown"


def package(args: argparse.Namespace) -> tuple[Path, bool]:
    project_root = Path(__file__).resolve().parents[1]
    build_tools = Path(args.build_tools).resolve()
    output_dir = Path(args.output_dir).resolve()
    if not build_tools.is_dir():
        raise PackageError("Android build tools directory does not exist")
    if output_dir.exists() and (not output_dir.is_dir() or any(output_dir.iterdir())):
        raise PackageError("output directory already exists and is not empty")

    apk, version_name, version_code, application_id = resolve_apk(project_root)
    aapt = find_tool(build_tools, "aapt")
    zipalign = find_tool(build_tools, "zipalign")
    apksigner = find_tool(build_tools, "apksigner")
    validate_apk(apk, aapt, version_name, version_code)

    signing = signing_configuration()
    temp_parent = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()).resolve()
    if not temp_parent.is_dir():
        raise PackageError("temporary directory is unavailable")

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="heyread-release-", dir=temp_parent) as temp_name:
        temp_dir = Path(temp_name)
        aligned = temp_dir / "aligned.apk"
        run_tool(zipalign, ["-f", "-p", "4", str(apk), str(aligned)])
        if not aligned.is_file():
            raise PackageError("zipalign did not produce an APK")

        if signing is None:
            if has_apk_signature_material(aligned):
                raise PackageError("unsigned packaging refused an APK containing signature material")
            if run_apksigner(
                apksigner,
                ["verify", "--verbose", str(aligned)],
                allow_failure=True,
            ) is not None:
                raise PackageError("unsigned packaging refused an APK that passes signature verification")
            packaged_apk = aligned
            signed = False
            signing_label = "unsigned"
        else:
            try:
                keystore_bytes = base64.b64decode(signing["RELEASE_KEYSTORE_BASE64"], validate=True)
            except (ValueError, base64.binascii.Error) as error:
                raise PackageError("RELEASE_KEYSTORE_BASE64 is not strict base64") from error
            if not keystore_bytes:
                raise PackageError("decoded release keystore is empty")
            keystore = temp_dir / "release.keystore"
            keystore.write_bytes(keystore_bytes)
            signed_apk = temp_dir / "signed.apk"
            environment = os.environ.copy()
            environment["RELEASE_STORE_PASSWORD"] = signing["RELEASE_STORE_PASSWORD"]
            environment["RELEASE_KEY_PASSWORD"] = signing["RELEASE_KEY_PASSWORD"]
            run_apksigner(
                apksigner,
                [
                    "sign",
                    "--ks",
                    str(keystore),
                    "--ks-key-alias",
                    signing["RELEASE_KEY_ALIAS"],
                    "--ks-pass",
                    "env:RELEASE_STORE_PASSWORD",
                    "--key-pass",
                    "env:RELEASE_KEY_PASSWORD",
                    "--out",
                    str(signed_apk),
                    str(aligned),
                ],
                env=environment,
            )
            if not signed_apk.is_file():
                raise PackageError("apksigner did not produce a signed APK")
            run_apksigner(apksigner, ["verify", "--verbose", str(signed_apk)])
            packaged_apk = signed_apk
            signed = True
            signing_label = "signed"

        run_tool(zipalign, ["-c", "-p", "4", str(packaged_apk)])

        short_sha = commit_sha()[:7] if commit_sha() != "unknown" else "unknown"
        apk_name = f"HeyRead-{version_name}-release-{signing_label}-{short_sha}.apk"
        with tempfile.TemporaryDirectory(prefix=".heyread-artifacts-", dir=output_dir.parent) as publish_name:
            staged_dir = Path(publish_name) / "artifacts"
            staged_dir.mkdir()
            staged_apk = staged_dir / apk_name
            shutil.copyfile(packaged_apk, staged_apk)
            digest = hashlib.sha256(staged_apk.read_bytes()).hexdigest()
            (staged_dir / "SHA256SUMS.txt").write_text(
                f"{digest}  {apk_name}\n", encoding="utf-8"
            )
            build_info = {
                "applicationId": application_id,
                "versionName": version_name,
                "versionCode": version_code,
                "signed": signed,
                "commitSHA": commit_sha(),
            }
            (staged_dir / "build-info.json").write_text(
                json.dumps(build_info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )

            if output_dir.exists():
                output_dir.rmdir()
            try:
                os.replace(staged_dir, output_dir)
            except OSError as error:
                raise PackageError("could not atomically publish packaged artifacts") from error
    return output_dir / apk_name, signed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", required=True, help="Android build-tools directory")
    parser.add_argument("--output-dir", required=True, help="Empty directory for validated APK artifacts")
    try:
        apk, signed = package(parser.parse_args())
    except PackageError as error:
        print(f"Release packaging failed: {error}", file=sys.stderr)
        return 1
    except OSError:
        print("Release packaging failed: filesystem operation failed", file=sys.stderr)
        return 1
    status = "signed and verified" if signed else "unsigned; not directly installable"
    print(f"Release APK prepared ({status}): {apk.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
