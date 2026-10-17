# Design

## Dependency provenance

The app build obtains the exact Sherpa-ONNX v1.13.4 AAR from its official GitHub release. Gradle writes it under ignored `app/build/third-party/`, permits only HTTPS GitHub release hosts across a bounded redirect chain, and validates pinned size plus SHA-256 before compilation. A wrong cache, redirect, response, size, digest, or incomplete transfer fails the build. The archive is never committed.

## Publication checks

`scripts/check_publication_safety.py --worktree` checks current tracked and non-ignored files; `--history` checks paths and structured text reachable from all local refs. Findings expose only a reason and a one-way path reference. GitHub Actions runs the worktree policy and its unit tests, then uses a checksum-pinned Gitleaks binary against complete Git history with read-only permissions and no credential persistence.

## Android hardening

Phone and Wear `release` enable R8 minification, resource shrinking, and `debuggable false`, using the optimized Android default rules plus narrow rules for the two JNI-exported Phone classes and a source-filename rule. Debug previews remain unchanged. No anti-debug or anti-tamper code is added; R8 reduces inspection effort but cannot prevent reverse engineering of a distributed binary or public source.

## Git boundary

Normal source commits may remove the AAR from the current tree. Removing its historical blob requires rewriting every branch and tag that reaches it. This is a separate, owner-approved operation; until approved and independently rescanned, public visibility remains a release blocker.
