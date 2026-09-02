# SPEC_IMPLEMENTATION — Production Stress Detection, PPG, Live Verbatim

Date: 2026-08-09
Branch: `codex/android-devsecops-20260808`
Mode: BMAD/SDD, RTK + Caveman, minimal diff

## Baseline and preservation boundary

- Read-only audit completed before implementation. No source file from this change was modified during audit.
- Baseline and post-change focused gates passed with an isolated Gradle cache: `:shared:test`, `:inference:testDebugUnitTest`, `:app:testDebugUnitTest`, `:wear:testDebugUnitTest`.
- ADB currently reports no attached devices; physical acceptance is pending.
- Worktree was already dirty: 106 tracked files changed plus unrelated deletions/untracked artifacts. Existing changes are user-owned and must remain intact.
- Only allowlisted files for this refactor may change. Do not reset, clean, stash, delete, overwrite legacy model artifacts, or create `.bak`/backup copies.

## Capability contracts

### CAP-01 — Supported Samsung acquisition and lifecycle

Use the PDSatu-literal individual compatibility trackers: `PPG_GREEN` (25 Hz), `PPG_IR` (100 Hz), and `PPG_RED` (100 Hz diagnostic), with approximately 300 raw samples per callback batch. These Samsung APIs are deprecated but remain the active, explicitly validated contract for Galaxy Watch 5. `PPG_CONTINUOUS` is legacy/reference only and is not an active fallback. Manifest-declared fractional source/model rates remain authoritative on the phone; nominal tracker rates are diagnostics, not silent replacements.

Readiness requires capability success and at least one accepted status-0 sample from each required channel. Lifecycle states are `Disconnected`, `Connecting`, `CapabilityChecking`, `Ready`, `Recording`, `Reconnecting`, `Draining`, `Completed`, `ActionRequired`, and `Failed`.

### CAP-02 — Bounded, ordered transport

Samsung callbacks only validate and enqueue. Wear ingress capacity is 500 raw samples; phone ingestion capacity is 64 batches. Callback code must never perform durable writes, network calls, directory scans, or `fd.sync()`. Overflow or unrecoverable gaps latch `transport_incomplete` and stop acquisition; samples are never silently dropped.

Stop order: close ingress → `HealthTracker.flush()` → await flush completion/timeout → unset on the same serial handler → drain actor/outbox → terminal receipt/ACK → disconnect.

### CAP-03 — Raw-to-ONNX parity

Create a new immutable model run. Green uses 25 Hz. IR training emulates the supported source by anti-aliasing 100→25 Hz, then applying the same timestamp-aware 25→100 Hz resampling used on Android. Process each 180-second window identically in Python and Kotlin. Keep the public ONNX contract `float_input` float32 `[1,34]` to `stress_probability` float32 `[1]`, with the existing 34 feature names/order. Non-finite features now reject the window; they are never replaced with zero.

Use participant-level splits, seed 42, one final holdout, and balanced accuracy > 0.80 per sensor. Candidate manifests remain `android_ready=false` until raw-to-feature parity, ONNX parity, checksums, and physical gates pass. If a labeled continuous-source IR gate is unavailable or fails, Green remains the production stress source and IR is diagnostic-only.

Session contract: 300 s, core `[30,270)`, 180 s windows, 10 s stride, seven windows/channel. Require at least 4 valid windows; aggregate median per channel; do not introduce an untrained fusion model.

### CAP-04 — UI and chart behavior

Phone monitoring shows only timer, connection/signal quality, primary stress result, and a Green/IR chart selector. Transport/model diagnostics are collapsed. Chart projection is off-main, bounded, immutable, timestamp-ordered, and visibly breaks at real gaps.

Wear shows phase, connection/error state, timer, and Green/IR readiness; remove fake controls and redundant metrics. Compose collection is lifecycle-aware and adaptive to compact widths/font scales.

### CAP-05 — Verbatim session integrity

Persist session ownership before starting Watch/audio. Attach session ID and generation to every PPG/chart frame. Use one phone monotonic `SessionTimeline` for audio, Watch, transcript, and divergence markers. Remove stale readiness polling and fixed delays. Stop by setting a cutoff, draining, `cancelAndJoin`-ing collectors, then persisting one immutable snapshot. Speaker edits are reducer-owned and durable.

## Public contract changes

