# AI+ automatic public fallback and chat memory — 2026-10-06

Scope: the existing local counselor journey, automatic public-topic search after
explicit opt-in, bounded chat memory and restrained recovery/typography.
No stress acquisition, ONNX, Watch or reminder opt-in was changed by this continuation.
Reminder publication/cancellation checks were strengthened within the existing contract.
No Git delivery; unrelated dirty worktree changes remain untouched.

## Deployment and retention

- Phone independently verified: `QSWSEMRKNFZ9LJRC`, POCO X7 Pro, model `2412DPC0AG`, Android 16.
- Package `com.stressdetector`, production-debug `0.1.0`.
- Latest measured update: `2026-10-06 07:23:55` Jakarta; original install remains `2026-09-25 01:07:46`.
- APK: `android/app/build/outputs/apk/production/debug/app-production-debug.apk`.
- SHA-256: `c9d8a782ee4c5e1642a978983d62d44ec43af0bfaa92942e92e8f55f462f9a5a`.
- Installed `base.apk` independently reports the identical SHA-256.
- Prompt asset `counselor_v1` version `1.2.1` is present in production merged assets.
- Every installation used `adb install -r -d`. No uninstall, `pm clear`, model deletion,
  database reset, or cancellation of existing reminder work.
- Existing conversation messages remained visible after install. The installed
  `gemma-4-E2B-it.litertlm` remains 2,588,147,712 bytes, dated 25 September.
  This is scoped retention evidence, not a checksum audit of every private artifact.
- Test APK also installed in-place. Three named safe test classes ran: the two
  dialog classes and `AssistantTurnRoomInstrumentedTest`, which uses a separate
  in-memory database, not the app's private database.

## Evidence matrix

| Gate | Observed evidence | Limit |
| --- | --- | --- |
| Unit tests | App 602, inference 31, Wear 30, shared 36: 699, zero failures/errors/skipped | Project-wide tests, not 699 physical AI+ scenarios; test doubles remain confined to tests |
| Static/build | Full ktlintCheck, detekt, app lintProductionDebug, production APK and test APK assembly passed after the whitespace/list patch | Lint: 0 errors, 72 warnings, 2 hints; no baseline/suppression was weakened |
| Physical Compose/Room | WebQueryConsentDialogTest (2), WebSearchModeDialogTest (1), AssistantTurnRoomInstrumentedTest (1), `OK (4 tests)`, 5.019 seconds on final APK | Query editing/blank input, fontScale 2 opt-in and real SQL rollback; not all screens/TalkBack/320dp |
| Automatic permission | Explicit `Izinkan web otomatis` chosen in app; legacy consent did not enable it. UI `Nonaktif` persisted both booleans false; restored explicit opt-in persisted both true | No raw personal query is used in automatic mode |
| Live web grounding | Public test topic `cara mengelola stres sehari-hari`; no per-turn dialog after opt-in; final APK produced two exact-bound citations, separate paragraphs/list items and one follow-up after TOC/title-only fixes | Scoped network test, not a reliability SLA or universal claim-to-passage entailment |
| Source disclosure | `[W1] Web` and actual page title; `Lihat sumber` exposed URL and an actual paragraph rather than TOC; URL action opened Chrome at the exact public page, then returned to AI+ | Browser launch/URL verified, not browser-page or medical-content quality assurance |
| Local advice | Actual Gemma produced journaling advice and a relevant probing question | General support, not diagnosis, prescribed therapy, or clinical efficacy |
| Memory/typography follow-up | Final APK recalled “revisi skripsi”, returned one exercise and one question in separate readable paragraphs with bold labels | Scoped active-chat recall; no unlimited/per-user memory or perfect instruction-following claim |
| Unavailable documents | No READY encrypted local index and no validated benchmark; bundled manifest EMPTY | Real calibrated document answer remains blocked; no invented corpus or threshold |
| Cancel/switch/revoke in-flight | Ownership/privacy/consent regression tests pass; native generation cancelled on 07:14 APK, composer recovered, no late assistant appeared after another minute | Not all physical interleavings or runtime races tested |
| History/reminders | Earlier eligibility/consent/channel regressions retained | No private history forcibly enabled; real personalized advice and due-time delivery remain pending |
| Watch | Not present in current `adb devices -l` | No new physical Watch/PPG acceptance claimed |
| Crash buffer | Only historical package native crash from 27 September remains; no new target-package crash observed during this smoke | Crash buffer was not cleared; scoped observation, not proof of all paths |
| Independent review | Three fresh blind/edge/acceptance reviews completed; findings triaged and regression-tested | Runtime calibration/corpus remains absent; static review is not full E2E acceptance |

## Reproduced failures and fixes

1. Google discovery returned HTTP 200 containing navigation/JavaScript links,
   not search results. Discovery now reads official non-JavaScript HTML results
   (`a.result__a`) only, deduplicates and admits at most three public HTTPS pages.
   Empty/challenge pages fail explicitly; there is no CAPTCHA bypass or proxy.
