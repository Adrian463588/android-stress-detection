# Report Screen Spec

## Goal
Show a simple summary of stress detection history.

## Required Views
- Today summary: total, stress count, normal count, stress rate.
- Weekly trend: per-day stress frequency.
- History list: timestamp, label, confidence, quality.

## Acceptance Criteria
- Report updates after a new completed session.
- Empty history has a clear empty state.
- Counts and stress rate are deterministic and covered by unit tests.
