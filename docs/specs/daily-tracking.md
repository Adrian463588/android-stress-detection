# Daily Tracking Spec

## Goal
Persist each completed stress detection session on the phone.

## Stored Fields
- session ID
- start and end timestamp
- duration
- primary sensor
- final label
- confidence
- quality tier
- stress probability
- Green and IR sample counts
- error reason, if any

## Acceptance Criteria
- Room is the source of truth.
- Latest sessions are queryable for history.
- Daily aggregates are computed from stored completed sessions.
