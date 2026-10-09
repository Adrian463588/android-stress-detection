# Verbatim+ focused workspace — implementation and manual acceptance

Date: 2026-10-06. Scope: CAP-8 presentation and interaction hardening on
`android-devsecops-20260808`, following PRD/AGENTS/DESIGN/EXPERIENCE. The two new
UI/UX attachments are reference input, not measured usability results. This
document supplements the earlier engine evidence in
[`verbatim-review-acceptance.md`](verbatim-review-acceptance.md); it does not
promote bilingual, acoustic, offline or physical Watch accuracy.

## Delivered checklist

- [x] Read-first transcript with speaker, utterance time and readable body text;
  UTC, original-versus-edited text, audio quality and real openSMILE values are
  behind `Detail ujaran`. Partial and uncertain identities remain explicit.
- [x] Separate Transkrip and Marker tabs, source filters and counts of actual
  observed evidence. Text, audio, independent PPG and manual notes remain
  distinguishable. Unknown legacy events remain in All rather than being
  mislabeled as stress evidence.
- [x] Inspectable literal cue explanations during live capture even when audio
  cannot play. Playback continues to require prepared audio and, for sensor
  markers, an actual linked utterance. No word-level timing or deception score.
- [x] One lazy scroll owner per selected panel, stable keys and separate saved
  scroll positions. The live tail remains bounded; archived transcripts are
  complete. No nested transcript scroll or fixed-height transcript slot.
- [x] Stop/save and pause/resume live controls outside the scroll owner. Active
  command failures retain Stop while blocking a new start; pending commands
  display actual progress. Jump-to-latest is scrollable, not another fixed bar.
- [x] TXT is the primary archived action. ZIP and confirmed deletion are
  secondary. Frozen picker inputs, save/export/delete gates, operation feedback
  and cancellation handling prevent misleading success or selection races.
- [x] Partial-failure archives attempt the real audio loader; absent/corrupt
  files become explicit recoverable errors rather than indefinite preparation.
- [x] Microphone denial remains visible with a real system-settings action.
  Archive review and WAV import do not silently start microphone recording.
- [x] Focused presentation components reuse Material tokens, wrapping controls,
  minimum 48dp targets, selection/expansion semantics, polite status updates and
  the application's reduced-motion policy. No new runtime dependency or fake
  production data was introduced.

## Implementation map

| Contract | Main ownership | Regression coverage |
| --- | --- | --- |
| Immutable state and action readiness | `VerbatimWorkspace`, existing ViewModel | `VerbatimWorkspaceTest` |
| Read-first utterance and disclosure | `VerbatimTranscriptCard`, `VerbatimDisclosure` | Compiled Compose cue interaction tests |
| Actual cue explanation | `VerbatimReviewText`, existing pure analyzer | `VerbatimReviewCuesTest`; existing lexical tests |
| Marker source identity and real evidence | `VerbatimMarkerFilters`, `VerbatimMarkerCard` | Taxonomy/channel/count unit tests; compiled filter semantics test |
| Reachable capture and secondary archive actions | `VerbatimWorkspaceControls`, route | Compiled `VerbatimWorkspaceControlsTest` |
| Export, deletion and partial-archive recovery | Existing `UnifiedSessionViewModel` boundaries | Read-only boundary audit; state guards; manual acceptance below |

Presentation receives existing speech/cue/prosody/marker evidence and emits
ViewModel events. Lexicons, signal thresholds, native runtimes, encryption and
inference remain at their existing boundaries. KISS/DRY guidance reduced clutter
and centralized readiness instead of introducing a replacement stack.

## Physical acceptance — pending, user-owned

Record APK checksum, device/Android version, speech bundle, language and actual
failure observations. Use consenting real audio/PPG; keep sensitive artifacts
outside Git. Compiling a Compose test is not executing it.

- [ ] Compact phone, landscape, tablet/expanded width and 200% font: verify
  readable transcript, wrapping controls and reachable Stop while scrolling.
- [ ] TalkBack: verify tabs, source filters, disclosure state, speaker labels,
  playback, denied permission, unavailable states and confirmed deletion.
- [ ] Record real Indonesian and English interviews offline after provisioning.
  Verify partial-to-final replacement, stable/uncertain speaker identity and
  first utterance delivery. Observe latency rather than infer it from source.
