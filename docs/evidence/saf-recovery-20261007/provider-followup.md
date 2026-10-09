# SAF provider and recovery-state follow-up — 2026-10-07

## Scope

CAP-13 hardening continues the [earlier delivery](acceptance.md) on
`android-devsecops-20260808`, starting from
`1f6b26b10a543a21fb133e813af734d4a6172334`. Main remains out of scope.
The [recovery contract](../../../../_bmad-output/specs/spec-verbatim-review-cues/saf-recovery-contract.md)
contains the plan and acceptance boundaries.

The patch resets backup controls before rethrowing cancellation and distinguishes
an optional local automation-password cache error from a completed archive
publication/import. An old cached password is removed before the commit boundary;
failed replacement caching cannot silently resume with that old password. A
manual operation that fails after removing its cache leaves automatic backup
disabled; the previous complete SAF archive is retained. No new dependency,
model threshold, Room schema or Composable business logic was introduced.

## Local verification

| Gate | Actual result |
| --- | --- |
| Full working-tree unit tests | 730 tests in 173 suites; zero failures/errors/skips |
| ktlint and detekt | Passed; no new suppression of function-count/style limits |
| Production Android lint | Zero errors; 73 warnings and 2 hints remain |
| APK/native packaging and Android-test compilation | Passed; full build `BUILD SUCCESSFUL in 7m 30s` |
| Scoped-index unit tests | 611 tests in 160 suites; zero failures/errors/skips |
| Scoped-index ktlint, detekt, Android-test compilation | Passed; `BUILD SUCCESSFUL in 3m 45s` |
| Delegated read-only review | No concrete regression found; test limits retained below |

The installed app/test APKs come from the complete already-dirty working tree.
Unrelated owner edits are not included in the commit. The separate scoped-source
snapshot was created from index tree
`36efd6e805a16d0de44aa41996b0dbff8eef339a`; all five changed Kotlin files match
that tested index after Git line-ending normalization. Documentation added after
this snapshot does not change runtime/build source. The snapshot is retained
under `_verification/saf-followup-20261007/scoped-source`; its `data` junction
points at real project data and must not be recursively deleted.

Build properties remained the canonical local model run:

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

The scoped snapshot ran ktlint, detekt, unit tests and Android-test compilation.
Only one Gradle build ran at a time. Initial format/function-count failures were
corrected before the passing final gates; no failed run is release evidence.

## Physical non-recording acceptance

Device: `RRCN3008VYE`, Samsung `SM-G988B`, Android 13, arm64, user 0.
Both APKs were replaced with `adb install --user 0 -r`; there was no uninstall,
`pm clear`, downgrade, microphone capture or user-data deletion. The signing
certificate matched the installed app before replacement.

| Artifact/receipt | SHA-256 |
| --- | --- |
| New production APK, also verified on device | `532a462c24e9d0c3eb89a26e85933dcfc1776256350f04e748c0cbabc09d67a6` |
| New test APK | `0022f5e4bbc48a60dbaa87d2915c8044135269af1724fdb4791a6d18c79f93fe` |
| Signing certificate | `fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4` |
| Production, 95 private files immediately before/after install | `03339d87b345038c6b098262641755cf2cd1e9d9ab42b893d966696dcc6d983f` |
| Legacy, 43 private files before/after install and after tests | `38bd640b70092789babc2500183d62798ccc2381de8b1069a50a98362e1b8e8e` |

Preservation fingerprints cover sorted paths and SHA-256 values under `files`,
`databases`, `shared_prefs` and `no_backup`, excluding cache. The exact unchanged
comparison is immediately across installation, before app/test startup. After
instrumentation, the production aggregate was
`8541637e8174987c37487056b5fe177db6813b172ffd6032b295df2d6d609b30`.
Startup can write private metadata, but no per-file pre-test baseline was kept:
the difference is not attributed to a specific file and is not described as
byte-identical post-runtime preservation. No private transcript/database dump
is published.

The selected instrumentation command was:

```text
adb -s RRCN3008VYE shell am instrument --user 0 -w -r -e class
com.stressdetector.mobile.lifecycle.PortableUserDataArchiveInstrumentedTest,
com.stressdetector.mobile.ui.screens.ProfileBackupSectionTest,
com.stressdetector.mobile.data.db.StressDatabaseMigration20To21Test
com.stressdetector.test/androidx.test.runner.AndroidJUnitRunner
```

The actual command passes the three class names as one comma-separated argument.
Observed result: `Time: 35.077`, `OK (15 tests)`. Every case reported status 0;
none was skipped. Nine archive cases, five existing Compose cases and one
existing owner migration case ran; that untracked migration test is not staged.

The new provider case used the existing local read/write tree grant, wrote only
a UUID-owned `SafRecoveryAcceptance-*` child outside `Artifacts/user-data-backup`,
encrypted the archive through the real ContentResolver stream, deleted only the
test source Keystore alias, rejected a wrong password, then restored History,
chats, transcript edits, markers, chart and media into isolated Room/files using
a different key. Cleanup verified the exact owned document ID before deletion.
The post-test provider listing found no such folder and the production backup
slot remained empty. Numeric/audio bytes and transcript strings are labelled
test-only fixtures, not recognition, physiological or clinical evidence.

The new cache-I/O case restored isolated records/media, forced a real local
FileOutputStream failure using a test-owned directory, and checked that the
completed receipt/data remain readable, automation is disabled, and the old
cached password is absent. Five new JVM cases cover state reduction, including
cancellation rethrow, pending/receipt preservation and non-secret warning text.

Post-test checks found no active app service or app entry in the crash buffer.
The speech provisioning preference still reports `ready`; the previously pinned
Whisper bundle was not replaced or downloaded again. The real user automation
secret is still absent, and no production backup/password was created.

## Boundaries and next user action

- This tests raw SAF provider transport plus the portable archive, not service
  generation publication/selection, completion-manifest ordering or a controlled
  uninstall/reinstall cycle. The cache failure case calls the real store and
  state helper, not the complete service orchestration. Runtime cancellation is
  covered by JVM state tests/static wiring, not a device cancellation test.
- Create the first real backup in Profile with a privately chosen recovery
  password; wait for successful completion and no pending changes. Do not share
  that password in chat. Until then, an uninstall can still remove private data.
- Recovery still requires the same Room schema, empty scoped tables, reselecting
  the SAF tree and the correct password. It is not an arbitrary-version merge.
- Bilingual recognition/diarization accuracy, airplane-mode interview execution
  and actual Galaxy Watch PPG are separate unverified gates. No clinical or
  deception inference is claimed.
- Knowledge Base documents/indexes, reminder settings, consent and Android grants
  are outside this conversation archive; persona/models use their separate slots.

Implementation guidance was checked against official
[Kotlin cancellation cleanup](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
and [Android SAF lifecycle/grants](https://developer.android.com/training/data-storage/shared/documents-files).
Existing [real Profile previews](acceptance.md) remain historical UI evidence;
no synthetic screenshot or new visual-design claim is introduced here.
