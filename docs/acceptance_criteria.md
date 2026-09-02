# Acceptance Criteria

These 12 acceptance criteria must be satisfied for the Android Clinical Companion project release.

## 5. ACCEPTANCE CRITERIA

| ID | Criteria | Verification |
|----|----------|--------------|
| AC-01 | Android build succeeds with zero errors | Gradle build passes |
| AC-02 | Bottom navigation shows 5 destinations with correct icons | Manual UI inspection |
| AC-03 | All screens are responsive and scrollable | Manual UI inspection |
| AC-04 | Audio recording works without crash | Manual test |
| AC-05 | Consent dialog persists across configuration changes | Manual test |
| AC-06 | AI Counselor returns Gemma 4 responses (not templates) | Manual chat test |
| AC-07 | STT produces timestamped, speaker-labeled transcripts (Indonesian + English) | Integration test |
| AC-08 | Live PPG runs concurrently with audio recording | 60-min memory test |
| AC-09 | PDF import + RAG retrieval works with citations | Integration test |
| AC-10 | Model download persists across app restarts | Manual test |
| AC-11 | No placeholder/dummy implementations – all features work end-to-end | Code review + manual testing |
| AC-12 | Physical device benchmark complete (Dimensity 8400 Ultra or reference device) | Benchmark report |
