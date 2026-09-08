# AI+ Reminder State Machines

## User-facing status

```text
Disabled
  └─ enable → NeedsConsent | NeedsNotificationPermission | NoEligibleSession
NeedsConsent ──consent/history enabled──> NoEligibleSession | Scheduled
NeedsNotificationPermission ──permission granted──> Scheduled
NoEligibleSession ──new valid session──> Scheduled
Scheduled ──delivery worker──> Generating
Generating ──valid local output + publish──> Delivered
Generating ──invalid/unavailable/permission──> Failed | ModelUnavailable
Delivered ──pause/revoke──> Disabled
Failed/ModelUnavailable ──explicit retry──> NoEligibleSession | Scheduled
```

## Persisted worker states

```text
SCHEDULED → GENERATING → GENERATED → DELIVERED
     └──────────────→ FAILED
```

`GENERATED` is not a user-visible success signal; it only records that an
encrypted payload was written before notification publication. A crash or
reboot reconciles it through the same ID-only delivery work. `FAILED` always
has an explicit error code and never contains generated plaintext in Room.

## Invariants

1. `sourceSessionId` and `occurrenceKey` are unique.
2. A source session can create at most one occurrence.
3. A notification is published only after permission, consent, fresh eligible
   context, output guard, and encrypted payload write all succeed.
4. Lock-screen public text is generic; full reminder text is private.
5. No state transition creates a message when the model is unavailable.
