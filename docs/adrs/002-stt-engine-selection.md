# ADR-002: Speech-to-Text (STT) Engine Selection

## Context
Verbatim Analysis requires an on-device STT engine capable of processing Indonesian (first priority) and English (second priority). It must run locally without cloud dependence and be capable of flagging low-confidence segments instead of silently omitting or hallucinating them.

## Decision
[Pending benchmarking] We will abstract the STT engine behind `SpeechToTextEngine`. Candidate engines (e.g., Whisper.cpp, Vosk) will be evaluated based on Indonesian language accuracy, offline capability, memory usage, and confidence score availability. The chosen engine must never overwrite the raw transcript data.

## Status
Proposed

## Consequences
- Requires upfront evaluation of STT models specifically for the Indonesian language.
- Pipeline must handle and surface low-confidence flags to the UI for user review.
