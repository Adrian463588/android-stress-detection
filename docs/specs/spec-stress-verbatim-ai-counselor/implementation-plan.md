# Implementation Plan

## Baseline

- Read `D:/SEMESTER12/SkripiDump/ProsesData/AGENTS.md`, `PRD.md`, and prior `android/SPEC_IMPLEMENTATION.md`.
- Branch: `codex/android-devsecops-20260808`.
- Worktree already contains unrelated tracked and untracked changes. Do not reset, clean, stash, or stage them.
- Samsung contract verified against official documentation: continuous PPG reports Green/IR/Red at 25 Hz; on-demand 100 Hz is foreground-oriented and not a continuous five-minute contract.

## Audit hypotheses to prove or reject

1. `CounselorPromptBuilder` may truncate optional context before stress history or truncate current user text after history; reserve current text and history budgets explicitly.
2. AI+ history provider/view-model may expose stale enabled/consent status or invalid/transport-incomplete sessions as useful context; define valid-entry policy and explicit unavailable state.
3. Room restoration and optimistic chat updates may race generation/session switching; generation must read the active persisted session boundary and never duplicate retries.
4. Stress finalization must reject incomplete transport, non-finite probability/features, poor SQA, and stale model manifests without persisting a confident label.
5. Live divergence must reject stale session frames, enforce cutoff/drain order, and persist one immutable event snapshot linked to transcript/session IDs.
6. Phone/Wear Compose screens must remain gradient-free, bounded, and readable at compact dimensions/font scales.

## Planned allowlist

### AI+

- `app/src/main/java/com/stressdetector/mobile/domain/stress/StressHistoryContext.kt`
- `app/src/main/java/com/stressdetector/mobile/domain/stress/StressHistoryReader.kt`
- `app/src/main/java/com/stressdetector/mobile/domain/engine/CounselorPromptBuilder.kt`
- `app/src/main/java/com/stressdetector/mobile/ui/viewmodels/CounselorViewModel.kt`
- `app/src/main/java/com/stressdetector/mobile/data/db/StressSessionDao.kt` and related repository only if query contract is insufficient
- focused AI+ history/prompt/ViewModel tests

### Stress and Verbatim

- Existing `app/domain`, `app/signal`, repository, ViewModel, and Compose files only where audit identifies a defect.
- Existing inference/shared/Wear contracts only if required to preserve the approved rate/identity contract.
- New tests for stale frames, invalid history, prompt budget, process recreation, stop cutoff, and divergence linkage.

## State contracts

```text
AI+ history: Disabled | ConsentUnavailable | Ready(snapshot) | Empty(reason)
Stress: Disconnected | Recording | Draining | Completed | Failed(reason)
Verbatim: Idle | Starting(session) | Recording(session) | Stopping(cutoff) | Saved(snapshot)
```

Each persisted object carries its owning session ID. No fallback to current/global session is permitted.

## Acceptance matrix

| Area | Required evidence |
|---|---|
| AI+ history | Room projection, consent/settings, seven-entry cap, invalid filtering, deterministic serializer |
| Prompt | current user text preserved, history bounded, raw PPG absent, explicit no-history fallback |
| Chat state | process recreation, session switch, retry, duplicate-send, cancellation |
| Stress | SQA/rate/schema/non-finite/transport rejection, finite prediction aggregation |
| Verbatim | session-generation isolation, monotonic timeline, cutoff drain, speaker edit persistence |
| UI | gradient-free, pastel sensor colors, compact/font-scale rendering and no deception/diagnosis copy |
| Build | `:app:testDebugUnitTest`, `:wear:testDebugUnitTest`, `:app:lintDebug`, `:wear:lintDebug`, assemblies |

## Verification commands

```powershell
cd D:\SEMESTER12\SkripiDump\ProsesData\android
rtk proxy powershell -NoProfile -Command "& .\gradlew.bat :app:testDebugUnitTest :wear:testDebugUnitTest :app:lintDebug :wear:lintDebug :app:assembleDebug :wear:assembleDebug --no-daemon"
```

Run focused tests first. Use isolated temporary Gradle user-home only when global cache metadata blocks resolution; do not delete caches.

## Commit rule

After verification, stage exact changed files only. Commit semantic message on `codex/android-devsecops-20260808`. Do not include pre-existing mixed files unless hunks can be separated safely.
