# Stress Detector Phone 0.1.5 — internal debug preview

`versionCode=6`, package `com.stressdetector`. This is an internal prototype,
not a diagnosis, clinically validated instrument, or Play Store release.

## Changes

- Align Android PPG filtering, window slicing, and feature cadence with the
  pinned model contract and observed Watch capture cadence.
- Preserve preprocessing fingerprints and reject invalid/insufficient signal
  instead of forcing a prediction.
- Keep ONNX model, calibration, and decision thresholds unchanged.

## Verification evidence and limits

- This standalone checkout passed 868 JVM tests: 38 shared, 34 inference, and
  796 app tests. `ktlintCheck`, `detekt`, app/inference production lint, and
  `:app:assembleProductionDebug` also passed.
- Wear compile/unit-test acceptance is not claimed from this checkout: its
  Samsung Health Sensor SDK AAR is not present locally. Watch source is
  unchanged in v0.1.5; the companion APK remains the previously released 0.1.1.
- Replay of one prior recording passed raw-to-clean/features/ONNX parity for
  seven Green and seven IR windows.
- A fresh 300-second session produced Green `Normal`; IR returned insufficient
  signal due to a real gap. Full-channel acceptance remains `NO_GO` because
  diagnostic Red boundary timestamps conflict.
- The paired Watch application is unchanged at v0.1.1. Physical Watch
  acceptance is not claimed by this release.
- Repeated stress-positive results still require development-only model
  evaluation. No result was relabeled to fit subjective expectation.
- AI+, Verbatim+, full accessibility, and all-screen end-to-end acceptance were
  not repeated for this release.

## Download

- Phone v0.1.5: [APK](https://github.com/Adrian463588/android-stress-detection/releases/download/android-v0.1.5/app-production-debug.apk)
- Watch v0.1.1 (unchanged): [APK](https://github.com/Adrian463588/android-stress-detection/releases/download/android-v0.1.1/wear-production-debug.apk)
- [Release page and checksums](https://github.com/Adrian463588/android-stress-detection/releases/tag/android-v0.1.5)

For an in-place update, install with `adb install -r -d`; do not uninstall or
clear application data. The repository is private, so downloads require GitHub
access.
