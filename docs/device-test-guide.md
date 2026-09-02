# Device Test Guide

## Build APK

```powershell
cd D:\SEMESTER12\SkripiDump\ProsesData\android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :wear:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

## Manual Test Flow

1. Install `app-debug.apk` on the phone.
2. Install `wear-debug.apk` on the Samsung Galaxy Watch.
3. Open the watch app once and grant body sensor permissions.
4. Open the phone app.
5. Press `Start Watch` from the phone, or press `Start` on the watch.
6. Wait until the session reaches the full `300 s` boundary.
7. A stable session should show approximately `7500` raw Green samples and `7500` raw IR samples from the Watch; IR is resampled to the model rate on the phone.
8. Final inference requires up to seven valid 180-second windows from the core `[30 s, 270 s)` interval.

## Device requirement

There is no production synthetic-session shortcut. Without a paired phone and Watch,
the flow cannot produce device acceptance evidence and must remain pending.

## Expected Behavior

- Green is the primary production candidate.
- IR is secondary when `stress_ppg_ir_model.onnx` exists.
- If quality is poor or window is too short, the UI shows `Invalid` rather than a confident prediction.

## Known Runtime Risk

Samsung Health Sensor SDK access can fail with policy or permission errors on unsupported watches or non-approved environments. In that case the watch app will show the SDK error in its status line.
