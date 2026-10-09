# Verbatim+ pitch/speech APK — data-preserving reinstall

Date: 2026-10-06, Asia/Jakarta. Scope: CAP-9 verification of the CAP-10/11
application artifact. The latest user request permits local installation and
device tests. The prior non-recording choice remains authoritative: automated
tests do not initiate microphone capture, delete sessions or clear data.

## Verified installation

- Target: `RRCN3008VYE`, Samsung SM-G988B/S20 Ultra, Android 13, arm64-v8a.
- Production package: `com.stressdetector`; owner user 0; version `0.1.0`/1.
  Separate legacy `com.stressdetector.research` remains installed and untouched.
- No active production or legacy recording service was observed before update.
- The exact reviewed application APK from the existing build was installed
  with `adb install --user 0 -r`; result `Success`. No uninstall, `pm clear`,
  downgrade flag, session deletion or package migration.
- Installed `base.apk` SHA-256 matches the local artifact:
  `671ca6f89dca9c7e4bcb9b00de5293aac67ff4140b333b826ec5e8356de53d31`.
- Signing key set `281`, UID `10446`, owner data inode `424497`, package and
  data directory are unchanged. Reviewed APK certificate SHA-256:
  `fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4`.
  Pre-update installed APK hash matches the previously verified signer receipt;
  replacement was accepted without deleting data.
- Matching test APK `com.stressdetector.test` installed with `-r -t`; result
  `Success`. SHA-256:
  `d4f61fee743fba6d5920d8c82a03a360019ca32e0b35d72258c51614ce6d52c0`.
  Resolved runner is `androidx.test.runner.AndroidJUnitRunner` targeting production.
- Device reported approximately 3.5 GB free before update. No model download,
  network-state change, font/rotation or screen-size/density mutation occurred.
  Existing viewport is 1080x2400 over physical 1440x3200, density 420;
  font scale 1.0, auto-rotation 1, user rotation 0, airplane mode 0.

## Preservation receipts

For each package, hash every regular file in `files`, `databases`,
`shared_prefs` and `no_backup` through its existing debug `run-as` identity.
Sort the `sha256sum` lines and SHA-256 the UTF-8 lines joined with LF, without
a trailing LF. Capture immediately before and after application replacement,
before launching the updated app. Contents, filenames and keys are not exported.

| Scope | Files before / after | Identical aggregate SHA-256 |
| --- | --- | --- |
| Production persistent files | 86 / 86 | `7a04931c0a59221e4d27823361d483ab68c5430a6ee065cde4e117b69ab5e3b1` |
| Separate legacy persistent files | 42 / 42 | `3f68067f753a3b2daca6161b0bec2212e9ee02250159706463789a747dd04adf` |

These are this run's exact replacement receipts, not the older 23-file
production receipt. They demonstrate unchanged files at installation, not a
cross-package migration, full logical database audit or invariance after an
actively recorded session creates new evidence.

## Bounded device observations

- Production launched successfully through `am start -W`: `Status: ok`, cold
  launch `TotalTime: 2435 ms`. This is one command measurement, not a sustained
  startup/resource benchmark.
- Actual Home exposes Watch unavailable with connection recovery, not a fake
  physiological result. Verbatim+ was inspected from actual UI hierarchy.
- The manifest retains the supported exact legacy speech bundle
  `sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3`, without `whisper_stt`.
  APK support for Whisper does not install its weights. Explicit Profile
  update/import and native verification remain necessary; no upgrade was started.
- A production recording subsequently became active during device inspection.
  `UnifiedSessionService` was foreground and AudioService reported real mono
  16 kHz PCM capture. Origin/consent of that session was not established by this
  test; automated actions did not request a recording session. The user
  confirmed it had stopped and saved. A fresh read-only check found no active
  service and AudioService had recorded its stop; only then did instrumentation
  proceed. This run did not stop or finalize the recording.
- Read-only hierarchy observed the live pitch panel, bounded real observations,
  accessible graph summary and explicit unvoiced state. This is limited UI/runtime
  observation, not reference-calibrated pitch or recognition/diarization accuracy.
- No matching fatal exception, native fatal signal, ANR, linkage failure or OOM
  was found in the bounded current production PID error scan. Historical exit
  records include force-stops; they are not crashes. Absence in this scan is not
  proof that all application paths are issue-free.
- A single active-process memory snapshot reports total PSS 1,559,061 kB and
  swap PSS 218,113 kB. This is not a baseline/delta, isolated-model attribution,
  thermal acceptance or sustained resource benchmark.
