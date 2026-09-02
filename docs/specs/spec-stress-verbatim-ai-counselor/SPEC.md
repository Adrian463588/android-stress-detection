# SPEC: Stress Detection, Verbatim Live Divergence, AI+ Counselor

Date: 2026-08-08
Target: `D:/SEMESTER12/SkripiDump/ProsesData/android`
Branch: `codex/android-devsecops-20260808`

## Why

Three local workflows must remain trustworthy across process death and continuous sensor input: PPG stress history must reach AI+ without raw-signal leakage, Verbatim divergence events must stay on one session timeline, and stress decisions must fail closed on incomplete evidence.

## Capabilities

### CAP-AI-01 — History retrieval

Intent: AI+ reads only bounded, persisted `stress_sessions` projections with consent/settings enforcement.

Success: latest seven sessions are deterministic, lineage-preserving, finite, and invalid/transport-incomplete sessions cannot become affirmative clinical claims.

### CAP-AI-02 — Historical prompt context

Intent: AI+ receives a stable, sanitized stress-history summary without crowding out current user text or RAG safety rules.

Success: prompt tests prove history survives budget trimming, current message remains complete, no raw PPG/session payload is emitted, and fallback is explicit.

### CAP-AI-03 — Chat/session continuity

Intent: persisted chat messages and active-session selection remain correct through recreation, switching, retry, and empty/error states.

Success: Room-backed history is restored before generation; no message or stress context crosses chat sessions; retry does not duplicate the user turn.

### CAP-STRESS-01 — Safe stress decision

Intent: Home/History use only valid core windows, finite 34-feature tensors, approved model manifests, and complete transport.

Success: invalid SQA, rate/schema/checksum mismatch, incomplete transport, or non-finite features produce `Signal unavailable`, never a confident label.

### CAP-VERBATIM-01 — Live divergence integrity

Intent: audio, transcript, Watch PPG, prediction, and divergence events share one phone monotonic session timeline.

Success: old-session frames/events are rejected, stop drains to cutoff, speaker edits persist, and each event links to the owning session/transcript when available.

### CAP-UI-01 — Minimal safe UI

Intent: Compose screens expose one status, timer, CTA, signal quality, and core prediction with gradient-free pastel channel distinction.

Success: phone/Wear compact and font-scale tests show no clipping; diagnostics remain collapsed and no UI claims deception or diagnosis.

## Constraints

- Strict MVVM/UDF; no Hilt or Kapt additions.
- No raw-data/model backups, reset, clean, uninstall, `pm clear`, or reference-project edits.
- Samsung `PPG_CONTINUOUS` raw Green/IR is 25 Hz; IR 100 Hz is canonical/model rate via timestamp-aware resampling, not five-minute raw acquisition. See Samsung [data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html) and [tracking lifecycle](https://developer.samsung.com/health/sensor/guide/tracking-data.html).
- Existing model bundles remain immutable; `android_ready=false` remains fail-closed until parity/checksum/device gates pass.
- PPG is a physiological arousal/stress-response marker, not diagnosis or deception detection.
- Preserve current dirty worktree; stage only files changed by this task.

## Non-goals

- No new sensor channel, fusion model, participant, cloud training, authentication, or RBAC.
- No automatic promotion of candidate ONNX assets.
- No physical-device production claim without paired Watch/phone acceptance evidence.

## Success signal

Focused Android unit tests, full relevant module tests, lint with zero errors, and debug assemblies pass. Python parity/history fixtures pass. Git commit contains only this task's allowlist; unrelated dirty files remain unchanged. Physical gate status is reported separately.

## Companion

See `implementation-plan.md` for audit hypotheses, file allowlist, state contracts, test matrix, and rollback-safe staging rules.
