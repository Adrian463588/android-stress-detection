# SAF recovery and Whisper provisioning — 2026-10-07

## Scope and source

CAP-12 adds discoverable Profile download/import for the catalog-pinned multilingual
Whisper bundle and its completed SAF generation. CAP-13 adds password-authenticated
recovery of Home History, AI+ conversations and finalized Verbatim+ records/media.
See the [normative contract](../../../../_bmad-output/specs/spec-verbatim-review-cues/saf-recovery-contract.md).

The target branch is `android-devsecops-20260808`, based on
`5fa82694f48a3c06099f0e8e447cfa13c732e1af`; no change targets `main`.
The installed APK was built from the complete, already-dirty working tree.
Unrelated owner changes are not staged. A separate snapshot of the scoped index
also passed compilation, unit tests, ktlint and detekt. Its pre-evidence tree was
`b312868b52f9e3772330555a359cb8b87071a04c`; the 42 staged runtime/build files
matched that tested snapshot after Git line-ending normalization.

## Completed verification

| Gate | Observed result | Claim boundary |
| --- | --- | --- |
| Full working-tree unit tests | 725 tests; zero failure/error/skip | Software checks, not interview accuracy |
| Scoped-index unit tests | 606 tests; zero failure/error/skip | Independently compilable scoped source; fewer unrelated owner tests |
| ktlint / detekt | Passed in both source contexts | No claim that the whole dirty tree is committed |
| Production lint | Zero errors; 73 warnings and 2 hints | Not a zero-warning release |
| APK and instrumentation compilation | Production debug app/test APKs built; scoped Android tests compiled | Physical acceptance is recorded separately below |
| Phone replacement install | `install --user 0 -r`; same signing certificate | No uninstall, downgrade or `pm clear` |
| Physical selected tests | `OK (13 tests)`, 22.325 seconds | Disposable fixtures; no real interview or Watch session |
| User file preservation | Production and legacy fingerprints unchanged immediately across replacement install | Later launch/download writes are expected; not a power-loss guarantee |

Local build commands ran from `android/` with:

```text
-PinternalModelIndex=../data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json
-PdetectorContract=../data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json
--console=plain --max-workers=1
```

Full-tree tasks:

```text
:app:ktlintCheck :app:detekt :app:lintProductionDebug
:app:testProductionDebugUnitTest :app:compileProductionDebugAndroidTestKotlin
:app:assembleProductionDebug :app:assembleProductionDebugAndroidTest
```

The scoped snapshot ran the same ktlint/detekt/unit/Android-test compilation tasks.
Only one Gradle build ran at a time. Model provenance remained the canonical run
`20260916T132736Z`; thresholds, cohort and clinical release status were unchanged.

## Phone and artifact receipts

Device: `RRCN3008VYE`, Samsung `SM-G988B`, Android 13, arm64. Production package:
`com.stressdetector`. The legacy package and its data were left untouched.

| Receipt | SHA-256 |
| --- | --- |
| Installed production APK | `9ce3d6d3e96fc7e71d7333d23ecd8e08889463a10ef1b4b316256c15c65d5954` |
| Installed test APK | `885695e5886d5224d19de8e339b08bc185f14211f4263f34dd2eadd8172764de` |
| Signing certificate | `fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4` |
| Production file fingerprint, 92 files | `307c2b757662ec469a7ba5f48d77557c451fb4a79da409ef9b2b1f9d9de65508` |
| Legacy file fingerprint, 42 files | `3f68067f753a3b2daca6161b0bec2212e9ee02250159706463789a747dd04adf` |

Preservation fingerprints use sorted file paths and SHA-256 values under private
`files`, `databases`, `shared_prefs` and `no_backup`, excluding cache. Comparison
was after install and before launch. No patient text, audio or database dump is
published here.

Seven archive instrumentation cases exercise real Room/SQLite, Android Keystore
and files in an isolated test context: cross-key round-trip of the three domains,
wrong-secret rejection, interrupted ownership cleanup, committed receipt recovery,
create-only collision, unrelated-row rollback and abandoned chart-stage cleanup.
Five Compose cases cover disconnected/running states, reachable actions at 320dp
and 200% font scale, password confirmation and restore cancellation.
One existing, unstaged migration test verifies Room 20→21 on a disposable database.
Thus twelve tests belong to this scoped patch; the thirteenth is existing owner
test coverage, not an additional staged file.

An earlier device run exposed Android's hard-link prohibition. Publication now
uses a separate native `renameat2(RENAME_NOREPLACE)` boundary and all thirteen
selected cases pass. Failure to support create-only publication remains an error,
not an overwrite fallback. A UUID transaction receipt replaces inference from
unrelated rows; reserved chart staging is recovered without deleting real charts.