- `PpgType`: expose separate `sourceRateHz`, `modelRateHz`, and raw batch duration.
- Session/batch codec v2: add `generation`, `peerNodeId`, and `sourceRateHz`; decode v1 for compatibility.
- `SessionReadyAck`: no default `greenAvailable=true`/`irAvailable=true`; both values are explicit.
- Persist and validate Watch `sourceNodeId` separately from phone payload `peerNodeId`.
- `PpgChartFrame`: include session ID and generation.
- Model manifest: include source rate, model rate, preprocessing/feature versions, checksum, parity status, and `android_ready` gate.

## Allowlist

### Android

- `shared`: PPG types, batch/READY contracts, codecs, session identity.
- `wear`: Samsung continuous adapter, `PpgTrackingService`, bounded ingestion, repository, outbox, permissions.
- `app`: Wear connection/command/session listeners, PPG repository/buffer/preprocessor/SQI, inference artifact gate, Verbatim engine/timeline/finalizer, monitoring screens/ViewModels.
- `inference`: feature extractor and parity/manifest validation.
- Related unit/Compose tests and non-release parity fixtures only.

### Model pipeline

- `PipelinePython/configs/android_continuous.yaml`.
- Relevant `PipelinePython/common` preprocessing, feature, training, export, validation, and Android-vector code.
- New versioned model/export/report paths only; legacy `v4.0` artifacts are immutable.

## Acceptance matrix

- Software: unit, lint, assemble, asset checksum, model schema, feature/probability parity, and `verifyNoLegacyModelAssets` pass.
- Concurrency: burst overflow, duplicate START, lost READY, reconnect, stale generation, process death, flush ordering, duplicate/reordered/gapped batches, terminal receipt retry.
- Timeline/UI: no session A leakage into B; readiness skew 0/5/15 s preserves marker linkage; speaker edits persist; phone and Wear compact/font-scale tests pass.
- Device: with paired phone/Watch, screen-off 300 s capture, raw Green/IR ≈25 Hz, canonical IR ≈100 Hz, all ACKs/sequence IDs, forced transport loss/recovery, seven valid windows, final prediction/History/transcript/divergence/terminal receipt. No device evidence means no production-complete claim.

## Verification commands

```powershell
python -m pytest -q
python -m PipelinePython data --config PipelinePython/configs/android_continuous.yaml --run-id <emitted-run-id>
python -m PipelinePython peaks --config PipelinePython/configs/android_continuous.yaml --run-id <emitted-run-id>
python -m PipelinePython stress-onnx --config PipelinePython/configs/android_continuous.yaml --run-id <emitted-run-id> --sensor ppg_green

.\gradlew.bat :shared:test :inference:test :app:testDebugUnitTest :wear:testDebugUnitTest --no-daemon
.\gradlew.bat :app:lintDebug :wear:lintDebug :app:assembleDebug :wear:assembleDebug --no-daemon
```

## Risks

- Existing IR model was trained at 100 Hz and is not evidence for supported continuous IR 25 Hz. Historical 100→25→100 is a proxy until labeled continuous-source holdout data exists.
- Current model manifests declare `android_ready=false`; release packaging must fail closed.
- Device acceptance is unavailable until ADB discovers both endpoints. Wi-Fi ADB loss is transport availability, not an app crash.
- Software evidence on 2026-08-09: `python -B -m pytest -q` passed (231 tests, 3 warnings); `ruff check .` and `ruff format --check .` passed. The isolated v4.1 run completed with participant holdout metrics above 0.80 balanced accuracy for Green/IR. `PipelinePython/03_stress_onnx/tools/validate_model_index.py` and `:inference:syncInferenceAssets` fail closed while indexed manifests remain `android_ready=false`; this is expected until raw-to-Kotlin parity and physical acceptance are proven. The app unit suite previously passed 247 tests, inference raw-to-feature parity parsed all 94 valid vectors per channel and executed its deterministic 10-per-channel sample, and app lint/debug assembly pass only when the intentionally blocked inference sync task is excluded.

## Latest implementation evidence

- The worktree remains intentionally dirty with unrelated user/raw/artifact changes; only the allowlisted release-evidence changes in this audit are candidates for the checkpoint.
- `adb devices -l` currently reports no attached device, so no new install, sensor, reconnect, five-minute, or paired phone/Watch evidence is claimed in this snapshot.
- Focused Python evidence: 100 v4.1 feature→ONNX vectors pass `<1e-5`; this is feature-vector/native-vs-ORT evidence, not proof of raw-signal Kotlin parity.
- No production fake PPG generator is present in `app`, `wear`, `shared`, or `inference` main sources. Physical sensor-light, 300-second, final-prediction, History, divergence, and forced-reconnect gates remain unproven until the Watch endpoint is reachable.
