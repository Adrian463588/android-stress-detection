# Device Test Guide — Production Only

## Build APK demo

```powershell
cd D:\SEMESTER12\SkripiDump\ProsesData\android
.\gradlew.bat :app:assembleProductionDebug :wear:assembleProductionDebug `
  "-PinternalModelIndex=..\data\artifacts\runs\20260916T132736Z\export\internal\internal_model_index.json" `
  "-PdetectorContract=..\data\artifacts\runs\20260916T132736Z\export\internal\ppg_detector_contract.json"
```

APK demo:

```text
app/build/outputs/apk/production/debug/app-production-debug.apk
wear/build/outputs/apk/production/debug/wear-production-debug.apk
```

Package Phone dan Watch harus sama-sama `com.stressdetector`. Android project
tidak lagi menghasilkan APK research. Artifact research lama hanya disimpan
sebagai evidence pipeline historis.

## Manual Test Flow

1. Baca ulang serial Phone dan endpoint wireless Watch dengan `adb devices -l`.
2. Jika masih terpasang, uninstall hanya package `com.stressdetector.research`
   pada Phone dan Watch. Jangan uninstall `com.stressdetector` atau memakai
   `pm clear`.
3. Install pasangan APK production dengan `adb install -r -d`.
4. Open the watch app once and grant body sensor permissions.
5. Open the phone app.
6. Press `Coba koneksi` and wait for `Siap` / `Mulai deteksi`; node discovery alone is not sufficient.
7. Press `Mulai deteksi` from the phone, or press `Start` on the watch.
8. Wait until the session reaches the full `300 s` boundary.
9. A stable session should show approximately `7500` raw Green samples and `7500` raw IR samples from the Watch; IR is resampled to the model rate on the phone.
10. Final inference requires up to seven valid 180-second windows from the core `[30 s, 270 s)` interval.
11. Confirm the packaged model run is `20260916T132736Z` and no `.research` launcher exists.

## Device requirement

There is no production synthetic-session shortcut. Without a paired phone and Watch,
the flow cannot produce device acceptance evidence and must remain pending.

## Expected Behavior

- Green is the primary production candidate.
- IR is secondary when `stress_ppg_ir_model.onnx` exists.
- If quality is poor or window is too short, the UI shows `Invalid` rather than a confident prediction.

## Known Runtime Risk

Samsung Health Sensor SDK access can fail with policy or permission errors on unsupported watches or non-approved environments. In that case the watch app will show the SDK error in its status line.
