# Design

## Dependency provenance

The app build obtains the exact Sherpa-ONNX v1.13.4 AAR from its official GitHub release. Gradle writes it under ignored `app/build/third-party/`, permits only HTTPS GitHub release hosts across a bounded redirect chain, and validates pinned size plus SHA-256 before compilation. A wrong cache, redirect, response, size, digest, or incomplete transfer fails the build. The archive is never committed.

## Publication checks

`scripts/check_publication_safety.py --worktree` rejects all AARs and other forbidden files in tracked/non-ignored current files. `--history` streams NUL-delimited entries from every distinct reachable commit tree, so one Git blob under multiple names cannot hide a forbidden path and tree output remains bounded. It permits exactly one historic AAR only when both path `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` and SHA-256 `dc5ac19a28dee3bffc5e5a5d50cb6afa977703fc4a7ee535a308506990fdd295` match; it checks the pinned file size before reading content and caches only the digest. A new AAR at that path, a Samsung SDK AAR, or any other package still fails. Findings expose only a reason and a one-way path reference. GitHub Actions runs unit tests and both worktree/history policy scans, then uses a checksum-pinned Gitleaks binary against complete Git history with read-only permissions and no credential persistence.

## Android hardening

Phone and Wear `release` enable R8 minification, resource shrinking, and `debuggable false`, using the optimized Android default rules plus narrow rules for the two JNI-exported Phone classes and a source-filename rule. Debug previews remain unchanged. No anti-debug or anti-tamper code is added; R8 reduces inspection effort but cannot prevent reverse engineering of a distributed binary or public source.

## Git boundary

Normal source commits keep the AAR out of the current tree. The owner accepts the exact historical Sherpa blob, so it stays reachable without rewrite or force-push. All other AARs remain publication blockers and are rejected by the scanner.
