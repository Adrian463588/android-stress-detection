# Wear–Mobile Stress Detection Repair Plan

## Scope

Target: `D:\SEMESTER12\SkripiDump\ProsesData\android` on branch
`codex/android-devsecops-20260808`.

Read-only references: PDATA/PDSatu, AndroidProjectPengambilanDataPPGECG,
kaistppg, Samsung Health Sensor SDK 1.4.1, and Samsung's official sensor
documentation. No reference checkout, raw data, model export, or unrelated
dirty worktree change is modified.

## Current evidence

- Wear uses `PPG_CONTINUOUS` with Green and IR, but listener registration is
  posted asynchronously and registration failures are not converted into an
  explicit sensor state.
- `SamsungContinuousPpgSource.stopCompleted` is not reset for a new session;
  this can break a later flush/stop cycle.
- Phone ping refresh is serialized, but capability refresh can clear pending
  nonce state while a pong is in flight; the nonce-less pong path is unsafe.
- Phone chart projection has a possible lost-wakeup race when samples arrive
  during frame-job replacement.
- Home finalization currently rejects valid core predictions when terminal
  transport is incomplete.
- Canonical model manifests currently report `android_ready=false`; packaging
  must remain fail-closed and must not be changed by assertion or fake metadata.

## Contracts

Every session-bound event carries:

```text
sessionId, sessionNonce, peerNodeId, sourceNodeId, lifecycleGeneration
```

The phone owns session lifecycle. Wear is a passive sensor producer. A sample
is accepted only when its identity and generation match the active session.
Samsung callbacks are decoded and enqueued into one bounded ordered queue;
overflow is an explicit error. No interpolation, synthetic sample, or silent
drop is allowed.

## Implementation sequence

### 1. Wear sensor truth and lifecycle

- Make source start/stop idempotent and reset all per-session completion state.
- Register the Samsung listener inside a guarded callback and surface
  permission, tracker, registration, and channel failures explicitly.
- Keep Green and IR readiness/failure independent. Send `SESSION_READY` only
  after both required listeners/data paths are active.
- Guard callbacks by session nonce and lifecycle generation; ignore callbacks
  after stop or session replacement.
- Flush and unset the listener deterministically, then disconnect the health
  service and stop the foreground service on every terminal path.
- Preserve phone reachability/node identity when `SESSION_START` arrives.

### 2. Phone Data Layer and transport

- Keep one coordinator for capability discovery, serialized ping, nonce,
  reachability, start, retry, stop, and terminal receipt.
- Capability listeners report nodes only; they do not clear pending pings or
  start competing refreshes.
- Correlate pong responses to the exact pending nonce and node. Stale,
  mismatched, or source-injected responses do not update reachability.
- Validate source/peer node, nonce, generation, session, batch sequence, ACK,
  NACK, duplicate, out-of-order, and terminal receipt serially and idempotently.
- Make `SESSION_STOP` drain sensor callbacks, Wear outbox, chart, and
  inference before the immutable snapshot and Room transaction.

### 3. Realtime chart

- Introduce one chart coordinator with a dirty revision/lost-wakeup-safe
  coalescing loop at approximately 100 ms.
- Use monotonic elapsed time on the X axis; retain source timestamps only as
  provenance.
- Keep raw buffers lossless for inference; decimate only the render projection.
- Render sequence/timestamp gaps as breaks and diagnostics, never as joined
  synthetic lines.
- Cancel chart jobs on session replacement or repository shutdown.

### 4. Home Stress Detection

- Keep Green and IR extraction, SQA, inference, and aggregation independent.
- Preserve the explicit `WindowAggregationResult` counts and rejection reason.
- Treat late/incomplete terminal transport as a warning when a valid core
  prediction already exists; do not erase that prediction.
- Distinguish `model_not_ready_android`, SQA failure, coverage/window shortage,
  and inference failure in state/UI.
- Read calibration and threshold from the validated canonical manifest only.
- Do not set `android_ready`, lower quality gates, use legacy assets, or add
  fake data. Model-unready must remain model-unready.

### 5. Verbatim live divergence

- Keep `UnifiedSessionEngine` as the single owner of raw PPG/live monitor
  lifecycle. Use generation guards and idempotent actor/mutex start/stop.
- Drain inference and divergence before cancelling collectors; persist the last
  valid event exactly once.
- Run Green and IR arousal analysis independently of model readiness. Publish
  BPM/baseline/delta/quality snapshots even when model probability is absent.
- Require the canonical configured BPM rise for two consecutive valid frames,
  then publish `PPG_AROUSAL`, `MODEL_AROUSAL`, or
  `PPG_AROUSAL_AND_MODEL`; recover after two valid frames below threshold.
- Use only `physiological arousal` and `stress-response divergence`; never
  `lie`, `truth`, or `deception` claims.

### 6. UI

- Keep Compose UDF/MVVM: route collects state once; composables are stateless.
- Home uses one compact status card with state, progress, Green/IR counters,
  one primary action, and collapsed diagnostics.
