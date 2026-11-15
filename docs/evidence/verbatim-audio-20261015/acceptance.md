# Verbatim+ audio evidence preview — 2026-10-15

## Scope and acceptance boundary

Phone/Watch 0.1.1 (versionCode 2), local production-debug builds. No new Watch protocol,
Room schema, model threshold, PPG channel fusion or participant data changes.
The user owns manual ADB testing for this change. No installation, microphone,
human ASR/diarization accuracy, airplane-mode, Watch or thermal result is claimed.
Existing device PNGs in the README are historical, not this version's preview.

## Behavior contract

- Given actual PCM, capture the measured absolute-peak envelope (up to 64 bins
  per complete second) and openSMILE voiced F0 geometric mean. Amplitude is not
  F0; neither is a deception/medical score. The envelope is not lossless audio.
- Given silence, clipping, missing frames or a pause, show unavailable pitch or
  a real gap; never synthesize points or join the F0 curve across gaps.
- Given more than 60 seconds, keep only the live tail in UI memory but retain
  encrypted signal chunks. Archive review loads one 60-second page at a time.
  Incomplete sub-second tails are not padded into an F0 observation.
- Given cue/sensor evidence, display marker boundaries and explanations.
  Lexical cues use utterance ranges, not invented word timestamps. Sensor
  events subtract the measured monotonic audio origin. Legacy events without
  alignment either use an explicitly disclosed linked utterance or abstain.
- Given marker inspection during recording, do not start playback. In archive
  review, listening goes through the existing capture-to-WAV pause mapping.
- Given a final native diarization result, decode only native speech spans,
  not uncovered silence. A blank crop does not erase valid neighboring turns.
  Overlap/insufficient speaker evidence remains Unknown, never an inferred role.
- Given a blank final decoder result, replace the partial with an explicit
  terminal review state; keep previous partial/whole/crop/backend evidence.
- Given pathological repeated ASR, retain raw text and flag review. Do not turn
  it into an automatic linguistic cue or AI summary. An explicit user edit is
  reviewed text; immutable decoder output remains available.
- Given an archived session, retain waveform/F0 and native decoder evidence in
  ZIP and the scoped encrypted SAF user backup. Transcript edits remain in Room.
  No destructive migration is used; old sessions show unavailable signal trace.
- Given deletion, remove the owned archive and any retained capture trace.

## Storage and clocks

`audio_signal/manifest.json.enc` references `chunk_000000.json.enc` etc. Each
chunk contains at most 30 real observations. Schema/session identity, ordering,
finite values, bounded JSON size and path scope are validated before review.
The manifest records completion/failure and `audioStartSessionElapsedMs` from
the actual SessionTimeline, not a wall-clock estimate. The saved trace follows
the existing pitch worker: overload may lose analysis windows but must not block
the AudioRecord writer; those gaps are visible and raw WAV remains authoritative.
`captureDurationMs` retains the known pause-aware recording extent, so an empty
or damaged page cannot hide later transcript/manual markers. Valid navigation
metadata survives a chunk failure. Zero-duration trace rows and dense manifests
are rejected before allocating their payloads.

WAV/transcript/native decoder evidence commits before optional trace publication.
A trace failure publishes an explicit unavailable manifest rather than rolling
back those core artifacts. Cleanup failures preserve committed evidence and
record exact retry paths in `artifact_warnings.json.enc` plus the finalization
warning state; this optional file is included in ZIP/SAF scope. A completed trace
is not claimed if final writes fail. Archive decoder restoration failures or
raw-text/time/session mismatches remain quarantined, not silently accepted.

Raw capture, F0, transcript and playback positions use capture elapsed time
including pauses. PPG/acoustic persisted events use session elapsed time. Only
the playback coordinator compresses unrecorded pauses into WAV offsets.
New manual notes also use the capture clock. Historical notes with ambiguous
clock provenance remain in the marker list/TXT, not guessed onto the chart.

## Recognition and diarization

The provisioned v4 speech bundle already uses local Whisper tiny INT8 for
Indonesian/English final decoding; Indonesian live partials use Zipformer.
The exact accepted legacy v3 path remains supported, not silently substituted.
Whisper is ASR, not a replacement for the native segmentation/embedding speaker
pipeline. These changes address concrete gap/crop/finalization failures, not a
claim of measured WER/CER/DER improvement without paired human reference audio.
No phrase blacklist removes genuine Indonesian repetition or fillers.

## Verification

Final local verification completed 2026-10-16 (work began 2026-10-15).
Compose instrumentation is compiled but not run on a device here.

