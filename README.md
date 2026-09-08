# Stress Detector Android: Phone + Wear

Android project untuk phone companion dan Galaxy Watch. Proyek ini mengintegrasikan pengumpulan data biosignal (PPG) dari smartwatch, inferensi ONNX lokal, serta fitur pendukung keputusan (Verbatim+) dan asisten RAG (AI+ Chatbot).

## Tampilan dan Fitur Utama Aplikasi

Aplikasi ini memiliki beberapa fitur utama yang dirancang dengan antarmuka berbasis Jetpack Compose:

1. **Sesi Perekaman Stres (Verbatim+)
   Perekaman real-time yang terhubung ke Galaxy Watch untuk mengambil data PPG.
   ![Stress Record Session](docs/screenshots/1.StressRecordSession.png)

2. **Pasca-Perekaman & Sinkronisasi**
   Memastikan data aman dan tersinkronisasi sebelum inferensi dijalankan.
   ![Screen After Record](docs/screenshots/2.ScreenAfterRecordStressDetection.png)

3. **Hasil Deteksi Stres**
   Menampilkan hasil inferensi model klasifikasi berbasis ONNX secara on-device.
   ![Screen Result](docs/screenshots/3.ScreenResultStressDetection.png)

4. **Riwayat Deteksi (History)**
   Mencatat hasil deteksi historis untuk evaluasi lebih lanjut.
   ![History Record](docs/screenshots/4.HistoryRecordStressDetection.png)

5. **Manajemen Dokumen (AI+ RAG)**
   Pengguna dapat mengelola dokumen lokal sebagai basis pengetahuan tersanitasi untuk AI.
   ![Dokumen RAG](docs/screenshots/5.ScreenDokumenRAG.png)

6. **AI+ Chatbot Counselor**
   Asisten konseling lokal (on-device LiteRT-LM) yang memanfaatkan dokumen untuk menghasilkan jawaban aman (evidence-gated).
   ![Chatbot Counselor](docs/screenshots/6.ScreenAI+ChatbotCounselor.png)

Source utama produk tetap [`../PRD.md`](../PRD.md); kontrak UI/runtime Verbatim+ ada di [`../DESIGN.md`](../DESIGN.md) dan [`../EXPERIENCE.md`](../EXPERIENCE.md).

## Modul aktual

| Modul | Tanggung jawab |
|---|---|
| `:app` | Phone app Kotlin + Jetpack Compose, MVVM/UDF, Home, History, Verbatim+, AI+, Profile, Room, SAF, speech/audio, dan koordinasi Watch. |
| `:wear` | Aplikasi Galaxy Watch, tracker PPG Green/IR/Red, koneksi Wear Data Layer, dan status sesi Watch. |
| `:shared` | DTO, codec batch, path pesan, sampling constants, dan kontrak lintas phone–Watch. |
| `:inference` | Validasi manifest/hash/schema, preprocessing, ONNX runtime, dan fail-closed readiness. |

Home stress detection tetap memiliki sesi maksimal 5 menit. Verbatim+ adalah sesi
terbuka yang berhenti melalui `Stop`, error fatal, atau proses Android dihentikan.
Audio/transkripsi tetap dapat berjalan ketika Watch atau PPG unavailable; aplikasi
menampilkan gap/unavailable dan tidak membuat sample, BPM, prediksi, atau transcript
sintetis.

## Verbatim+ UI aktual

- Storage dan speech readiness diproyeksikan dari state nyata yang sama dengan Profile.
- Folder belum tersinkron tidak menghasilkan status sukses palsu; recovery memakai
  `Buka Profile` yang membuka halaman Profile utama secara langsung.
- Saat belum ada sesi, layar tidak menampilkan tab transcript/marker atau panel teknis
  kosong.
- Drawer sesi memakai loading/error/empty state terpisah, judul maksimal dua baris,
  status ringkas, bounded width, dan target hapus minimum 48dp.
- Pada compact/large-font layout, CTA utama tetap reachable dan aksi export/delete
  tidak saling memotong.
- Green dan IR adalah channel inference independen; Red tetap diagnostik. Marker
  fisiologis adalah evidence untuk review, bukan diagnosis atau deception detection.

## AI+ local-first evidence

AI+ memakai LiteRT-LM sebagai generator on-device dan tidak menganggap model
yang belum siap sebagai jawaban yang berhasil. Retrieval evidence berjalan
fail-closed dengan urutan berikut:

