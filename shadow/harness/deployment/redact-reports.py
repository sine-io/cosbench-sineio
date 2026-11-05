#!/usr/bin/env python3
"""Remove this run's generated credentials from retained textual evidence."""
import pathlib
import sys

reports, secrets = map(pathlib.Path, sys.argv[1:])
values = []
scanned = 0
redacted_files = 0
for name in ("minio-root-user", "minio-root-password", "http-test-password", "http-service-token"):
    path = secrets / name
    if path.is_file() and path.read_bytes().strip():
        values.append(path.read_bytes().strip())
for path in reports.rglob("*"):
    if path.is_symlink():
        continue
    if path.is_dir():
        path.chmod(0o755)
        continue
    if not path.is_file():
        continue
    path.chmod(0o644)
    data = path.read_bytes()
    # JARs and binary fixtures have independent checksums; credentials are never
    # written into these artifacts. Only UTF-8 report/log/config text is redacted.
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        continue
    scanned += 1
    redacted = data
    for value in values:
        redacted = redacted.replace(value, b"<REDACTED>")
    if data != redacted:
        path.write_bytes(redacted)
        redacted_files += 1
(reports / "report-redaction.txt").write_text(
    f"status=passed\ntext_files_scanned={scanned}\nredacted_files={redacted_files}\n"
    f"credential_values={len(values)}\n"
)