- Wear uses responsive `ScalingLazyColumn`, gradient-free pastel Green/IR
  sections, compact connection/progress/action content, and hidden technical
  details behind diagnostics.
- Display Samsung SDK, paired phone, Green sensor, and IR sensor status
  separately; never show disconnected without missing reachability evidence.

## Verification gates

1. Unit tests: listener registration failure/recovery, stop reset, callback after
   stop, generation/nonce/node validation, ACK/NACK, duplicate/out-of-order,
   outbox drain, and terminal receipt race.
2. Unit/Compose tests: chart lost-wakeup/coalescing/gaps, valid prediction with
   incomplete transport, model-unready distinction, Green/IR routing, finalizer
   idempotency, BPM two-frame arousal/recovery, and compact UI state changes.
3. Build with the isolated Gradle cache when needed:

```text
rtk .\gradlew projects --no-daemon
rtk .\gradlew :shared:test :inference:testDebugUnitTest :app:testDebugUnitTest :wear:testDebugUnitTest --no-daemon
rtk .\gradlew ktlintCheck detekt lint assembleDebug --no-daemon
```

4. Verify canonical asset allowlist, manifest checksums, schema `[1,34]`,
   scalar output, 100-vector parity `<1e-5`, and explicit `android_ready`
   status. A model readiness failure is not reported as a signal failure.
5. Physical acceptance requires an actually discovered ADB/mDNS Watch endpoint,
   installed phone/Wear APK hashes, sensor permissions, visible Green/IR sensor
   activity, raw sample counts, realtime chart, final prediction/reason,
   History persistence, and Verbatim divergence persistence. If the Watch is
   unavailable, report acceptance as blocked; do not invent an endpoint.

## Commit boundary

Stage only files changed for this repair. Review `git diff --cached`, commit a
semantic message describing Wear sync and Samsung PPG lifecycle fixes, and
leave unrelated dirty files unstaged.

## Audit addendum — serialized ping lease

The current Data Layer contract treats `MessageClient.sendMessage()` as
best-effort and non-retrying. The phone therefore owns the retry policy, while
the Wear side keeps durable PPG batches until a correlated ACK/terminal receipt
arrives. A refresh must not replace a pending ping nonce before its lease
expires; otherwise a valid pong can be rejected as stale. The phone connection
repository now uses a thread-safe pending-ping registry with a bounded lease,
same-nonce send-failure cleanup, and stale-pong rejection.

The Samsung source remains on one `PPG_CONTINUOUS` tracker for Green + IR. It
registers the listener before reporting readiness, verifies usable channel
samples before `SESSION_READY`, flushes before teardown, and invalidates late
callbacks by generation/epoch. Permission and SDK policy failures remain
explicit failures and are not converted into synthetic signal.

`SESSION_START` now fails closed when `sessionNonce` is blank. The Watch no
longer invents a local nonce that the phone cannot accept; this prevents a
capture from consuming sensor time while every resulting batch is rejected as
stale.

References reviewed:

- [Wear OS Data Layer client types](https://developer.android.com/training/wearables/data/client-types)
- [Wear OS node and capability discovery](https://developer.android.com/training/wearables/data/discover-devices)
- [Samsung Health Sensor SDK introduction](https://developer.samsung.com/health/sensor/guide/introduction.html)
- [Samsung HealthTracker API](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/HealthTracker.html)

## Implementation evidence addendum — 2026-08-08

- `UnifiedSessionEngine` now serializes start, stop, pause, resume, drain, and
  finalization through one lifecycle `Mutex`; a stop arriving while start is
  still awaiting Watch/audio readiness cannot bypass cleanup.
- `WearBatchListenerService` now orders `SESSION_READY` and
  `PPG_TERMINAL_RECEIPT` behind the same bounded single-worker queue as raw
  PPG batches. A full queue leaves the control message unacknowledged so the
  Watch outbox can retry it; it is not silently dropped.
- Local verification passed with the canonical asset-sync gate explicitly
  preserved: `:app:testDebugUnitTest` (160 tasks), `:shared:test`, and
  `:wear:testDebugUnitTest` (26 tasks). The normal inference asset gate still
  fails closed while `ppg_green` has `android_ready=false`.
- Physical acceptance is pending. `rtk adb devices -l` and `rtk adb mdns
  services` currently report no phone or Watch endpoint, so APK installation,
  sensor-light activity, raw sample counts, and end-to-end prediction remain
  unverified.
- The Wear permission gate no longer treats `POST_NOTIFICATIONS` as a sensor
  prerequisite. `BODY_SENSORS`, `ACTIVITY_RECOGNITION`, and the background
  body-sensor permission remain enforced because the Data Layer starts the
  health foreground service while the app is backgrounded. See the Android
  [notification permission guidance](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
  and [health foreground-service permission rules](https://developer.android.com/develop/background-work/services/fgs/service-types).