```text
APP_RAG → BUNDLED_LOCAL → WEB (consent) → NONE / UNAVAILABLE
```

- `APP_RAG` membaca dokumen lokal terenkripsi yang memiliki provenance valid.
- `BUNDLED_LOCAL` hanya membaca `txt`, `md`, atau `json` yang terdaftar di
  `app/src/main/canonicalAssets/ai/bundled_knowledge_manifest.json` dan lolos
  verifikasi checksum. Manifest saat ini berstatus `EMPTY` karena tidak ada
  corpus konseling berlisensi yang benar-benar tersedia; tidak ada dataset
  training biosignal, log, metadata partisipan, atau dokumen engineering yang
  dipromosikan menjadi corpus chatbot.
- `WEB` hanya aktif setelah consent eksplisit. Query hanya berasal dari
  pertanyaan pengguna yang telah disanitasi; persona, riwayat stress, session
  ID, dan konteks privat tidak dikirim. URL HTTPS publik, batas halaman,
  timeout, dan citation divalidasi sebelum model menerima evidence.
- `NONE` menampilkan `UNAVAILABLE` dan tidak memanggil generator untuk
  pertanyaan yang membutuhkan evidence. Tidak ada citation, jawaban, atau
  status sukses yang dibuat-buat.

Pesan menyimpan source tier, path/URL, checksum, provenance, dan waktu
retrieval. UI menampilkan sumber aktif sebagai `Dokumen lokal`, `Dataset
bawaan`, atau `Web`; error teknis berada pada recovery card terpisah dari
bubble jawaban. Pertanyaan dukungan non-evidence boleh memakai `GENERAL_SAFE`
dengan disclaimer. Kontrak ini mengikuti [LiteRT-LM Kotlin Conversation API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md),
[Android app architecture](https://developer.android.com/topic/architecture),
dan pembatasan SSRF dari [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

## AI+ Reminder

AI+ Reminder adalah fitur opt-in yang membuat paling banyak satu pengingat untuk
satu sesi stress yang valid. Planner dan generator memakai LiteRT-LM lokal; teks
dibuat saat worker berjalan agar tetap memakai sesi valid terbaru. Nama display
dan payload reminder disimpan melalui encrypted storage, sedangkan Room hanya
menyimpan metadata dan provenance. Raw PPG, transcript penuh, dan dokumen RAG
tidak masuk ke notifikasi.

Reminder hanya aktif setelah consent AI+ Reminder dan consent counseling diberikan,
nama (opsional) disimpan, model lokal siap, serta izin notifikasi tersedia. Model
tidak boleh menghasilkan recurrence, waktu lampau, waktu lebih dari 48 jam, klaim
diagnosis, atau klaim tugas grounding yang tidak tercatat. Output invalid atau
model/permission/sesi tidak siap menghasilkan status `UNAVAILABLE`/`FAILED`, bukan
pesan pengganti.

WorkManager menjadwalkan pekerjaan secara persisten dan bersifat perkiraan; Android
dapat menunda eksekusi. Notifikasi memakai channel stabil, `VISIBILITY_PRIVATE`,
`publicVersion` yang tidak membocorkan isi sesi, dan PendingIntent immutable yang
hanya membawa `reminderId` menuju AI+. Permission notifikasi diminta ketika user
mengaktifkan reminder dan tidak menghalangi monitoring PPG.

### Live preview PNG

Screenshot PNG reminder dari perangkat nyata belum dapat diterbitkan pada checkout
ini. Pada satu percobaan manual, `adb install -r -d` berhasil memasang APK
research ke `QSWSEMRKNFZ9LJRC` (`2412DPC0AG`) dan `MainActivity` berhasil dibuka.
Percobaan reinstall berikutnya melalui Gradle ditolak Android dengan
`INSTALL_FAILED_USER_RESTRICTED`; pada verifikasi terakhir `adb devices -l` juga
tidak menampilkan serial tersebut. Bundle Sherpa belum tersedia, instrumented
model-parity belum menghasilkan receipt, permission notifikasi masih disabled,
dan belum ada sesi PPG nyata yang memvalidasi delivery reminder. Status live
preview tetap **BLOCKED**. Tidak ada gambar sintetis, dummy, placeholder, atau
screenshot buatan yang ditambahkan sebagai bukti.

Kontrak dan state machine lengkap berada di
[`docs/specs/spec-ai-reminder/SPEC.md`](docs/specs/spec-ai-reminder/SPEC.md).

Current device gate (2026-09-08): APK research berhasil dibuild. Satu percobaan
install/launch manual berhasil, tetapi reinstall Gradle berikutnya diblokir oleh
kebijakan perangkat (`INSTALL_FAILED_USER_RESTRICTED`) dan ADB kemudian tidak
menampilkan device. Instrumented suite belum menjadi acceptance gate final;
Sherpa/model parity, permission notifikasi, sesi PPG nyata, reboot/timezone, dan
deep-link acceptance masih harus diverifikasi pada perangkat yang siap.

## Build dan verifikasi lokal

Jalankan dari folder ini:

```powershell
.\gradlew.bat :shared:test
.\gradlew.bat :app:test :wear:test
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lint
.\gradlew.bat :app:assembleDebug :wear:assembleDebug
```

Compose drawer test pada perangkat/emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.stressdetector.mobile.ui.screens.UnifiedSessionSidebarTest'
```

Test tersebut memeriksa layout compact dengan judul sesi panjang, state folder
belum terhubung, CTA Profile, dan status artifact. Fixture hanya untuk pengujian
layout; runtime tidak menggunakan fake, mock, dummy, placeholder, atau data sintetis.

## Deployment aman

Pasang APK tanpa menghapus data aplikasi:

```powershell
adb -s <phone-serial> install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s <phone-serial> shell monkey -p com.stressdetector 1
```

Jangan gunakan `uninstall`, `pm clear`, atau penghapusan model untuk acceptance
normal. Verifikasi package, serial, launch, crash log, dan APK checksum sebelum
mengklaim device acceptance.

## Evidence terakhir — 2026-08-27

Smoke dilakukan pada Samsung `RRCN3008VYE` / `SM-G988B` memakai APK debug
terbaru. AI+ berhasil dibuka dan menampilkan state model yang belum tersedia;
tidak ada jawaban/citation/status sukses sintetis.
Evidence UI nyata:

![Verbatim+ readiness](../docs/evidence/verbatim_readiness_smoke_20260825.png)

![Verbatim+ drawer](../docs/evidence/verbatim_storage_drawer_smoke_20260825.png)

![Profile storage recovery](../docs/evidence/verbatim_profile_recovery_smoke_20260825.png)

![AI+ local-first model-missing state](../docs/evidence/android_ai_plus_local_first_smoke_20260827.png)

APK debug yang diverifikasi:

| APK | Ukuran | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 254,750,144 bytes | `2787928C1A1E9D9E0291D21810EF59D545E93F3595B1154396B44B88CB0CDE58` |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 17,697,960 bytes | `9337D83104E931D3559AECA33CD1BD297534494B4C66F5AA823F35F0A683ACC2` |

| Evidence | Status |
|---|---|
| `:app:test`, `:wear:test`, ktlint, detekt, app lint, debug assembly | PASS pada working tree `2026-08-27` |
| Compose drawer instrumented test | PASS pada `SM-G988B` |
| Verbatim+ recovery, drawer, dan direct Profile navigation | PASS pada perangkat substitusi |
| AI+ local-first resolver, provenance, consent, model-missing fail-closed | PASS pada unit test dan phone smoke |
| Phone target `QSWSEMRKNFZ9LJRC` | BLOCKED: tidak terlihat pada `adb devices` |
| Watch target `192.168.0.8:38805` | BLOCKED: tidak terhubung pada ADB |
| Real microphone, speech bundle, saved-session artifact, dan Watch PPG | Belum di-claim; memerlukan provisioning/session/hardware nyata |

Software gate tidak menggantikan physical phone + Galaxy Watch acceptance. Root
[`../README.md`](../README.md) menyimpan evidence matrix pipeline dan Android terbaru.

## Model assets

Asset inference bukan source file manual. Task `:inference:syncInferenceAssets`
hanya menyalin bundle berstatus `accepted` dari indeks kanonis:

```text
../data/artifacts/export/model_index.json
../data/artifacts/export/{model_version}/stress_{sensor}_fp32.onnx
inference/build/generated/inferenceAssets/models/{model_version}/{sensor}/
wear/libs/samsung-health-sensor-api-1.4.1.aar
```

Jika indeks kosong, tidak lengkap, atau belum `android_ready`, packaging inference
berhenti fail-closed. Adapter produksi tidak membaca fallback flat assets.