| Gate | Observed result |
| --- | --- |
| JVM tests | app 782, inference 31, Wear 30, shared 36; 879 total, zero failures/errors |
| All-module ktlintCheck / detekt | Passed; no lint baseline modified |
| Phone Android lint | Passed: 0 errors, 73 warnings, 2 hints |
| Watch Android lint | Passed: 0 errors, 9 warnings |
| App Android-test Kotlin / APK | Compiled and assembled; not executed |
| Native / phone / Watch APK | Assembled; static-Sherpa/native-runtime/no-legacy-model-asset verification passed |
| Artifact identity | Both productionDebug, version 0.1.1/code 2, package com.stressdetector |
| Signing | Both verified; SHA-256 certificate fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4 |

Final full gate: `BUILD SUCCESSFUL in 9m 16s`, 236 actionable tasks. Earlier
failed formatter/lint and regression attempts were repaired and rerun; their
candidate APKs are not the release artifact. No physical/device result is reused.

[Versioned checksums](../../releases/android-v0.1.1/SHA256SUMS.txt):
phone 253246205 bytes, SHA-256 `5639c8e704d106646a010d483d60cd391bfbb13dc2c4ffe741f747ab076a612f`;
Watch 17526934 bytes, SHA-256 `3ea5fd86c13105244350eab8ea7713cc793df0fbde8d1ba626f3b09a8bc8cf96`.
See [review resolutions](review.md) for the regression-to-boundary mapping.

Required local gates: `:app:ktlintCheck`, `:app:detekt`,
`:app:lintProductionDebug`, `:app:testProductionDebugUnitTest`,
`:app:compileProductionDebugAndroidTestKotlin`, `:app:assembleProductionDebug`,
`:app:assembleProductionDebugAndroidTest`.

Executed from `android/` with RTK, local Gradle and one worker:

```powershell
.\gradlew.bat "-PinternalModelIndex=../data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json" "-PdetectorContract=../data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json" :app:ktlintFormat ktlintCheck detekt :app:testProductionDebugUnitTest :app:compileProductionDebugAndroidTestKotlin :app:lintProductionDebug :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest :wear:lintProductionDebug :wear:assembleProductionDebug :inference:testProductionDebugUnitTest :wear:testProductionDebugUnitTest :shared:test --console=plain --max-workers=1
```

Manual checklist:

- [ ] Install with replacement semantics and the same signing identity; no uninstall/clear-data.
- [ ] Provision the v4 bundle through Profile/SAF; verify offline readiness.
- [ ] Record consented ID/EN two-speaker reference audio; compare text and speaker labels.
- [ ] Observe waveform/F0, real pauses, live cues and recording-stop drain.
- [ ] Reopen a session longer than 60 seconds; navigate pages and listen from markers.
- [ ] Review/correct suspicious ASR, export TXT/ZIP and inspect evidence explanations.
- [ ] Backup and restore through the selected SAF folder; verify the signal trace.
- [ ] Check large font, narrow width, landscape, TalkBack and 48dp actions.
- [ ] With real Watch batches, verify independent PPG markers; otherwise unavailable.

## Distribution

Publish only to the user-approved private `Adrian463588/android-stress-detection`
repository, development branch `android-devsecops-20260808`, as an internal
prerelease. Both main branches and the historical 0.1.0 release are preserved.
The user explicitly authorized including the existing pseudonymized PPG parity
vectors in this private APK. ZIP inspection found 100 feature-vector cases each
for Green/IR, with pseudonymized participant/phase/window identifiers and model
probabilities; these are derived features, not raw PPG waveform recordings.
Do not interpret that authorization as permission to make the
repository public. No raw source dataset is staged or separately uploaded.
The existing Samsung/openSMILE distribution/license constraints still apply.
Comparison with the historical 0.1.0 tag found Watch/shared changes already in
the starting checkout. Build a matching-source 0.1.1 companion rather than infer
compatibility of the old Watch APK. This task changes its version only.
The earlier precautionary question referred broadly to signal windows; the
packaged `onnx_feature_parity_vectors.json` payload was subsequently inspected
and this description corrected before publication.

## Primary research informing the minimal change

