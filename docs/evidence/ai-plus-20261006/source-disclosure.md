# AI+ grouped source disclosure — 6 October 2026

Scope: one disclosure per answer, grouping existing citations. No retrieval,
generation, safety, consent, memory, inference or storage contract was changed.
No new runtime dependency, citation or answer was introduced. Existing dirty
changes remain untouched; no staging, commit or push.

## Acceptance evidence

| Gate | Actual observation | Limit |
| --- | --- | --- |
| Collapsed state | One `Lihat sumber`; individual source titles and details are hidden | One control per answer, not one global control for every chat |
| Expanded state | One `Sembunyikan sumber`; persisted `[W1]` and `[W2]`, titles, URLs, evidence excerpts and retrieval metadata appear in one list | Existing preview/URL line limits remain unchanged |
| URL action | `[W2]` opens Chrome; VIEW intent contains exactly `https://www.alodokter.com/manajemen-stres-cara-jitu-agar-hidup-lebih-tenang`; Back returns to the expanded list, then it collapses successfully | Browser dispatch verified, not medical-content quality |
| Compose device tests | `AiCounselorCitationTest`: `OK (6 tests)`, 9.269 seconds on final APK | Actual bubble at 320dp/font scale 2, local/web numbering, empty/changed sources, recreation, button role/state and 48dp targets; not full TalkBack, landscape or tablet acceptance |
| Unit gates | App 602, inference 31, Watch 30, shared 36; 699 total, zero failures/errors | Inference/Watch/shared unchanged tasks may be up-to-date; not 699 physical scenarios |
| Quality/build | Full ktlintCheck, detekt, app lintProductionDebug and both APK assemblies passed; final Gradle invocation exited 0 | Lint reports 0 errors, 71 warnings and 2 hints outside the changed source-disclosure files; no baseline weakened |
| Data retention | In-place installs only; original install date and existing conversation/citations preserved; Gemma file still 2,588,147,712 bytes, dated 25 September | No checksum audit/export of private chat or model data |
| Crash buffer | Only historical target-package native crash dated 27 September observed; no new target-package crash during this smoke | Buffer retained; no all-path crash-free claim |
| Independent review | Two findings addressed: unsafely restored expansion removed; narrow-font harness now uses the actual answer bubble | Static review is not a replacement for physical tests |

The disclosure uses temporary `remember(citations)` state: changed sources or a
recreated screen start collapsed. It does not restore an expanded state against
changed references. Source count descriptions use plural resources; the new
pluralization lint warning from the intermediate build was repaired rather
than suppressed.

## Final deployment

- Verified phone: `QSWSEMRKNFZ9LJRC`, model `2412DPC0AG`, Android 16.
- Production-debug package: `com.stressdetector`, version `0.1.0`.
- Last update: `2026-10-06 08:04:09` Jakarta.
- Original install remains: `2026-09-25 01:07:46`.
- Local APK and installed `base.apk` independently match SHA-256:
  `59fd4954c665bcd83a2058b13f6f6b739805548b82a8e9396e4bd0f2f62f5306`.
- No uninstall, `pm clear`, model deletion, database reset or settings change.
- No new question/search was sent; UI acceptance used the existing real web-grounded answer.

## Commands

From the Android directory, the final Gradle invocation ran:

```powershell
rtk proxy .\gradlew.bat :app:testProductionDebugUnitTest `
  :inference:testProductionDebugUnitTest :wear:testProductionDebugUnitTest `
  :shared:test ktlintCheck detekt :app:lintProductionDebug `
  :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest `
  "-PinternalModelIndex=D:/SEMESTER12/SkripiDump/ProsesData/data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json" `
  "-PdetectorContract=D:/SEMESTER12/SkripiDump/ProsesData/data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json"
```

Both APKs were installed using `adb -s QSWSEMRKNFZ9LJRC install -r -d`, then:

```powershell
rtk proxy adb -s QSWSEMRKNFZ9LJRC shell am instrument -w `
  -e class com.stressdetector.mobile.ui.screens.AiCounselorCitationTest `
  com.stressdetector.test/androidx.test.runner.AndroidJUnitRunner
```

## Actual previews

Collapsed on the final APK:

![One source disclosure](sources-group-collapsed-final.png)

Expanded on the final APK:

![Both references in one list](sources-group-expanded-final.png)

References: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
for button/state descriptions, and [Compose state](https://developer.android.com/develop/ui/compose/state)
for temporary local presentation state. BMAD Quick Dev, Compose expert,
CAVEMAN and PONYTAIL guidance shaped the scoped implementation and review;
RTK remained authoring tooling only.
