# Traceability Matrix (Master Prompt V2)

Snapshot: 2026-08-09 on `codex/android-devsecops-20260808`.

This matrix records the strongest current evidence. `PASS — software scope`
does not imply physical-device acceptance.

| ID | Criteria | Traced To (PRD / ADRs) | Verification Method | Status |
|----|----------|------------------------|---------------------|--------|
| AC-01 | Android build succeeds with zero errors | PRD §14, AGENTS.md §5 | Unit, lint, and debug APK reports | Partial — release sync blocked |
| AC-02 | Bottom navigation shows 5 destinations with correct icons | PRD §20, ADR-003-unified-navigation.md | Compose/UI inspection | Pending — device/UI |
| AC-03 | All screens are responsive and scrollable | PRD-Clinical-Features-Clean.md | Compose/UI inspection | Pending — device/UI |
| AC-04 | Audio recording works without crash | PRD-Clinical-Features-Clean.md §8.4 | Microphone runtime test | Pending — device |
| AC-05 | Consent dialog persists across configuration changes | PRD-Clinical-Features-Clean.md §4 | Configuration-change test | Pending — runtime |
| AC-06 | AI Counselor returns Gemma 4 responses (not templates) | ADR-001-llm-runtime-quantization.md | On-device model test | Pending — model/device |
| AC-07 | STT produces timestamped, speaker-labeled transcripts | ADR-002-stt-engine-selection.md, ADR-003-diarization-approach.md | Audio integration test | Pending — microphone |
| AC-08 | Live PPG runs concurrently with audio recording | ADR-006-window-step-configuration.md | Paired 60-minute test | Pending — device |
| AC-09 | PDF import + RAG retrieval works with citations | ADR-004-retrieval-licensing-corpus.md, PRD §13.4 | Local RAG/citation test | Partial — citation path tested; PDF/runtime pending |
| AC-10 | Model download persists across app restarts | PRD-Clinical-Features-Clean.md | Restart/persistence test | Pending — runtime |
| AC-11 | No placeholder/dummy implementations — all features work end-to-end | PRD §14.12, AGENTS2.md | Static audit plus physical test | Blocked — physical evidence |
| AC-12 | Physical device benchmark complete (Dimensity 8400 Ultra or reference device) | PRD §3.10 | Benchmark report | Blocked — no device attached |
