# AI+ repair and physical acceptance — 2026-10-05 (Asia/Jakarta)

Scope: local counselor, source fallback, turn ownership, prompt budget,
eligible stress history and optional support reminders. No changes to the
stress inference pipeline were made for this task. No Git delivery.

## Deployment

- Verified phone: `QSWSEMRKNFZ9LJRC`, model `2412DPC0AG`.
- Package: `com.stressdetector`; latest observed update `2026-10-05 23:26:59`.
- Source-built APK: `android/app/build/outputs/apk/production/debug/app-production-debug.apk`.
- SHA-256: `1ff711883051fc8d694fed302528dbf668da8150232c0acfaba52788d6583eb3`.
- All installs used `adb install -r -d`. No uninstall, `pm clear`, model
  deletion, database reset, or cancellation of existing reminder work.
- Test APK installed in-place; only the two named safe instrumented classes
  were executed. WorkManager tests that cancel all reminders were not run.

## Evidence matrix

| Gate | Actual evidence | Status / limit |
| --- | --- | --- |
| App unit tests | 538 tests, zero failures/errors | Passed |
| Inference / Wear / shared unit tests | 31 / 30 / 36 tests, zero failures/errors | Passed; unchanged modules up-to-date |
| Static / build | ktlintCheck, detekt, lintProductionDebug, assembleProductionDebug | Passed; existing compiler/deprecation warnings remain |
| Physical Compose/query | WebQueryConsentDialogTest: blank query and large-font edited query; notification channel contract | 3 passed; this is not full TalkBack/320dp/all-screen acceptance |
| Local Gemma | Actual installed model generated distinct replies for fatigue and changed plans | Passed scoped smoke; no benchmark of answer quality |
| Chat context | Follow-up referred to previously stated disappointment, rather than a missing document | Passed scoped smoke; model phrasing still generic |
| RAG unavailable | Explicit PDF question yielded unavailable and recovery controls; no invented grounded answer | Passed fail-closed path |
| Web confirmation | Visible editable query with public/private warning; Cancel returned to app | Passed dialog/cancel path; no network request approved |
| Grounded document answer | No real validated benchmark packaged; bundled manifest EMPTY | Blocked; no fabricated threshold/corpus |
| Real web answer/citation | User confirmation of public test query requested | Pending approval |
| Stress-history advice | Eligibility regressions tested; no private history forcibly enabled | Unit verified; physical grounded advice pending |
| Reminder | Existing scheduled reminder preserved; channel semantics verified | Real due-time generation/delivery pending |
| Native generation cancellation / chat switching | Unit cancellation/ownership regressions passed | Physical cancellation attempts raced completion; not claimed passed |
| Crash buffer | Latest observed native crash timestamp: 2026-09-27 11:26:35 | Historical; no new crash observed in this smoke window |
| Independent BMAD review | Three reviewers dispatched without conversation context | Failed service/network; no findings, not a passed review |

## Captured evidence

### Approved live web attempt, 23:41 Jakarta

The user approved the public query `cara mengelola stres sehari-hari`.
The confirmation field was edited and verified to contain exactly that query
before `Izinkan dan cari` was tapped. The app entered `Mencari sumber web…`
then displayed `Sumber web tidak tersedia; tidak ada evidence yang dapat
diverifikasi.` No web-grounded answer or citation was generated. This verifies
the consent/recovery path, **not successful web grounding**. The precise
discovery/scraping failure is not yet isolated; technical details still show
the previous local retrieval reason, so diagnostics need further investigation.
`web-unavailable.png` records this actual state. Generic support questions
stayed in local `GENERAL_SAFE`, which does not itself prove web fallback.
The installed base APK checksum was verified identical to the latest build.

- `before.png`: initial AI+ empty state with actual existing reminder.
- `real-response.png`: first local Gemma response before final follow-up fixes.
- `current-response.png`: latest distinct contextual local response.
- `followup.png`: latest contextual follow-up referencing disappointment.
- `web-confirmation.png`: actual confirmation before any web network request.
- `cancel.png`: response completed before the attempted tap; **not evidence of
  successful generation cancellation**.

Only clearly labelled local test prompts were sent. Existing chats and model
files were retained. Screenshots are unmodified device captures.

## Implementation and references

Reuse existing MVVM/UDF modules, Material components and security boundaries;
no runtime authoring-tool dependencies added. RTK, CAVEMAN and PONYTAIL guided
authoring; focused formatter allowlist avoided unrelated dirty worktree files.

- [LiteRT-LM Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md): background initialization, conversation lifecycle and coroutine response flow. Installed runtime retained.
- [Android notification channels](https://developer.android.com/develop/ui/compose/notifications/channels): user-controlled blocked channels must not report successful delivery.
- [OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html): public-address checks and DNS/redirect boundaries.

These sources guide engineering controls, not clinical validation or proof that
regex/prompt guards alone eliminate privacy leaks or prompt injection.
