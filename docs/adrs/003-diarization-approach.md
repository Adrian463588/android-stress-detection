# ADR-003: Speaker Diarization Approach

## Context
We need to attribute speakers in the transcribed verbatim sessions. The MVP requires supporting two speakers (e.g., counselor and client). This must run offline on-device. The user must be able to relabel speakers without re-running the full audio pipeline.

## Decision
We will implement speaker diarization as a distinct pipeline step decoupled from STT, utilizing an on-device clustering or embedding approach that supports two speakers. The output will be integrated such that labels can be updated independently of the audio processing pipeline.

## Status
Proposed

## Consequences
- Requires an intermediate data structure to map timestamps to speaker IDs.
- Relabeling must be a fast, offline CRUD operation on the local database without audio reprocessing.
