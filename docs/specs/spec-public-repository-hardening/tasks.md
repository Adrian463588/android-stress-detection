# Implementation checklist

- [x] Record baseline repo visibility, reachable refs, Gitleaks status, participant-field scan, release assets, and historical AAR provenance.
- [x] Define Given/When/Then publication, dependency-integrity, and R8 acceptance scenarios.
- [x] Ignore AAR files and move the Sherpa dependency to checksum-verified, build-local acquisition.
- [x] Enable non-debuggable R8 release configurations for Phone and Wear.
- [x] Add a redacted source/history policy scanner, unit tests, and read-only GitHub workflow; enumerate all paths per distinct history tree and run both scans in CI.
- [x] Document current private status, debug APK boundaries, security workflow, and the required publication gate.
- [x] Run full-history policy and secret scans; allow only the exact historical Sherpa AAR path/checksum. Gitleaks 8.30.1 reports no credential findings; the scanner rejects all other AARs.
- [x] Run Phone release build, Android unit tests, lint, Detekt, and KtLint using the locked model artifacts. Phone production release builds with R8; the APK is unsigned and not a distributable release.
- [ ] Build and test Wear against Samsung Health Sensor SDK 1.4.1; blocked in this checkout because the local SDK AAR is absent (`com.samsung.*` unresolved).
- [x] Record owner decision: retain historical Sherpa AAR; use ordinary commits only; no rewrite or force-push.
- [x] Re-scan fetched remote branches/tags and release assets; no Samsung Health SDK AAR or AAR release attachment found.

## BDD verification

| Scenario | Verification |
| --- | --- |
| Pinned Sherpa download integrity | `:app:verifyStaticSherpaRuntime` and release assemble; checksum/size mismatch must fail. |
| Forbidden files and participant-name fields | `python -m unittest discover -s scripts -p 'test_publication_safety.py'`; `python scripts/check_publication_safety.py --worktree`. |
| History artifact policy | `python scripts/check_publication_safety.py --history`; passes only with the exact legacy Sherpa path and SHA-256. Tests reject aliases of the same blob, changed content, other paths, current-tree AARs, and over-limit tree records. |
| No credential in reachable commits | Gitleaks 8.30.1 with `--redact --log-opts=--all`; retain generic/redacted diagnostics only. |
| Release hardening | Phone `:app:assembleProductionRelease` passed; R8 mapping output exists and APK manifest has no `android:debuggable=true`. Wear release is blocked by absent local Samsung SDK AAR. |

## Execution evidence (2026-10-18)

- Phone, Inference, and Shared JVM tests: 868 passed, 0 failures/errors/skips; `:app:lintProductionDebug`, `ktlintCheck`, and `detekt` passed.
- Phone release: `:app:assembleProductionRelease` passed with R8. Local artifact is unsigned, package `com.stressdetector`, version `0.1.5`, SHA-256 `D9145B14A2499C8E23A046F70583942790681F57BEC7BEBBFE6D82BA1982D3EA`; do not distribute this unsigned build.
- R8 mapping: generated locally at `app/build/outputs/mapping/productionRelease/mapping.txt`; never publish it with an APK.
- Sherpa AAR: absent from current worktree/index and downloaded only into ignored build output; pinned size/SHA verification passed. APK contains no nested package/key artifacts.
- Worktree policy scanner and Gitleaks full reachable-history scan passed. Full-history artifact policy allows only the historical Sherpa AAR at `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar` (SHA-256 `dc5ac19a28dee3bffc5e5a5d50cb6afa977703fc4a7ee535a308506990fdd295`); no Samsung Health Sensor SDK AAR or other AAR path was found. GitHub release attachment inventory contains no AAR.
- Participant-name scan found no explicit identity fields in reachable source/text files. This is a repository-content scan, not a guarantee about untracked source datasets or arbitrary text embedded in screenshots.
- Wear build/test was attempted and failed at Kotlin compilation because Samsung Health Sensor SDK is not present locally; no replacement or mock was introduced.
- Repository visibility remains unchanged. Owner accepts the Sherpa history artifact; no rewrite or force-push is planned.
