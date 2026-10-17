# Stress Detector 0.1.1 — Verbatim+ internal preview

Phone and Watch versionCode 2, productionDebug; phone Android 8+ (API 26), Watch
API 28+. This is a locally
built, debug-signed prerelease, not a Play Store or validated forensic release.
Physical device and bilingual accuracy testing remain user-owned.

## Improvements

- Separate real PCM amplitude envelope and openSMILE F0 charts, live and saved.
- Bounded encrypted waveform/F0 chunks with 60-second archive paging, gaps,
  marker explanations and pause-aware playback. No reconstructed legacy pitch.
- Literal cue, acoustic/prosody, manual-note and optional independent PPG marker
  boundaries share the audio review chart without a fused deception score.
- Native speech crops no longer decode uncovered silence. A blank crop preserves
  valid neighboring speaker turns; a blank final terminates the partial safely.
- Raw whole/crop/partial/backend evidence remains available. Suspicious ASR
  repetition requires review instead of becoming an automatic cue or AI fact.
- Signal/native evidence follows ZIP and scoped encrypted SAF backup. Existing
  recordings, model setup, Room schema and manual corrections are preserved.

Whisper already handles v4 Indonesian/English final decoding; it does not itself
perform diarization. The native segmentation/embedding speaker path remains
separate. No WER/CER/DER, microphone, Watch or clinical improvement is claimed
from numerical unit fixtures or compiled instrumentation.

## Download and upgrade

Local gates: 879 JVM tests, zero failures/errors; all-module ktlint/detekt;
Phone/Wear Android lint; native and both APK builds; app instrumentation
compilation/assembly. Android lint retains 73 phone warnings/2 hints and 9 Watch
warnings; no physical instrumentation was run. See
[versioned SHA-256 checksums](https://github.com/Adrian463588/android-stress-detection/releases/download/android-v0.1.1/SHA256SUMS.txt).

The attached `app-production-debug.apk` is the phone build;
`wear-production-debug.apk` is the matching-source companion. `SHA256SUMS.txt`
identifies both exact artifacts. Repository access is required while private.
Watch/shared source already differs from the historical 0.1.0 release, so use
the pair from this release; physical pairing and PPG acceptance remain pending.
Historical releases are preserved in the repository's Releases page.

For a compatible existing installation, use Android's update flow or
`adb -s <YOUR_SERIAL> install --user 0 -r app-production-debug.apk`.
Do not uninstall or clear data. A different signing key cannot update in place;
stop and inspect the mismatch rather than deleting the existing application.
No ADB command was executed as acceptance of this release. Create a real SAF
user backup with your recovery password before any future reinstall experiment.

This APK includes the existing internal ONNX models and pseudonymized PPG parity
feature vectors (100 each for Green/IR, with participant/phase/window IDs and
probabilities), explicitly authorized by the user for this private release.
The packaged vectors are derived features, not raw PPG waveform recordings.
Do not make the repository/assets public without checking participant consent
and the Samsung Health Sensor SDK/openSMILE distribution terms separately.
Speech/LLM model provisioning remains through Profile/SAF, then local inference.

See [local gates, behavior contract and manual checklist](https://github.com/Adrian463588/android-stress-detection/blob/android-v0.1.1/docs/evidence/verbatim-audio-20261015/acceptance.md).
