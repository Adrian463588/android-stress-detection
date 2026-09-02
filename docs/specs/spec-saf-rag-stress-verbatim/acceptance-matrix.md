# Acceptance Matrix

| Area | Required evidence |
|---|---|
| SAF grant | persistable read/write grant, wrong-provider/failure state, reconnect/reconcile |
| SAF durability | checksum/size, atomic `.part` publish, restore after local cache loss, external files survive explicit cache cleanup |
| SAF deletion | ordinary rebuild/uninstall path never deletes tree; explicit user delete is isolated and tested |
| PDF | 200MB-class bounded copy, temp-file parsing, page audit, OCR fallback, cancellation cleanup |
| DOCX | streaming `word/document.xml`, paragraph/tab/break mapping, malformed/encrypted/zip-bomb rejection |
| RAG memory | fixed page size, bounded top-K candidates, deterministic lexical/semantic fallback |
| Stress | existing rate/SQA/model/feature/transport/session gates remain green |
| Verbatim | existing timeline, stale-frame, rollback, drain, and divergence linkage tests remain green |
| UI | SAF status/error/action copy remains gradient-free and responsive |
| Build | unit tests, lint, asset checks, app/Wear assemblies |
| Device | preservation-safe install/reinstall, 200MB import, paired Watch session; pending when no device |

## Evidence recorded 2026-08-08

- Unit: `:shared:test`, `:app:testDebugUnitTest`, and `:wear:testDebugUnitTest` passed; focused SAF/RAG suite passed (17 tests).
- Static/build: `:app:lintDebug`, `:wear:lintDebug`, `syncInferenceAssets`, `verifyNoLegacyModelAssets`, `verifyNativeRuntimeContract`, `:app:assembleDebug`, and `:wear:assembleDebug` passed.
- Device: ADB has no attached device; SAF provider revocation, 200MB physical import, uninstall/reselect, and Watch acceptance remain pending.
