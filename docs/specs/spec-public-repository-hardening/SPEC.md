---
id: SPEC-public-repository-hardening
companions:
  - bdd-scenarios.md
  - design.md
  - tasks.md
sources: []
---

# Public Repository Security Readiness

## Why

The Android repository is intended to become public evidence for a thesis defense. Contributors and reviewers need a source history that does not expose credentials, participant identities, raw research data, or vendored AAR binaries, while release builds use platform-supported code obfuscation without claiming reverse engineering is impossible.

## Capabilities

- id: CAP-1
  intent: Repository owners can verify that reachable source history and release assets contain no credentials, participant-name fields, raw participant datasets, or checked-in AAR packages before publication.
  success: A redacted full-history secret scan is clean; repository and release asset inventories contain no forbidden binaries or participant-data paths; any historical finding is explicitly blocked from publication.
- id: CAP-2
  intent: Developers can build the app without storing third-party AAR binaries in Git.
  success: The pinned upstream Sherpa artifact is fetched only when needed, SHA-256 verified before use, and a mismatch or network failure stops the build with a clear error.
- id: CAP-3
  intent: Android release variants are harder to inspect than debug variants without compromising runtime correctness or shipping signing credentials.
  success: Phone and Wear release configurations enable R8 minification and resource shrinking and remain non-debuggable; Phone release builds successfully with the locked model artifacts, while Wear build verification requires the owner-provided Samsung SDK; signing secrets remain external to source control.
- id: CAP-4
  intent: Future changes are checked for secret and binary regressions before they can be merged.
  success: Repository policy rejects AAR/key/data artifacts and CI scans full Git history with redacted output and read-only permissions.

## Constraints

- Participant datasets are not part of this Android source repository; only approved pseudonymous IDs may appear in research references.
- No credentials, signing keys, or AAR files may be tracked or attached to this repository's releases.
- The existing release APKs are debug previews; do not describe them as hardened production binaries.
- Do not change repository visibility or rewrite shared Git history without explicit approval.
- R8 is defense-in-depth, not a guarantee against reverse engineering; public source remains inspectable by design.
- Do not modify biosignal model thresholds, participant data, runtime behavior, or clinical claims for this security change.

## Non-goals

- Preventing a determined analyst from reverse engineering a distributed APK.
- Creating or publishing a signed production APK without an owner-controlled signing key.
- Changing GitHub repository visibility, deleting historical releases, or force-pushing shared refs as an unapproved side effect.
- Adding runtime security libraries or anti-tamper mechanisms.

## Success signal

The current source tree contains no AAR, key, or raw participant-data files; builds obtain the same approved Sherpa artifact from its official release with a pinned checksum; release variants pass R8 build checks. Publication remains blocked until all reachable refs and tags are free of the historical AAR and the owner authorizes any required history rewrite.

## Assumptions

- The repository remains private during this remediation; the owner will control the later visibility change.
- Existing APK releases remain explicitly labeled internal debug previews.
- The verified upstream Sherpa AAR checksum is the intended dependency contract for this branch.

## Open Questions

- May all branch and tag histories, including `main` and release tags, be rewritten with `git-filter-repo` and force-pushed to remove the AAR introduced in the initial commit?
