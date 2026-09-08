# SPEC: AI+ Grounded Reminder Notification

Date: 2026-09-08
Target: `D:/SEMESTER12/SkripiDump/ProsesData/android`
Source of truth: `D:/SEMESTER12/SkripiDump/ProsesData/PRD.md`, `AGENTS.md`, `DESIGN.md`

## Why

AI+ needs an optional, local-only follow-up after a valid stress session. The
follow-up must be useful without pretending that an unrecorded grounding task
was assigned or completed. It must remain safe through process death, reboot,
timezone changes, model unavailability, permission denial, and database export
or deletion.

## Capabilities

### CAP-AI-04 — Consent-bound reminder planning

Intent: plan at most one approximate reminder for one eligible persisted
session using the real on-device LiteRT-LM runtime.

Success: the worker requires AI Counselor consent, AI+ Reminder consent,
stress-history opt-in, a valid session projection, and a future time within
48 hours. It rejects recurrence, invalid JSON, model-unavailable states, and
duplicate occurrences. A delivered reminder is subject to a 24-hour cooldown.

### CAP-AI-05 — Grounded natural reminder content

Intent: generate a short reflective notification from the latest valid session
projection and an optional locally stored display name.

Success: raw PPG, transcript, full chat, web evidence, and RAG documents never
enter the background prompt. The output guard rejects diagnosis, clinical
certainty, medication, deception claims, citations, and unrecorded grounding
claims. Rejected output is never replaced with canned or fabricated text.

### CAP-AI-06 — Persistent private delivery

Intent: deliver and deep-link a real notification without exposing session
content on a lock screen.

Success: WorkManager unique work survives process death; boot/timezone/time
changes reconcile persisted `Instant` values; the stable reminder channel uses
private visibility and a redacted public version; the immutable PendingIntent
contains only the app-owned reminder ID and opens AI+.

### CAP-AI-07 — Explainable settings and lifecycle

Intent: let the user name, consent, enable, pause, resume, retry, export, and
delete reminder data from Profile.

Success: every unavailable state is explicit, notification permission is
requested only when enabling reminders, sensor monitoring does not depend on
notification permission, and encrypted reminder payloads are included in
explicit export and purge paths.

## Constraints

- Kotlin + Jetpack Compose, layered Clean Architecture, MVVM/UDF.
- WorkManager is approximate; no exact alarm and no promise of minute-level delivery.
- Only Room reminder metadata is plaintext; display name and generated message
  are Keystore-backed encrypted preferences.
- No cloud LLM, web request, raw biosignal, transcript, full RAG evidence, fake
  response, placeholder message, or silent fallback in the production path.
- The app remains a decision-support tool and never diagnoses stress or any
  other condition.
- RTK AI, CAVEMAN, and PONYTAIL are authoring guidance only, not runtime dependencies.

## Acceptance criteria

1. Room migrates v19 to v20 and enforces unique source session and occurrence keys.
2. Planner and delivery are idempotent unique WorkManager jobs with explicit
   terminal failure codes.
3. Notification permission denial, disabled channel, missing model, invalid
   session, invalid output, timeout, cancellation, and cooldown never publish text.
4. The notification's public version contains no session label, probability,
   transcript, or generated message.
5. Profile and AI+ expose state-driven reminder status with accessible 48dp
   controls and bounded responsive content.
6. Static/unit/lint/build evidence is reported separately from physical-device
   and real-model acceptance. No device evidence may be fabricated.

## Traceability

| Contract | Implementation evidence |
|---|---|
| PRD AI+ local/offline/safety | `domain/reminder/LlmReminderGenerators.kt`, `ReminderOutputGuard` |
| AGENTS Room/lineage/lifecycle | `data/db/StressDatabase.kt`, `RoomAiReminderRepository.kt`, `DataLifecycleService.kt` |
| AGENTS WorkManager/background | `WorkManagerAiReminderScheduler.kt`, `AiReminderSystemReceiver.kt` |
| DESIGN responsive/a11y/UDF | `AiReminderSettingsSection.kt`, `AiReminderViewModel.kt`, Profile/AI+ integration |

## Non-goals

- User-defined recurring schedules or exact alarms.
- Sending reminders based on invalid/incomplete sessions.
- Clinical intervention, diagnosis, treatment recommendation, or crisis automation.
- Changing raw pipeline data or the root pipeline repository.
