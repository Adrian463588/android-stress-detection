# Verbatim+ data-preserving phone verification

Date: 2026-10-06, Asia/Jakarta. CAP-9 follows the latest request to install and
test, superseding earlier manual-only acceptance. The user then chose
**non-recording tests first**; no microphone interview or Watch session was
started. This is scoped device/UI/native-smoke evidence, not speech accuracy,
clinical validation or acceptance of every Verbatim+ feature.

## Device, installation and preservation

Device: `RRCN3008VYE`, Samsung SM-G988B (S20 Ultra), Android 13, arm64.
Preflight found only `com.stressdetector.research`; canonical production
`com.stressdetector` was absent. The first production install therefore created
a separate package, not a migration of legacy sessions. The research app and
its encrypted data remain present and untouched.

The initial reviewed APK installed successfully. After responsive corrections,
the production APK was replaced with `adb install --user 0 -r`; no uninstall,
`pm clear`, session deletion or cross-UID copying occurred. Separate test APKs
were installed with `-r -t`. Existing owner-user data remained in place; no
restricted work/Secure Folder profile was targeted. No active recording service
was present before update or after dialog/import cancellation.

Production retained UID `10446`, signing key set `281` and owner-user data inode
`424497` through replacement. Local certificate SHA-256:
`fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4`.
The successful replacement used the same certificate; backup is disabled, so
uninstall/data clearing was never treated as a recovery method.

Aggregate SHA-256 fingerprints compare sorted `sha256sum` lines without
publishing their filenames, contents or encryption keys:

| Scope | File count before / after | Identical aggregate fingerprint |
| --- | --- | --- |
| Legacy databases, files, shared preferences, no_backup | 42 / 42 | `7f15937ff3e0dc95105826d9ddda7df91cd7ba2f98c95d6bb3e6d62449f09024` |
| Production sensitive payload and speech runtime, before / after replacement | 23 / 23 | `41a8b695f362d198df6de295ec0ac238d989480aa7e41ac0d589c89a4874eb43` |

The second comparison is not a production database row-migration test. The
production drawer remained empty; no existing interview was edited or deleted.
Initially missing speech/storage showed explicit recovery rather than a fake
transcript. Later inspection observed provisioned speech files and connected
storage/readiness. This observation is not an executed bilingual ASR acceptance
claim or evidence of migration from the research package.

## Corrections and executed checklist

- [x] Fix truncated bottom-navigation labels under large font. A stateless
  `AppBottomNavigation` measures actual themed text against available slot
  width. If labels cannot fit, all named icon tabs remain accessible and the
  complete selected destination appears above them. No shrinking user fonts,
  dependency addition or change to navigation events.
- [x] Fix overlapping Profile readiness text. `ProfileStatusRow` uses a wrapping
  FlowRow with the same combined accessibility description and theme roles.
- [x] Add three isolated Compose regressions: measured-fit labels, 320dp/200%
  named navigation actions and 48dp targets, and non-overlapping status wrapping.
- [x] Install and replace the production APK without deleting data; verify
  package identity, signer, data inode and the aggregate fingerprints above.
- [x] Execute ten explicitly selected instrumented cases on the phone. Final
  result: **`OK (10 tests)`, 10.019 seconds**. Earlier seven-case execution
  also passed before the layout corrections.
- [x] Inspect actual portrait, 200% portrait text and landscape expanded-width
  Verbatim+; confirm empty states, real controls, sidebar and navigation.
- [x] Inspect actual Profile at 200% font: storage label/status no longer overlap.
- [x] Open session options without starting capture; switch to English, observe
  `✓ English`, restore Indonesia, and cancel. Optional PPG is off by default.
  This proves selection/cancellation, not either language's ASR accuracy.
- [x] Open WAV import's Android document picker and cancel after verifying picker
  focus; return to production without creating a session or recording service.
- [x] Restore original `font_scale=1.0`, `accelerometer_rotation=1`,
  `user_rotation=0`. Existing screen-size/density overrides were not changed.
- [x] Inspect production process errors/exit information: no production crash or
  ANR was observed in this bounded run. Target restarts recorded by the runner
  and update are force-stop exits, not interview recovery acceptance.
- [x] Save and inspect actual screenshots; no fixture transcript or fake marker
  is shown as production/device evidence.

The first ten-case run had one failing fit-case assertion: a constrained 400dp
test did not expose all labels. The adaptive fallback is expected when measured
labels cannot fit. The fit-case fixture now uses a wider 600dp container; the
separate 320dp/200% case still verifies fallback, callbacks and touch targets.
The complete ten-case suite was rerun successfully, not merely recompiled.

A read-only subagent audited the test allowlist and storage boundaries. The
selected tests use component-local callbacks; they do not delete application
sessions or cancel user reminder work. The openSMILE case consumes numerical
test PCM and writes only its bundled native configuration. It verifies real JNI
graph execution, not real human pitch/arousal or deception detection.

Classic `uiautomator dump` encountered an already-registered UiAutomation service
from the existing local UI driver. These shell-tool exceptions were not
production app crashes. Inspection continued through that existing driver's
local CLI; no second UI service, cloud device, reset or unrequested tool install
was needed. Final interactions used freshly observed element bounds. Screenshots
were acquired after configuration/animation settled, not during transitions.

## Actual previews