2. First real web attempt reached Gemma but failed citation validation. It did
   not become a successful grounded bubble. The prompt now requires the exact
   supplied source label/ID per factual claim. The second actual attempt succeeded:
   [10 Cara Mengelola Stres Secara Efektif dan Mudah](https://heartology.id/health-library/content/cara-mengelola-stres-secara-efektif-dan-mudah/).
   This retrieved page is runtime evidence, not an approved bundled corpus or a
   claim that every recommendation is medically verified.
3. A numbered first item attached to a colon was displayed as paragraph text.
   Presentation parsing now recognizes that boundary without modifying persisted
   model output or citations; decimal regression coverage remains intact.
4. A longer user template could crowd out an explicit personal statement,
   producing a generic question instead of recall. Explicit attributed statements
   now precede optional excerpts within the same budget. The final physical reply
   remembered the real test statement and offered one relevant exercise.
5. The first post-review APK (`b9ec1029…`) produced an exact-bound citation but
   only repeated source metadata. Actual Heartology HTML included
   `.gt-table-of-contents`. That navigation is removed, paragraph boundaries
   retained, and source-metadata-only replies rejected. One genuine bounded
   regeneration is allowed for citation-related rejection; citations are never
   inserted by the app. The 07:14 reply used two sources, but its exact-binding
   success does not establish that every recommendation is entailed by its quote.
6. Native runtime discarded whitespace-only response deltas, merging paragraphs
   and numbered advice. The final adapter preserves nonempty deltas and is
   tested with the real response assembler. Display-only parsing also separates
   numbered items after exact citations; decimals and saved model text are preserved.
   Final physical journaling output contains separately readable paragraphs.

Post-review ownership fixes include ordered revocation, whole-turn handoff,
pre/post Room transaction checks, cancellation throughout HTTP body/robots,
and reminder eligibility/publication serialization. A structurally valid
benchmark cannot enable local retrieval without matching actual active corpus,
embedding, tokenizer, chunk-policy and implementation identity. No identity,
threshold, calibration or corpus receipt was fabricated.

Memory is rebuilt from at most 64 persisted messages in the active chat. It
selects up to six recent turns, three relevant literal older user excerpts and
six explicit user goals/preferences/self-reports. Quotes retain source identity;
they are not generated summaries or diagnoses. Prompt admission has its own
bound. Switching/deleting a chat cannot revive a separate semantic-memory cache.
The engine cache avoids reloading the model; each turn uses a fresh conversation,
so LiteRT-LM KV state is not reused across unrelated chats.

Automatic web queries are a deterministic allowlist of generic public topics,
never raw chat/history/persona/document text. Consent is separate and revocable.
Manual unsupported topics still require a visible editable public query. The
public DNS/HTTPS/redirect/robots/size limits and bounded search timeout remain.
Local crisis interception and output safety/citation guards remain authoritative.
Regex and prompting are not a guarantee against all privacy or injection risks.

## Unmodified device captures

- `before.png`: initial oversized error/recovery UI.
- `web-setting.png`: explicit automatic public-search disclosure.
- `web-progress.png` and `web-citation-rejected.png`: first attempt's rejection;
  despite the first filename, it is **not successful progress/grounding**.
- `retrieval-progress.png`: actual second-attempt generation pending; the older
  “terverifikasi” status wording was subsequently corrected to “berhasil dibaca”.
- `web-grounded.png`, `source-expanded.png`: actual validated web reply and source.
- `journaling-before-layout-fix.png`, `memory-followup-before-layout-fix.png`:
  earlier advice with the numbered-text layout defect.
- `followup-budget-regression.png`: reproduced generic response before memory admission fix.
- `web-revoked.png`: real disabled selection before restoring the prior explicit choice.
- `memory-followup-final.png`: recall and short exercise on an earlier
  continuation build, before the paragraph-preserving patch.
- `web-title-only-before-guard.png`: actual rejected design/runtime defect on
  the first post-review build, not useful-answer acceptance.
- `ime-resize-0714.png`: header remains visible while keyboard reduces the app
  window; no keyboard-driven panning of the header.
- `web-grounded-0714.png`, `source-expanded-0714.png`: practical reply and actual
  source paragraph on the 07:14 build, before the last typography patch.
- `generation-cancelled-0714.png`, `cancel-no-late-answer-0714.png`: cancelled
  native generation followed by a later observation without an assistant reply.
- `memory-journaling-final.png`: final installed build, one exercise/one probing
  question with original newlines preserved; no screenshot editing.
- `web-grounded-final.png`, `web-grounded-paragraphs-final.png`,
  `source-expanded-final.png`: final APK's actual public response, readable
  numbered list and source paragraph. Source-link action opened the exact URL
  in Chrome; Back returned to the app.

## Engineering references

- [LiteRT-LM Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md): conversation lifecycle, response flow and runtime budget.
- [Android data layer](https://developer.android.com/topic/architecture/data-layer): data boundaries and UDF; UI retains state/callback separation.
- [LangChain memory concepts](https://docs.langchain.com/oss/python/concepts/memory): conceptual short-term/episodic/semantic separation only; no LangChain dependency added.
- [Coroutine cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html): cancellation lifetime at the header/body boundary.
- [DuckDuckGo non-JavaScript search](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript): supported HTML search endpoint, not challenge circumvention.
- [OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html): public DNS/address and redirect checks.
- [WHO stress Q&A](https://www.who.int/news-room/questions-and-answers/item/stress): general support framing, not validation of this chatbot or a source bundled without licensing review.

RTK, CAVEMAN and PONYTAIL guided authoring/minimal changes only; no application
runtime dependencies were added. Clinical accuracy and full end-to-end acceptance
cannot be inferred from unit tests, screenshots or this scoped smoke test.
