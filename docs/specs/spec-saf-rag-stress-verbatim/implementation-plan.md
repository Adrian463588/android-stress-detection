# Implementation Plan

## Baseline

- Existing target has SAF model storage, but audit must verify permission lifecycle, restore, deletion boundaries, and model-manager wiring.
- Existing RAG path copies SAF input to `filesDir`, caps files at 25MB, parses non-PDF input as plain text, loads PDF pages/chunks into memory, and retrieves all chunks at once.
- Current worktree contains unrelated dirty Android/data changes. Do not reset, clean, stash, or stage them.
- Prior Stress/Verbatim commit is the compatibility baseline; preserve current PPG rate, identity, generation, timeline, and fail-closed model gates.

## Read-only hypotheses

1. `ModelStorageManager` needs explicit non-destructive external-storage semantics and tests for URI grant failure, restore, checksum, and disconnect.
2. `DocumentParser` needs DOCX OOXML extraction and PDF temp-file loading; current 25MB gate contradicts 200MB-class import.
3. `DocumentIngestionService` needs bounded text/chunk/embedding work and cancellation-safe cleanup; database writes must not retain all input bytes.
4. `LocalRagRetrieverImpl` needs paged chunk access and bounded top-candidate retention.
5. Model deletion and rebuild/uninstall paths must never call SAF `deleteSlot` implicitly; only explicit user deletion may remove external artifacts.
6. Stress/Verbatim code needs regression-only verification after RAG/SAF edits, not broad redesign.

## Allowlist

### Spec and tests

- `android/docs/specs/spec-saf-rag-stress-verbatim/**`
- Focused tests under `android/app/src/test/java/com/stressdetector/mobile/ml/storage/**` and `.../ml/rag/**`.

### SAF/model

- `ml/storage/ModelStorageManager.kt`, `ml/storage/ArtifactStorage.kt`
- `ml/llm/ModelManager.kt`, `ml/llm/GemmaModelStorage.kt`
- `ml/speech/SpeechModelBundleManager.kt`
- `ui/viewmodels/ProfileViewModel.kt`, `ui/screens/ProfileScreen.kt` only when needed for durable semantics.

### RAG

- `ml/rag/DocumentParser.kt`, `DocumentIngestionService.kt`, `PdfIngestionWorker.kt`, `TextChunker.kt`, `LocalRagRetrieverImpl.kt`
- `data/db/ClinicalDaos.kt` only for bounded chunk queries.

### Regression

- Stress/Verbatim production files only if a failing regression identifies a direct defect. Add focused tests; do not alter model artifacts.

## Design

```text
SAF tree -> persisted URI grant -> bounded copy/stream -> verified app-private runtime cache
                                      |-> checksum + atomic activation
                                      |-> external artifact untouched by uninstall/rebuild

SAF PDF/DOCX -> bounded temp file -> page/OOXML stream -> bounded text/chunks -> paged DB writes
                                                                    |-> bounded retrieval top-K

Watch PPG/audio -> existing session/generation timeline -> stress/divergence reducers
```

- Raise binary import ceiling to an explicit 256MB minimum (configurable constant), while keeping textual/chunk limits bounded.
- Parse DOCX `word/document.xml` with streaming XML; reject encrypted/malformed ZIP and oversized decompressed text.
- Use PDFBox temp-file memory mode where supported; keep page extraction and OCR page-scoped.
- Replace all-chunks retrieval with fixed-size DAO pages and bounded candidate heap.
- Preserve atomic `.part` then publish behavior; do not delete SAF artifacts during cache cleanup or ordinary lifecycle operations.

## Verification commands

```powershell
cd D:\SEMESTER12\SkripiDump\ProsesData\android
rtk proxy powershell -NoProfile -Command "& .\gradlew.bat --gradle-user-home D:\SEMESTER12\SkripiDump\ProsesData\.gradle-codex :app:testDebugUnitTest :wear:testDebugUnitTest --no-daemon"
rtk proxy powershell -NoProfile -Command "& .\gradlew.bat --gradle-user-home D:\SEMESTER12\SkripiDump\ProsesData\.gradle-codex :app:lintDebug :wear:lintDebug :app:assembleDebug :wear:assembleDebug --no-daemon"
```

Physical SAF persistence, uninstall/reinstall, 200MB PDF/DOCX, and paired Watch gates require a device and are not claimed by local unit/build tests.

## Commit rule

Stage exact allowlist only. Commit semantic message on `codex/android-devsecops-20260808`; leave unrelated dirty files unchanged.