[Portrait](evidence/verbatim-device-20261006/verbatim-portrait.png),
[200% font](evidence/verbatim-device-20261006/verbatim-font200.png),
[landscape](evidence/verbatim-device-20261006/verbatim-landscape.png),
[Profile 200%](evidence/verbatim-device-20261006/profile-font200.png),
[session options](evidence/verbatim-device-20261006/session-options.png), and
[empty session drawer](evidence/verbatim-device-20261006/session-drawer.png).
These show the actual non-recording application state; they are not interview,
transcript-quality, PPG or performance evidence.

## Final local verification

All seven requested local tasks passed. The initial correction run finished in
10m45s; the final test-fixture correction rerun passed in 1m2s. Current unit XML:
**648 tests across 163 suites, zero failures/errors/skips**. Android lint:
**zero errors, 72 warnings, two hints**; no baseline/severity was weakened.
Native packaging/static Sherpa/no-legacy-model checks also passed. Existing
compiler deprecation and Gradle compatibility warnings are not claimed fixed.

```powershell
# From android/, using the local run-scoped contract and dependencies.
rtk proxy .\gradlew.bat `
  "-PinternalModelIndex=../data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json" `
  "-PdetectorContract=../data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json" `
  :app:ktlintCheck :app:detekt :app:lintProductionDebug `
  :app:testProductionDebugUnitTest :app:compileProductionDebugAndroidTestKotlin `
  :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest `
  --console=plain --max-workers=2 --continue

rtk proxy adb -s RRCN3008VYE install --user 0 -r `
  app/build/outputs/apk/production/debug/app-production-debug.apk
rtk proxy adb -s RRCN3008VYE install --user 0 -r -t `
  app/build/outputs/apk/androidTest/production/debug/app-production-debug-androidTest.apk
rtk proxy adb -s RRCN3008VYE shell am instrument --user 0 -w `
  -e class com.stressdetector.mobile.AppNavigationResponsiveTest,com.stressdetector.mobile.VerbatimReviewCuesTest,com.stressdetector.mobile.VerbatimWorkspaceControlsTest,com.stressdetector.mobile.audio.VerbatimOpenSmileTest `
  com.stressdetector.test/androidx.test.runner.AndroidJUnitRunner
```

Final production APK SHA-256:
`2b3dcc9a09c1577d7282ec11d12e5efbfb993ba9333520756fc7fdb129cd7209`.
Size: 280,794,648 bytes; write time: `2026-10-06T07:47:28.3912372Z`.
The installed production `base.apk` SHA-256 was read from the phone and matches
this local artifact exactly.
Do not confuse this with the earlier UI-only APK checksum. Gates use the
preserved dirty workspace, not a clean-checkout release of unrelated changes.
Only scoped source/docs/privacy-safe previews are eligible for staging; existing
unrelated edits, raw data, model files, AARs, APKs and caches are excluded.

The delivery allowlist was checked against all 15 staged paths. Partial staging
kept unrelated MainActivity and README edits out of this change. Scoped
`git diff --cached --check` passed; a staged-text credential-pattern scan found
no matches, and the six previews were visually checked for unrelated private
content. This is not an audit of all dirty files or the entire repository history.
The only authorized delivery branch is `android-devsecops-20260808`; `main` is
excluded, and publication must use a non-force fast-forward push.

## Still pending — not promoted to pass

- [ ] Consenting Indonesian/English microphone conversations, live partial/final
  transcript latency and stable/ambiguous multi-speaker diarization accuracy.
- [ ] Real speech cue precision/recall and openSMILE speaker baseline behavior.
- [ ] Real pause/resume/stop WAV alignment, active-back handling, finalized
  archive playback, edited TXT content/export and delete idempotence. No session
  was created just to claim these passed; existing data must not be deleted.
- [ ] Actual permission denial/grant/recovery around microphone recording.
- [ ] Full airplane-mode speech/summary inference after provisioning. No network
  state was changed; a native test without network calls is not this gate.
- [ ] Real Galaxy Watch PPG, independent-channel ONNX/SQA, BPM baseline/gap/
  recovery and stress marker acceptance. Home reported Watch unavailable.
- [ ] Full TalkBack journeys, physical tablet/foldable and all combined window/
  font/orientation cases. Phone landscape is not physical tablet acceptance.
- [ ] Thermal, sustained latency, RAM/CPU and battery benchmarks on real input.

## Primary implementation/testing sources

- [ADB](https://developer.android.com/tools/adb): same-package `-r` update keeps
  data; user selection avoids unrelated profiles.
- [apksigner](https://developer.android.com/tools/apksigner): signer inspection.
- [Selected instrumentation](https://developer.android.com/studio/test/command-line):
  an explicit class allowlist, not an unfiltered destructive test suite.
- [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults):
  named controls and minimum touch targets.
- [Text measurement](https://developer.android.com/develop/ui/compose/graphics/draw/overview):
  use actual measured text rather than guessing from character count.
- [Flow layouts](https://developer.android.com/develop/ui/compose/layouts/flow):
  wrapping when horizontal space is insufficient.

SDD/BMAD records the acceptance contract before changes. MVVM/UDF remains
unchanged; these corrections are stateless presentation/layout boundaries.
PONYTAIL/KISS guidance favors reuse of Material measurement/wrapping APIs over
new navigation frameworks. RTK/CAVEMAN/PONYTAIL remain authoring tools only.
