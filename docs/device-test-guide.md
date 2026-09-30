# Uji perangkat HP dan Galaxy Watch

Panduan ini untuk APK `productionDebug` v0.1.0 dengan model internal dari run `20260916T132736Z`. Model ONNX berada di HP; Watch hanya merekam dan mengirim PPG. Instalasi dan unit test **bukan** bukti bahwa jalur sensor nyata sudah lolos.

## Sebelum mulai

1. Gunakan pasangan APK HP dan Watch dari [rilis yang sama](../README.md), atau build keduanya dengan index dan detector contract run yang sama.
2. Aktifkan USB debugging di HP dan wireless debugging di Watch. Baca serial saat ini lewat `adb devices -l`; port Watch dapat berubah.
3. Pastikan kedua perangkat muncul sebagai `device`, bukan `offline` atau `unauthorized`.

Jika Watch tidak muncul, hentikan uji sensor. Jangan mengganti dengan batch, sesi, atau hasil buatan.

## Instal tanpa menghapus data

Isi serial yang benar-benar tampil pada `adb devices -l` dan path dua APK yang
diunduh atau dibangun:

```powershell
$phoneSerial = Read-Host 'Serial ADB HP'
$watchSerial = Read-Host 'Serial ADB Watch'
$phoneApk = (Resolve-Path -LiteralPath (Read-Host 'Path APK HP')).Path
$watchApk = (Resolve-Path -LiteralPath (Read-Host 'Path APK Watch')).Path

adb -s $phoneSerial install -r $phoneApk
adb -s $watchSerial install -r $watchApk
adb -s $phoneSerial shell pm path com.stressdetector
adb -s $watchSerial shell pm path com.stressdetector
```

Kedua APK harus memiliki package `com.stressdetector` dan identitas penandatanganan yang cocok. Jangan memakai `pm clear`, uninstall, atau APK dari run lain untuk mengatasi kegagalan koneksi.

## Periksa satu sesi nyata

1. Buka aplikasi Watch dan berikan izin sensor yang diminta. Buka aplikasi HP dan berikan izin koneksi yang diperlukan.
2. Di HP, pilih **Coba koneksi**. Status penemuan perangkat saja belum cukup; tunggu aplikasi Watch dapat dijangkau.
3. Jalankan sesi penuh sekitar 300 detik. Catat jumlah sampel Green/IR yang benar-benar diterima, gap transport, dan status kualitas; jangan menganggap jumlah nominal sebagai bukti penerimaan.
4. Tunggu finalisasi berhenti pada hasil, sinyal tidak cukup, gagal, atau timeout. Jika kualitas gagal, aplikasi tidak boleh menampilkan prediksi stres yang yakin.
5. Periksa run ID model `20260916T132736Z`, kontrak detector, serta trace Green dan IR. Red bersifat diagnostik dan tidak memiliki model stres pada run ini.

Parity ONNX pada vektor fitur dan parity jalur **Watch nyata sampai fitur/probabilitas** adalah dua pemeriksaan berbeda. Receipt sensor hanya sah jika batch Watch aktual, lineage, kedua kanal, dan hasil verifier Python cocok. Ketiadaan capture atau satu kanal valid saja tetap `PENDING_DEVICE_CAPTURE`/`NO_GO`, bukan keberhasilan.

Jika Samsung Health Sensor SDK menolak akses, tampilkan dan catat pesan izin/kebijakan SDK; jangan mengisi sampel yang hilang. Hasil ini adalah dukungan keputusan internal, bukan diagnosis.