- After runner restarts, the updated Verbatim+ screen showed the latest session
  as `Tersimpan · artefak siap`, `Audio siap`, and an enabled
  `Unduh transkrip TXT` action. The TXT action opened Android DocumentsUI's
  create-document picker with a `.txt` name. Back navigation canceled the
  picker and returned to the same saved session. No plaintext report was
  written; exported content/marker correctness remains untested.
- Screenshots exposed private transcript and device-file labels. They were
  kept only in ignored local build output while inspected, then removed.
  Generated screenshot/XML files under `/sdcard/verbatim-pitch-*` were also
  removed. No transcript, private screenshot, raw audio or fabricated preview
  is eligible for publication.

## Test boundary and checklist

- [x] Verify serial, package, version, reviewed artifact and installed checksum.
- [x] Replace application and test APK without deleting data.
- [x] Verify unchanged UID, signer key set, data inode and aggregate files.
- [x] Open updated production app and inspect bounded non-destructive state.
- [x] Observe live pitch/unvoiced semantics without initiating microphone capture.
- [x] Preserve the active recording and exclude private screenshot from publication.
- [x] After the recording stopped, execute 13 audited Compose UI cases:
  `OK (13 tests)`, 13.365 seconds. Pitch compact/200% font, pause/unavailable,
  gap/continuity, sidebar, controls and cue semantics passed as fixtures.
- [x] Execute four isolated bundle import/rollback cases:
  `OK (4 tests)`, 0.388 seconds. They use unique test-cache directories.
- [x] Execute two Sherpa native Indonesian lifecycle/preflight cases:
  `OK (2 tests)`, 10.608 seconds. A separate verbose rerun confirmed each
  returned status code `0` rather than being skipped. The existing legacy
  bundle was used; these are silent numeric PCM mechanics, not Whisper or ASR
  accuracy evidence.
- [x] Execute one packaged openSMILE native graph smoke case:
  `OK (1 test)`, 0.308 seconds. Harmonic PCM is a fixture, not human pitch.
- [x] Reopen production after runner restarts and confirm the newly saved
  session, audio-ready state, TXT picker launch and safe cancellation.
- [ ] Exercise idle-language options, import/cancel, full portrait/200%/
  landscape journey and privacy-safe previews. The 200% Compose fixture ran
  on the physical phone; the phone-wide font setting stayed at 1.0.
- [ ] Provision and execute the new Whisper profile through official setup.
- [ ] Consented bilingual reference recordings, literal transcript comparisons,
  stable speaker evaluation and WER/CER/DER.
- [ ] Actual pause/resume/stop/WAV alignment, archive seek/playback/TXT export
  and isolated deletion acceptance without deleting existing user sessions.
- [ ] Full airplane-mode interview, sustained resource/thermal measurements,
  physical tablet/TalkBack and paired Galaxy Watch PPG/ONNX acceptance.

A read-only subagent audited this 20-case allowlist. Sherpa preflight may
materialize verified local runtime files and openSMILE may write its pinned
configuration; neither test records speech, mutates sessions or validates
human inference. The test runner restarted the app only after the session had
stopped. No full `connectedAndroidTest`, uninstalling orchestrator or
`clearPackageData` was used. The selected cases total **20/20 passed**;
the additional verbose Sherpa rerun is a repeat of two cases, not two new ones.

## Source/software verification boundary

No Kotlin/production implementation was changed for this reinstall. The exact
APK and test APK match the final software receipt in
[pitch/speech acceptance](verbatim-live-pitch-speech-acceptance-20261006.md):
706 unit cases, ktlint/detekt/production lint/assembly and test compilation
previously passed; lint retains 72 warnings and two hints. Those local gates
were not rerun. The 20 selected physical instrumentation cases passed, but
none measures bilingual accuracy, actual Whisper pitch, real diarization or
Watch PPG/stress acceptance.

BMAD/SDD records the renewed CAP-9 authority before device mutation; existing
CAP IDs and privacy/accessibility/channel constraints are preserved. PONYTAIL
keeps the existing APK/runner/ADB paths and adds no runtime dependency. Only
test evidence/spec documentation changes; dirty unrelated worktree content,
models, raw data and legacy package are preserved. `main` is never a target.

Primary operational references:
[ADB data-preserving install](https://developer.android.com/tools/adb) and
[explicit instrumentation selection](https://developer.android.com/studio/test/command-line).
