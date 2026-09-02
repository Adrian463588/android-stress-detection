# Stress Detection Spec

## User Flow
- User starts recording from phone or watch.
- App records for 5 minutes.
- UI shows progress, sample counts, and quality status.
- At completion, phone displays `Stress` or `Normal` with confidence.
- Final inference uses the latest valid 3-minute feature window after the 5-minute recording, matching the Python model training window.
- If recording is started on the watch, the phone auto-detects the new session ID from incoming batches and resets stale buffers.

## State Model
`Idle -> Starting -> Recording -> Finalizing -> Completed/Error`

## Acceptance Criteria
- A valid completed session stores exactly one final result.
- A completed session records approximately 7,500 Green samples and 30,000 IR samples when sensor delivery is stable.
- Incomplete or poor-quality sessions do not store confident results.
- Debug simulation must be clearly marked and use the same inference path as real data.
- Debug simulation is available only in debug builds.
- UI must state that the app is wellness/research support only and not for diagnosis or treatment.

## Failure Modes
- Permission denied: show permission guidance.
- Samsung SDK policy error: show SDK setup guidance.
- Watch disconnect: mark session incomplete.