- [ ] While recording, inspect a literal cue and marker source without pausing
  capture; unavailable playback must stay disabled. Inspect openSMILE evidence
  only after native execution and baseline readiness; no stress/lie claim.
- [ ] Pause/resume/stop and active-back handling: verify WAV seek alignment,
  saved edits, actual finalization warnings and no hidden recording service.
- [ ] Reopen a completed and a failed-with-evidence archive; missing/corrupt
  audio must show a recoverable error, not endless preparation or fake playback.
- [ ] Edit text/roles, reopen, TXT export and cancel. Inspect UTF-8, timestamps,
  literal cue explanations, manual notes, real acoustic/PPG context and gaps.
- [ ] ZIP export/cancel/error and confirmed deletion: ensure no duplicate action,
  conflicting selection/start or success on a cancelled/failed write.
- [ ] Deny microphone, return from system settings, allow and retry. Check the
  notification permission separately; import/review remain available.
- [ ] Optional Watch gate: real paired Galaxy Watch batches, independent channel
  ONNX/SQA/baseline provenance and transport-gap abstention. Audio-only review
  must remain useful when no PPG evidence is available.

No ADB, install, device recording, generated preview PNG or physical usability
claim is part of this delivery. openSMILE remains subject to its noncommercial
research license. Real cue precision/recall, diarization errors and device
resource/thermal measurements remain pending.

## Final local evidence

The final six-gate run passed (`BUILD SUCCESSFUL`, 6m28s): ktlint, detekt,
production debug Android lint, production debug unit tests, Android-test Kotlin
compilation and production debug APK assembly. Unit XML reports contain **648
tests in 163 suites, zero failures, errors or skips**. This UI stage adds 16 pure
presentation-policy regressions and five Compose interaction cases; the existing
narrow-width cue interaction case was updated. Compose cases were compiled,
not executed. Static Sherpa, native packaging and no-legacy-model checks passed.

Android lint reports **zero errors, 72 warnings and two hints**. The existing
application is not warning-free; the new explicit system-settings intent also
has a nonblocking `UseKtx` suggestion for `String.toUri`. No baseline or severity
was weakened to pass these gates. An earlier run exposed four detekt findings;
the final patch resolves them by splitting utterance evidence presentation,
using the existing Result-style I/O error boundary and shortening the long line.

APK: `app/build/outputs/apk/production/debug/app-production-debug.apk`,
280,794,648 bytes; observed write time `2026-10-06T06:42:35.9911996Z`.
SHA-256: `451ffcd29c9f0d2a5c5695e79597573c2787937372b29b2a93efa33e0d5286a4`.
This supersedes the older UI/APK checksum in the engine acceptance document.
Compare the actual checksum before recording manual acceptance; rebuilding can
change the artifact.

The read-only subagent reviewed live command errors, permission recovery,
loading, archive mutation boundaries and wrapping playback controls. Its final
quality-label concern was checked against the actual producer: Sherpa fills
the historically named `confidence` field using `SpeechSegmentQuality.score`
(observed finite ratio, RMS and clipping), not an ASR posterior. The concern was
withdrawn after source verification; the UI now explicitly calls it a heuristic
audio-quality score. No delegated build or physical acceptance is claimed.

Gates ran on a preserved dirty workspace, including unrelated application and
pipeline changes. This is scoped current-workspace software evidence, not
clean-checkout acceptance of all features. Source-only staging excludes those
unrelated changes, raw data, models, AARs, APKs, caches and generated artifacts.
No network/model release readiness or clinical metric was promoted.

Reproduce from the Android directory with the local provisioned dependencies:

```powershell
rtk proxy .\gradlew.bat `
  "-PinternalModelIndex=../data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json" `
  "-PdetectorContract=../data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json" `
  :app:ktlintCheck :app:detekt :app:lintProductionDebug `
  :app:testProductionDebugUnitTest :app:compileProductionDebugAndroidTestKotlin `
  :app:assembleProductionDebug --console=plain --max-workers=2 --continue
```

## Primary design references

- [Compose UDF](https://developer.android.com/develop/ui/compose/architecture)
- [Accessible API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- [Flow layouts](https://developer.android.com/develop/ui/compose/layouts/flow)
- [Material insets](https://developer.android.com/develop/ui/compose/system/material-insets)

These sources informed state ownership, reachable targets, wrapping actions and
progressive disclosure; they do not establish measured usability on every device.
