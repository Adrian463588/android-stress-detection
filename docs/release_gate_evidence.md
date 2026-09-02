# Release Gate Evidence: Clinical Companion Android App

Snapshot: 2026-08-09 on `codex/android-devsecops-20260808`.

This document records evidence actually available in the current checkout. A
software pass does not promote the model or replace paired phone/Watch and
microphone acceptance.

## 1. QA & Unit Test Results (Gradle)
- **Status:** [PASS — software scope]
- **Target:** 100% pass rate for critical path clinical features.
- **Evidence:**
  - Full app unit suite passed: `:app:testDebugUnitTest -x :inference:syncInferenceAssets`.
  - Focused Verbatim finalizer test passed, including persisted RAG citation provenance.
  - Debug Android-test APK assembly and debug lint passed when the intentionally
    blocked inference asset-sync task was excluded.
  - JUnit report: `android/app/build/reports/tests/testDebugUnitTest/index.html`
  - Lint report: `android/app/build/reports/lint-results-debug.html`
  - This does not prove physical sensor, microphone, or instrumentation acceptance.

## 2. Physical Device Benchmark Results
- **Status:** [BLOCKED — no device evidence]
- **Target:** P95 inference latency < half step interval.
- **Evidence:**
  - `adb devices -l`: no attached device.
  - P50/P95 latency: not measured.
  - Paired Samsung phone/Watch capture, reconnect, ACK, seven-window result,
    History, and live divergence evidence: not measured.
  - Profiling target: `android/app/build/outputs/benchmarks/latency_report.json`

## 3. 60-Minute Memory Load Test
- **Status:** [PENDING — external runtime test]
- **Target:** No OutOfMemory (OOM) errors during 60-minute continuous live monitoring & verbatim processing. Memory footprint stable (no memory leaks).
- **Evidence:**
  - Initial/final/max memory: not measured.
  - Memory profiling target: `android/app/build/outputs/benchmarks/memory_load_60m.csv`

## 4. Dependencies & Contracts
- **Inputs Required for QA:**
  - Source code, Unit/UI tests, Android SDK, physical device/emulator connected via adb.
  - Test APK / Debug APK.
- **Outputs Generated:**
  - Automated test reports, Lint reports, Benchmark data (CSV/JSON), Memory dump logs.
- **Dependencies:**
  - `dumpsys meminfo` for memory tracking.
  - Macrobenchmark / Microbenchmark AndroidX libraries.

## 5. Release blockers

- `data/artifacts/export/model_index.json` is intentionally empty with
  `release_status=blocked_android_evidence_pending`.
- Canonical readiness is blocked at `191/192` PPG Gold groups because
  `ID-12 / relax / ppg_red` has seven invalid windows; no synthetic repair is
  permitted.
- Physical Samsung phone/Watch, microphone, instrumentation, and 60-minute
  resource evidence must be completed before a production-complete claim.

## 6. AI+ local-first evidence — 2026-08-27

- **Software status:** PASS for the local-first resolver, source-tier mapping,
  checksummed bundled-manifest handling, consent-gated web path, citation
  provenance persistence, model-missing guard, and AI+ unit tests.
- **Bundled corpus status:** `EMPTY`. No approved licensed counseling corpus was
  found in the checkout, so no project documentation, biosignal data, logs, or
  experiment artifacts are packaged as chatbot knowledge.
- **Phone smoke:** PASS on `RRCN3008VYE` / `SM-G988B`. The installed debug APK
  opened AI+ and displayed the real `Model belum tersedia` recovery state. The
  screenshot is UI/runtime evidence only and does not prove model-backed answer
  generation or web citation retrieval.
- **APK:** `android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256
  `2787928C1A1E9D9E0291D21810EF59D545E93F3595B1154396B44B88CB0CDE58`.
- **Watch/target phone:** no claim. `QSWSEMRKNFZ9LJRC` and the Watch endpoint
  remain blocked when they are absent from ADB.
