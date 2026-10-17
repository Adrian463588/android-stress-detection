#!/usr/bin/env python3
"""Fail closed on sensitive artifacts and explicit participant-name fields."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_SUFFIXES = {
    ".aar",
    ".apk",
    ".aab",
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
    ".key",
    ".p8",
    ".ppk",
    ".mobileprovision",
}
FORBIDDEN_PATH_SEGMENTS = {
    "datarenamedpartisipan",
    "datatrimmedpartisipan",
    "timestampanchor",
    "rawparticipantdata",
    "participantdata",
    "participantdataset",
    "subjectdata",
    "subjectdataset",
}
STRUCTURED_SUFFIXES = {".csv", ".tsv", ".json", ".jsonl"}
TEXT_SUFFIXES = STRUCTURED_SUFFIXES | {
    ".gradle",
    ".groovy",
    ".java",
    ".kt",
    ".kts",
    ".md",
    ".properties",
    ".py",
    ".sh",
    ".toml",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
MAX_STRUCTURED_BYTES = 8 * 1024 * 1024
IDENTIFYING_FIELD = re.compile(
    r"(?i)(?:[\"']\s*)?(?:participant|subject)(?:[_\-\s])?"
    r"(?:full(?:[_\-\s])?)?name(?:[\"']\s*)?\s*[:=]"
)
FULL_NAME_FIELD = re.compile(r"(?i)(?:[\"']\s*)?full(?:[_\-\s])?name(?:[\"']\s*)?\s*[:=]")
PSEUDONYM = re.compile(r"^ID-(?:0[1-9]|[12][0-9]|3[0-2])$", re.IGNORECASE)
IDENTITY_KEYS = {"participantid", "subjectid"}
NAME_KEYS = {"name", "displayname"}
EXPLICIT_NAME_KEYS = {
    "participantname",
    "subjectname",
    "participantfullname",
    "subjectfullname",
    "fullname",
}


def path_violation(path: str) -> str | None:
    normalized = PurePosixPath(path.replace("\\", "/"))
    if normalized.suffix.casefold() in FORBIDDEN_SUFFIXES:
        return "forbidden-package-or-key"
    segments = {re.sub(r"[^a-z0-9]", "", part.casefold()) for part in normalized.parts}
    if segments & FORBIDDEN_PATH_SEGMENTS:
        return "restricted-participant-data-path"
    return None


def _normalized_key(value: object) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value).casefold())


def _has_unmasked_name(value: object) -> bool:
    if isinstance(value, dict):
        normalized = {_normalized_key(key): item for key, item in value.items()}
        if normalized.keys() & EXPLICIT_NAME_KEYS:
            return True
        participant_ids = [
            str(item).strip()
            for key, item in normalized.items()
            if key in IDENTITY_KEYS and item is not None
        ]
        for participant_id in participant_ids:
            for key in NAME_KEYS & normalized.keys():
                name = str(normalized[key]).strip()
                if name and (not PSEUDONYM.fullmatch(participant_id) or name != participant_id):
                    return True
        return any(_has_unmasked_name(item) for item in value.values())
    if isinstance(value, list):
        return any(_has_unmasked_name(item) for item in value)
    return False


def _csv_has_unmasked_name(text: str, delimiter: str) -> bool:
    rows = csv.reader(io.StringIO(text), delimiter=delimiter)
    try:
        headers = [_normalized_key(header) for header in next(rows)]
    except StopIteration:
        return False
    if set(headers) & EXPLICIT_NAME_KEYS:
        return True
    id_indexes = [index for index, header in enumerate(headers) if header in IDENTITY_KEYS]
    name_indexes = [index for index, header in enumerate(headers) if header in NAME_KEYS]
    if not id_indexes or not name_indexes:
        return False
    for row in rows:
        for id_index in id_indexes:
            participant_id = row[id_index].strip() if id_index < len(row) else ""
            for name_index in name_indexes:
                name = row[name_index].strip() if name_index < len(row) else ""
                if name and (not PSEUDONYM.fullmatch(participant_id) or name != participant_id):
                    return True
    return False


def content_violation(path: str, content: bytes) -> str | None:
    suffix = PurePosixPath(path.replace("\\", "/")).suffix.casefold()
    if suffix not in TEXT_SUFFIXES:
        return None
    if suffix in STRUCTURED_SUFFIXES and len(content) > MAX_STRUCTURED_BYTES:
        return "oversized-structured-file-requires-review"
    text = content.decode("utf-8", errors="replace")
    if IDENTIFYING_FIELD.search(text) or FULL_NAME_FIELD.search(text):
        return "identifying-name-field"
    if suffix == ".json":
        try:
            if _has_unmasked_name(json.loads(text)):
                return "unmasked-participant-name"
        except json.JSONDecodeError:
            return "invalid-json-requires-review"
    if suffix == ".csv" and _csv_has_unmasked_name(text, ","):
        return "unmasked-participant-name"
    if suffix == ".tsv" and _csv_has_unmasked_name(text, "\t"):
        return "unmasked-participant-name"
    if suffix == ".jsonl":
        for line in text.splitlines():
            try:
                if _has_unmasked_name(json.loads(line)):
                    return "unmasked-participant-name"
            except json.JSONDecodeError:
                return "invalid-jsonl-requires-review"
    return None


def _git(*arguments: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=ROOT,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise RuntimeError("unable to inspect repository state") from error
    return result.stdout


def _git_with_input(input_data: bytes, *arguments: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=ROOT,
            input=input_data,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise RuntimeError("unable to inspect repository state") from error
    return result.stdout


def _path_reference(path: str) -> str:
    return hashlib.sha256(path.encode("utf-8", errors="replace")).hexdigest()[:12]


def inspect_worktree() -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    paths = _git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
    for raw_path in paths.decode("utf-8", errors="surrogateescape").split("\0"):
        if not raw_path:
            continue
        reason = path_violation(raw_path)
        if reason:
            findings.append((reason, raw_path))
            continue
        source = ROOT / Path(raw_path)
        if source.is_file() and not source.is_symlink():
            reason = content_violation(raw_path, source.read_bytes())
            if reason:
                findings.append((reason, raw_path))
    return findings


def inspect_history() -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    objects = _git("rev-list", "--objects", "--all")
    paths_by_object: dict[str, set[str]] = {}
    for line in objects.decode("utf-8", errors="surrogateescape").splitlines():
        parts = line.split(" ", 1)
        if len(parts) != 2:
            continue
        object_id, path = parts
        reason = path_violation(path)
        if reason:
            findings.append((reason, path))
        suffix = PurePosixPath(path.replace("\\", "/")).suffix.casefold()
        if suffix in TEXT_SUFFIXES:
            paths_by_object.setdefault(object_id, set()).add(path)

    if not paths_by_object:
        return findings

    object_ids = list(paths_by_object)
    object_input = ("\n".join(object_ids) + "\n").encode("ascii")
    metadata = _git_with_input(object_input, "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)")
    sizes: dict[str, int] = {}
    for line in metadata.decode("ascii", errors="replace").splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] == "blob":
            sizes[parts[0]] = int(parts[2])

    readable_ids: list[str] = []
    for object_id, paths in paths_by_object.items():
        size = sizes.get(object_id)
        if size is None:
            findings.extend(("unreadable-history-object", path) for path in paths)
            continue
        for path in paths:
            if size > MAX_STRUCTURED_BYTES:
                suffix = PurePosixPath(path.replace("\\", "/")).suffix.casefold()
                reason = (
                    "oversized-structured-file-requires-review"
                    if suffix in STRUCTURED_SUFFIXES
                    else "oversized-text-file-requires-review"
                )
                findings.append((reason, path))
        if size <= MAX_STRUCTURED_BYTES:
            readable_ids.append(object_id)

    if not readable_ids:
        return findings
    contents = _git_with_input(
        ("\n".join(readable_ids) + "\n").encode("ascii"),
        "cat-file",
        "--batch",
    )
    stream = io.BytesIO(contents)
    for object_id in readable_ids:
        header = stream.readline().decode("ascii", errors="replace").split()
        if len(header) != 3 or header[1] != "blob":
            findings.extend(("unreadable-history-object", path) for path in paths_by_object[object_id])
            continue
        size = int(header[2])
        content = stream.read(size)
        stream.read(1)
        for path in paths_by_object[object_id]:
            reason = content_violation(path, content)
            if reason:
                findings.append((reason, path))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--worktree", action="store_true", help="inspect tracked and non-ignored current files")
    mode.add_argument("--history", action="store_true", help="inspect every file path and text blob reachable from refs")
    arguments = parser.parse_args()
    try:
        findings = inspect_history() if arguments.history else inspect_worktree()
    except RuntimeError:
        print("FAIL: publication policy could not inspect the Git repository")
        return 2
    if findings:
        print(f"FAIL: {len(findings)} publication-policy finding(s); paths are hashed to avoid exposing names.")
        for reason, path in findings:
            print(f"- {reason}; path-ref=sha256:{_path_reference(path)}")
        return 1
    scope = "reachable Git history" if arguments.history else "current worktree/index"
    print(f"PASS: publication policy found no prohibited artifacts or identifying name fields in {scope}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
