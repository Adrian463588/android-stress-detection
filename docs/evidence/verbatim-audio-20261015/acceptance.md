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
- [RTK](https://github.com/rtk-ai/rtk), [Ponytail](https://github.com/dietrichgebert/ponytail), [Caveman](https://github.com/juliusbrussee/caveman): authoring guidance only.