No recording was started. Tests did not delete real sessions or run a fresh-install
cycle on the user's installation. Manual Profile smoke also opened the real
backup and restore dialogs: empty secrets disabled submission, the backup dialog
required confirmation, and Cancel returned safely. No password was entered and
neither operation was submitted on live user data. The selected runner was:

```text
adb -s RRCN3008VYE shell am instrument -w -r -e class com.stressdetector.mobile.lifecycle.PortableUserDataArchiveInstrumentedTest,com.stressdetector.mobile.ui.screens.ProfileBackupSectionTest,com.stressdetector.mobile.data.db.StressDatabaseMigration20To21Test com.stressdetector.test/androidx.test.runner.AndroidJUnitRunner
```

## Actual Profile provisioning and preview

The existing user-selected local SAF tree is `primary:StressDetector`.
The official Profile action started an unmetered Wi-Fi download of fourteen
catalog components, 777,276,595 bytes, followed by extraction and verification.
Activation and SAF completion are not inferred merely from download completion.
At 11:25 WIB the operation reached `ready` after native preflight. The active
manifest declares schema `2.0.0`, Sherpa runtime `1.13.4`, 16 kHz, languages
`id`/`en` and Whisper task `transcribe`. Its bundle version is
`sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-whisper-tiny-v4`, with Whisper
revision `65176e2deb88badc814a94058666cadccc29b61c`.

The SAF generation
`Artifacts/speech-bundle/sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-whisper-tiny-v4-faa5eba53fdd`
has a schema-3 `complete-manifest.json` with `complete=true`. Independently
computed device hashes of the local and SAF ZIP/sidecar match each other and
that completion manifest. The old completed SAF speech generation remains.

| Verified file | Bytes | SHA-256 |
| --- | ---: | --- |
| Local and SAF `sherpa_bundle.zip` | 702,505,884 | `d554aa1377fcbf8605d36f51acddfc81a0d43249ba319e2364b3cdadce2e432f` |
| Local and SAF `manifest.json` | 8,285 | `9f2e49f85ad2c01515b31ee434de00374613963dd60ad0b8329ecf51345bd7fc` |
| Materialized Whisper encoder INT8 | 12,937,772 | `d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434` |
| Materialized Whisper decoder INT8 | 89,855,401 | `d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925` |
| Materialized Whisper tokens | 816,730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

The three materialized Whisper hashes equal the catalog-pinned download hashes.
The Profile readiness screenshot was captured at 11:26 WIB. No interview or
microphone input was used to obtain this provisioning result. The crash buffer
for this app process was empty during the check; that is a bounded smoke result,
not a claim that all Android devices or audio cases are crash-free.

These PNGs were captured directly from this phone's current APK, without editing
or synthetic content. They show controls and readiness, not interview data:

![Real Profile Whisper download options](profile-whisper-download.png)

![Real Profile private-data backup controls](profile-backup.png)

![Real Profile after verified Whisper activation](profile-whisper-ready.png)

| PNG | SHA-256 |
| --- | --- |
| `profile-whisper-download.png` | `0a4234eb121e9cf5ea48de7256294b54aea96e23f3b76f9f6041ff45b49811e9` |
| `profile-backup.png` | `b2473583841773b50675842dffda7e1085a928f4099aa1a5b1435037cac2c773` |
| `profile-whisper-ready.png` | `2f937d92d5a05848a96714020e039afe5a35f690e12a81041d6362f442ce2c15` |

## User action and remaining gates

- Create the first backup with a private recovery password, wait for successful
  completion and no pending changes, and keep that password outside the app.
  A real user-data backup was not enabled on the user's behalf.
- After uninstall, reselect the same SAF folder and restore before creating new
  records. Recovery currently requires the same Room schema and empty scoped
  tables; it is not a merge or a future-schema migration.
- Test a controlled uninstall/reinstall on a disposable profile/device. The
  cross-key fixture proves the archive does not depend on the old installation's
  Keystore, but does not replace that operating-system lifecycle test.
- Reference Indonesian/English audio, speaker ground truth, airplane-mode
  interview execution and actual Watch PPG remain separate acceptance gates.
- Knowledge Base documents/indexes, reminder settings, consent and Android grants
  are not in the conversation archive. Persona/models use separate SAF slots.
- Backup changes coalesce for 15 seconds while the process runs. Full generations
  are retained and consume extra space; force-stop or uninstall before completion
  cannot be described as protected. Other kernels/filesystems and sudden power
  loss are not certified by the Samsung test.
