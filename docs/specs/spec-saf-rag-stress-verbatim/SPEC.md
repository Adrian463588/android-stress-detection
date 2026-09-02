---
slug: saf-rag-stress-verbatim
date: 2026-08-08
target: D:/SEMESTER12/SkripiDump/ProsesData/android
companions:
  - implementation-plan.md
  - acceptance-matrix.md
sources:
  - ../../../SPEC_IMPLEMENTATION.md
---

# SPEC: SAF, Large-File RAG, Stress, Verbatim

## Why

AI+, Verbatim+, and Stress Detection need durable model ownership, bounded document ingestion, and session-safe inference. Rebuild, uninstall, large imports, and continuous PPG must not silently delete assets, exhaust memory, or leak events across sessions.

## Capabilities

### CAP-SAF-01 — Durable model ownership

Intent: User-selected SAF storage is the durable source for Gemma, Verbatim speech, and Voice AI artifacts; app-private files remain materialized runtime caches.

Success: persistable tree permission, schema marker, checksum/size validation, atomic publish, restore-after-cache-loss, explicit disconnect, and no automatic external deletion are covered by tests.

### CAP-RAG-01 — Large PDF/DOCX ingestion

Intent: Import PDF and DOCX through SAF with bounded streaming, page/paragraph extraction, deterministic chunking, and bounded memory.

Success: 200MB-class inputs are accepted when textual limits permit, DOCX XML is parsed instead of treated as binary text, cancellation cleans temporary files, and malformed/zip-bomb/over-limit input fails closed.

### CAP-RAG-02 — Bounded local retrieval

Intent: RAG scans persisted chunks in bounded pages and retains only bounded candidates while preserving source/page citations.

Success: retrieval never loads an unbounded chunk corpus, semantic/lexical fallback remains deterministic, and source-count/relevance policy stays fail-closed.

### CAP-STRESS-02 — Production-safe stress path

Intent: Stress windows remain session/generation scoped and fail closed on invalid rate, SQA, model, feature, or transport evidence.

Success: existing session-isolation and inference tests remain green after SAF/RAG changes; no RAG import can mutate stress state.

### CAP-VERBATIM-02 — Durable live divergence

Intent: Verbatim audio, PPG, prediction, and divergence artifacts remain linked to one session timeline while model/cache restoration is independent.

Success: existing timeline, rollback, stale-frame, and terminal-drain tests remain green; artifact deletion requires explicit user action.

## Constraints

- Strict MVVM/UDF; no Hilt or Kapt additions.
- Reference projects remain read-only.
- Preserve current dirty tree; stage only this task's files/hunks.
- No backup copies, reset, clean, uninstall, `pm clear`, or dummy physiological/model data.
- SAF URI grants are persistable while installed; after uninstall Android may revoke grants, so UI must explain re-selection while external model files remain untouched.
- Large-file limits must be explicit, bounded, and independently testable; never allocate input-sized byte arrays or load all retrieved chunks.
- Existing ONNX/model fail-closed gates and PPG source/model-rate contract remain unchanged.

## Non-goals

- No cloud RAG, external model hosting, automatic folder deletion, model retraining, new sensor channel, or navigation rewrite.
- No promise that Android retains a SAF grant across uninstall; persistence means external files survive and can be reselected.

## Success signal

Spec, focused SAF/DOCX/large-file/RAG tests, full `:app`/`:wear` tests, lint, asset checks, and debug assemblies pass. Commit contains only allowlisted Android/spec files. Physical device and 200MB hardware acceptance are reported separately.