- [ATLAS.ti multimedia workflow](https://manuals.atlasti.com/Win/en/manual/Multimedia/MultimediaWorkingWith.html): waveform-based audio review, not F0 estimation.
- [Whisper model card](https://github.com/openai/whisper/blob/main/model-card.md): hallucination/repetition and task limitations; preserve raw decoder evidence.
- [Pinned Sherpa VAD implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/sherpa-onnx/csrc/voice-activity-detector.cc): drain completed queue without resetting ongoing VAD state.
- [Sherpa diarization API](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/docs/speaker-diarization.dox): separate segmentation, embedding and clustering.
- [BMAD](https://github.com/bmad-code-org/BMAD-METHOD), [OpenSpec](https://github.com/Fission-AI/OpenSpec): local spec/BDD/review artifacts; no runtime or cloud build dependency.
- [GitHub Spec Kit](https://github.com/github/spec-kit): reference for the specify → plan → tasks → implement → converge cycle; no new tooling dependency was added.
- [RTK](https://github.com/rtk-ai/rtk), [Ponytail](https://github.com/dietrichgebert/ponytail), [Caveman](https://github.com/juliusbrussee/caveman): authoring guidance only.

## Follow-up verification — 2026-10-17, Phone v0.1.2

### BDD regression

Given a persisted marker with an unrecognized channel and marker type, when the
finalizer links it to a final transcript utterance, then it links only by the
recorded time window and retains the original metadata. It is never converted
to `GREEN` or `MODEL_AROUSAL`. Regression: `TranscriptTimelineLinkerTest`.

### Local gates

- Python: `ruff check .`, `ruff format --check .`; **430 passed, 3 skipped** in pytest.
- Android JVM: app **783**, inference **31**, Wear **30**, shared **36**;
  **880 total, 0 failures, 0 errors, 0 skipped**.
- `ktlintCheck`, `detekt`, phone `lintProductionDebug`, Wear
  `lintProductionDebug`, Android-test Kotlin compilation and production APK/test
  APK assembly passed. Native Sherpa/openSMILE/runtime-contract checks passed.
- Instrumentation coverage was explicitly selected to avoid capture or storage
  mutation; it does not turn fixture values into audio/runtime evidence.

### Preservation-safe device check

Device: Samsung SM-G988B (ADB target supplied by the user):

- `adb install --user 0 -r -d app-production-debug.apk` returned `Success`.
- Installed package reports `com.stressdetector`, versionCode **3**, versionName
  **0.1.2**. Package UID, data directory, and first-install time were unchanged.
- SHA-256 of `stress_sessions.db` and `knowledge_base_secure.db` matched before
  and immediately after replacement. No uninstall, `pm clear`, backup/restore,
  or session-artifact cleanup was used.
- Airplane mode was enabled. Launcher reached `MainActivity`; the app process
  remained alive and no `AndroidRuntime` error appeared in the checked log window.
- Selected device instrumentation: **25 passed** across
  `VerbatimPitchPanelTest`, `VerbatimReviewCuesTest`,
  `VerbatimWorkspaceControlsTest`, and `AppNavigationResponsiveTest`.
  These are presentation/callback tests with isolated fixtures, not microphone
  or model accuracy tests.

APK: **252,548,527 bytes**, SHA-256
`e42071aaa904fdaec6d9e6f05f43a3b1444ab3e78c57121676157e79c16ab07c`.
Checksums are also attached to the private `android-v0.1.2` prerelease.

### Acceptance still pending

- No microphone session was recorded. Indonesian/English WER/CER, diarization
  DER, real F0/marker behavior, pause/resume, stop/finalize, playback and TXT
  export are not claimed passed.
- Whisper bundle provisioning through Profile/SAF and restore-after-reinstall
  were not exercised in this check.
- No paired Galaxy Watch was used; BPM baseline/divergence and PPG channel
  isolation remain unverified on hardware.
- This was one compact-phone presentation run, not acceptance for tablet,
  landscape, TalkBack, or large-font system settings.
- The smoke launch in airplane mode does not prove complete offline inference.

The earlier 0.1.1 verification table above remains historical; this follow-up
supersedes its “instrumentation compiled but not run” status only for the four
selected UI classes listed here.

## Repeat verification — 2026-10-17

- Python quality gates: Ruff check and format passed; **460 pytest passed**
  (one NumPy `Mean of empty slice` warning).
- Forced Android JVM rerun: shared **36**, inference **31**, app **783**, Wear
  **30**; **880 total, 0 skipped, 0 failures, 0 errors**.
- Production lint (`:inference:lintProductionDebug`, `:app:lintProductionDebug`,
  `:wear:lintProductionDebug`), `ktlintCheck`, `detekt`, and Phone/Watch
  production debug assembly succeeded.
- Additional on-device Compose coverage: **25/25** tests across the four UI
  classes above, plus **6/6** `AiCounselorCitationTest` tests. The latter
  verifies one collapsed source disclosure, grouped web/local citations,
  citation URL callbacks, and large-font layout using rendering-only fixtures.
- Reinstalled the production debug APK on `RRCN3008VYE` (Samsung SM-G988B,
  Android 13) with `adb install -r -d`; result `Success`. Package remains
  `com.stressdetector`, versionCode **3**, versionName **0.1.2**, UID **10446**;
  first-install time remained `2026-10-06 14:13:13`.
- Before/after replacement SHA-256 remained identical for
  `stress_sessions.db` (`6f7823d3cd813e8a2bd308f6ab69101cc100670fcf727656b806368f6d36714d`)
  and `knowledge_base_secure.db` (`9cd7afad9b518461d15c3020eda1b87ed0443b9f580449dcd7136801848ceb1f`).
- Relaunch reached `com.stressdetector/.mobile.MainActivity`; process remained
  alive. The crash buffer contained older entries dated 2026-10-16 and no
  crash entry dated 2026-10-17 at verification time.
- Local APK SHA-256 matches the existing private GitHub `android-v0.1.2`
  prerelease asset digest exactly. README contains the current APK, checksum,
  historical release, and companion Watch download links; no duplicate release
  asset was created.

### Full-product acceptance gaps — static audit, not runtime claims

- The bundled RAG manifest is empty and the runtime RAG release gates remain
  blocked; grounded local-document answering is therefore not accepted as ready.
- The inspected Room chat database path stores message/context fields without
  the Keystore-backed database encryption required by the PRD. This needs a
  separate migration/security change before sensitive chat storage is accepted.
- MADC motion annotations do not currently participate in the inspected SQA
  gate, and Home can select a raw diagnostic chart when preprocessing has
  completed but the result is rejected. These findings do not establish that
  ONNX consumes raw PPG or that any stored result is fabricated.
- A phone ingress backpressure message claims Watch retry although the active
  direct-message path has no durable batch retry; this needs truthful status or
  an explicitly specified transport recovery design.
- The read-only UI audit found a stale AI+ composer accessibility description
  and missing textual gap semantics on History/Verbatim waveforms. Broad screen
  coverage for Profile, Home, History, and Verbatim is still incomplete.
- No Watch was connected to ADB during this repeat run. Pairing, live PPG,
  stress-session parity, and full Verbatim microphone/runtime acceptance remain
  unverified on physical hardware.

### Device state after instrumented tests

Immediately after the connected instrumentation task, Android PackageManager
logged `onPackageRemoved pkgName=com.stressdetector userId=0` at
`2026-10-17 07:13:02`; subsequent checks found no production package and no
`/data/user/0/com.stressdetector` directory. The earlier replacement install
and database-hash comparison had succeeded, but the connected-test cleanup
removed the app afterward. No further install was attempted: with the package
and private data directory absent, another `install -r -d` would be a fresh
install and could not satisfy the user's data-preservation condition. Device
installation is therefore **blocked pending an explicit recovery/fresh-install
decision**. The instrumentation result remains valid for those 31 presentation
tests; it does not imply that the app is currently installed.

### Fresh-install recovery follow-up — 2026-10-17

The user subsequently authorized a fresh install because PackageManager and the
app-private directory no longer contained `com.stressdetector`. No uninstall or
`pm clear` was run by this follow-up.

- Device: `RRCN3008VYE`, Samsung SM-G988B, Android 13. No Watch was listed by
  `adb devices -l`.
- Installed local production-debug APK with `adb install -r -d`; output:
  `Success`.
- Installed package: `com.stressdetector`, versionCode `3`, versionName `0.1.2`.
  First-install and update times both read `2026-10-17 07:24:00`, confirming
  this was a fresh install, not a data-preserving replacement.
- APK size `255732794` bytes; local SHA-256
  `91cf17afb44e5128f01560ca8184c283ee540da37f454d27b3c7dc756fb12f03`.
- `com.stressdetector/.mobile.MainActivity` launched and remained alive (PID
  `28551`). Home showed the permission-required state for audio and Bluetooth;
  no permissions were granted, and no stress session was started.
- Crash-buffer inspection found no `com.stressdetector` crash after install.
  Existing `FATAL EXCEPTION` entries were from `com.coffeecreator.app` on
  2026-10-16 and are unrelated to this package.
- Screenshot: [fresh-install Home permission state](fresh-install-home-20261017.png).
- The published private GitHub `android-v0.1.2` APK asset has SHA-256
  `e42071aaa904fdaec6d9e6f05f43a3b1444ab3e78c57121676157e79c16ab07c` and size
  `252548527` bytes. It differs from the installed local artifact; this run
  does not claim that the existing download link serves the exact APK tested.
- Watch pairing, permissions, live PPG, AI+ model/RAG, and stress-session
  acceptance were not tested in this smoke run. The fresh install has no
  restored app-private databases or downloaded model state.
