# Implementation Plan: AI+ Reminder

## Contract-first sequence

Requirement → acceptance criteria → typed domain ports → encrypted/Room
boundaries → WorkManager state machine → UI state → tests → static/build/device
evidence → allowlisted Git delivery.

## File scope

- Domain ports/policy: `app/src/main/java/com/stressdetector/mobile/domain/reminder/`
- Room metadata: `data/db/ClinicalEntities.kt`, `ClinicalDaos.kt`, `StressDatabase.kt`
- Encrypted payload: `data/reminder/EncryptedAiReminderSecretStore.kt`
- Work and delivery: `WorkManagerAiReminderScheduler.kt`, `notification/`, `receiver/`
- DI: `di/AppLocator.kt`
- UI: `ui/viewmodels/AiReminderViewModel.kt`, `ui/screens/AiReminderSettingsSection.kt`,
  Profile and AI+ screens
- Lifecycle/permission/session integration: `DataLifecycleService.kt`,
  `MainActivity.kt`, `StressViewModel.kt`, `UnifiedSessionScreen.kt`

## Failure policy

| Failure | Production behavior |
|---|---|
| no consent/history/session | no planning or notification; explicit status |
| model file/runtime unavailable | failed state; no canned reminder |
| invalid schedule/content JSON | terminal failed state; retry only by user |
| notification permission/channel denied | terminal permission state; no content published |
| worker/process interruption | WorkManager retry/reconciliation; no synthetic state |
| stale past schedule after clock change | discard pending occurrence and replan |

## Verification matrix

- JVM: name normalization, relative date, schedule horizon, cooldown, output
  guard, strict JSON, delta/snapshot aggregation.
- Instrumentation: Room migration, encrypted payload boundary, channel
  visibility/public version, immutable ID-only PendingIntent, deep link, and
  process/reconciliation behavior where the connected target permits it.
- Compose: reminder consent/permission/model/session states at compact,
  medium, expanded, large font, and landscape widths.
- Static: ktlint, detekt, lint, tests, debug/release assembly with an explicit
  run-scoped model index. Missing production model index remains a build blocker.
- Physical: ADB serial, real paired PPG session, real model readiness, actual
  notification and deep link. If absent, report `BLOCKED`.

## Delivery boundary

Only the nested Android repository is eligible for staging. Existing dirty
files are reviewed before explicit allowlist staging; root pipeline changes and
generated build artifacts are excluded.
