# Stress Detector untuk Android dan Galaxy Watch

Aplikasi pendamping yang merekam PPG dari Galaxy Watch dan menghitung hasil stres
secara lokal di HP. Hasilnya adalah **bantuan pengambilan keputusan**, bukan
diagnosis medis.

## Unduh aplikasi v0.1.0

- **[Unduh APK HP v0.1.0](https://github.com/Adrian463588/android-stress-detection/releases/download/android-v0.1.0/app-production-debug.apk)**
- **[Unduh APK Galaxy Watch v0.1.0](https://github.com/Adrian463588/android-stress-detection/releases/download/android-v0.1.0/wear-production-debug.apk)**

Kedua berkas berasal dari [rilis yang sama](https://github.com/Adrian463588/android-stress-detection/releases/tag/android-v0.1.0).
Instal keduanya: Watch merekam PPG, sedangkan model ONNX berjalan di HP. APK
ini **debug build untuk evaluasi internal**, bukan rilis Play Store. Repositori
bersifat privat; dosen atau developer perlu akses GitHub untuk mengunduhnya.

Model yang dikemas berasal dari run `20260916T132736Z`. Green menjadi kanal
utama, IR dinilai terpisah, dan Red tidak memiliki model stres. Kualitas sinyal
yang tidak memadai menghasilkan status *sinyal tidak cukup*, bukan prediksi
yang dibuat-buat.

### Cara mencoba

1. Instal APK HP dan APK Watch dari tautan di atas, lalu pastikan kedua perangkat terhubung.
2. Buka aplikasi Watch dan HP; berikan izin sensor serta Bluetooth yang diminta.
3. Di HP, pilih **Coba koneksi**. Mulai sesi saat status Watch menunjukkan siap.
4. Rekam sesi sekitar lima menit. Baca hasil Green/IR dan status kualitas di HP.
   Jika sinyal tidak cukup, perbaiki posisi Watch atau koneksi sebelum mengulang.

Panduan ADB dan pemeriksaan sesi nyata tersedia di
[panduan uji perangkat](docs/device-test-guide.md).

## Apa yang tersedia?

| Bagian | Fungsi |
| --- | --- |
| Home | Koneksi Watch, pemantauan PPG, dan hasil sesi stres. |
| History | Riwayat hasil sesi yang tersimpan. |
| Verbatim+ | Perekaman percakapan dan PPG dalam satu alur; fitur audio tetap dapat digunakan saat PPG tidak tersedia. |
| AI+ | Bantuan berbasis dokumen lokal; sumber web memerlukan persetujuan. Model atau sumber yang belum siap ditampilkan sebagai tidak tersedia. |
| Profile | Pengaturan izin, penyimpanan, dan model lokal. |

Antarmuka memakai Jetpack Compose dan MVVM.
[Tangkapan layar aplikasi](docs/screenshots/) bersifat ilustrasi antarmuka;
bukti pengujian perangkat harus berasal dari sesi aktual.

## Untuk developer

### Struktur

| Modul | Tanggung jawab |
| --- | --- |
| `:app` | UI Compose, ViewModel, penyimpanan lokal, dan koordinasi sesi di HP. |
| `:wear` | Akuisisi PPG dan status sesi di Galaxy Watch. |
| `:shared` | Kontrak pesan dan data lintas HP–Watch. |
| `:inference` | Pemeriksaan kontrak model, preprocessing, fitur, dan inferensi ONNX. |

Model stres hanya memakai PPG. ECG dipakai di pipeline Python sebagai referensi
penelitian, **bukan** input aplikasi. Tidak ada fallback ke data atau model
tiruan ketika sensor, kontrak, atau model tidak tersedia.

### Prasyarat build

- Android SDK, JDK 17, dan perangkat/ADB untuk uji fisik.
- Direktori saudara `../data/artifacts/runs/20260916T132736Z/` dari proyek
  ProsesData, berisi `internal_model_index.json`,
  `ppg_detector_contract.json`, dan model ONNX yang dirujuk. Artefak penelitian
  ini **tidak** disimpan dalam repo Android mandiri.

Build sengaja gagal jika artefak run tersebut hilang, berubah, atau tidak cocok.
Tidak ada fallback ke index model global.

Jalankan dari direktori `android/` di checkout ProsesData:

```powershell
$runRoot = (Resolve-Path ..\data\artifacts\runs\20260916T132736Z).Path
$index = Join-Path $runRoot 'export\internal\internal_model_index.json'
$detector = Join-Path $runRoot 'export\internal\ppg_detector_contract.json'

.\gradlew.bat --no-daemon "-PinternalModelIndex=$index" "-PdetectorContract=$detector" `
  :app:assembleProductionDebug :wear:assembleProductionDebug
```

Hasil build berada di:

```text
app/build/outputs/apk/production/debug/app-production-debug.apk
wear/build/outputs/apk/production/debug/wear-production-debug.apk
```

Nama variant `productionDebug` adalah nama Gradle; **cakupan model tetap
evaluasi internal**. HP dan Watch memakai package `com.stressdetector` dan
harus ditandatangani dengan identitas yang cocok agar Wear Data Layer bekerja.

### Test dan pemeriksaan statis

Dengan `$index` dan `$detector` dari perintah sebelumnya:

```powershell
.\gradlew.bat --no-daemon "-PinternalModelIndex=$index" "-PdetectorContract=$detector" `
  :shared:test :inference:testProductionDebugUnitTest `
  :app:testProductionDebugUnitTest :wear:testProductionDebugUnitTest `
  ktlintCheck detekt :inference:lintProductionDebug `
  :app:lintProductionDebug :wear:lintProductionDebug
```

Build dan unit test tidak menggantikan uji sesi Watch nyata. Untuk bukti fisik,
periksa perangkat melalui `adb devices -l`, lalu ikuti
[panduan uji perangkat](docs/device-test-guide.md). Jangan memakai data sintetis
sebagai bukti sensor atau menganggap instalasi APK sebagai bukti kualitas prediksi.

<details>
<summary>SHA-256 APK pada rilis v0.1.0</summary>

- HP: `db8dafbc11fe16bd9e86e72995968bf23c7a7b764b27e0501b1c5c419115fa73`
- Watch: `4c4a57afedb0df13a5e8aa44e112579346ed1d8c3db1d6a06ed67dd1273c021d`

</details>
