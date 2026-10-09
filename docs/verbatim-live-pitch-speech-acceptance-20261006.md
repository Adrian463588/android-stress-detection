# Verbatim+ — pitch live, Whisper, dan speaker review

Tanggal: 2026-10-06. Scope: CAP-10/11 pada spec Verbatim+ yang sudah ada.
Status: gate source/software lulus; acceptance perangkat dan akurasi audio
menunggu pengujian manual pengguna. Tidak ada instalasi, ADB, rekaman mikrofon,
penghapusan data, atau screenshot baru pada perubahan ini.
APK dibangun dari worktree lokal yang juga berisi perubahan pengguna pada
fitur lain. Commit hanya mencakup allowlist pitch/speech/Verbatim+; bukan
snapshot seluruh worktree. Perubahan lain, raw/model/cache/binary tidak distage.

## Perbaikan dan batas kontrak

- Pitch memakai PCM mono 16 kHz yang sama dengan WAV/STT, bukan mikrofon kedua.
  Worker openSMILE menganalisis window utuh satu detik di luar capture/main
  thread. Handoff dan riwayat dibatasi; overload/pause memutus data, bukan
  menambah sampel atau membawa Hz terakhir sebagai pengukuran baru.
- Hz adalah **rerata geometrik F0 voiced** pada window, dikonversi dari
  eGeMAPSv02 semitone dengan `27.5 * 2^(semitones/12)`. Ini bukan F0
  instan, waveform amplitude, skor stres, atau pendeteksi desepsi.
- Panel live memperlihatkan Hz, periode observasi, grafik terputus, status
  jeda/unvoiced/kualitas/gap/native unavailable, dan semantics. Grafik memakai
  theme tokens dan layout wrapping; uji Compose compact/200% font ditambahkan
  untuk eksekusi manual. Riwayat maksimal 60 observasi bukan trace pitch penuh.
- Profil speech baru menyediakan Whisper tiny multilingual INT8 dalam
  Sherpa-ONNX 1.13.4 yang sudah digunakan; tidak menambah framework ASR/capture.
  Bahasa `id`/`en` dan task `transcribe` eksplisit. Zipformer tetap memproduksi
  partial Indonesia; Whisper memproduksi final Indonesia dan partial/final
  Inggris. Partial Inggris dijadwalkan setelah tambahan audio satu detik;
  ini cadence, **bukan hasil pengukuran latensi**.
- Stream Zipformer setelah `inputFinished()` dilepas dan dibuat ulang,
  tidak di-reset lalu diberi ujaran berikutnya. Teks berasal dari decode nyata;
  tidak ada koreksi diam-diam, terjemahan, cloud fallback, atau respons template.
- Whisper adalah ASR, bukan diarization. Segmentasi/embedding speaker native
  tetap terpisah: konteks PCM kontigu maksimal 30 detik, identitas session-owned,
  embedding dari span speaker eksklusif, coverage temporal bermakna. Gap,
  konflik, atau identitas ambigu tetap `Unknown`, bukan peran yang ditebak.
  Heuristik identity belum dikalibrasi menggunakan DER audio nyata.
- Final segment memakai crop PCM pada boundary nyata. Jika crop gagal
  menghasilkan teks, whole-utterance ASR dipertahankan dengan speaker unresolved;
  tidak menghapus ujaran atau mengarang timestamp kata. Crop pendek masih
  membutuhkan evaluasi recognition nyata.
- TXT mempertahankan raw transcript dan edit review terpisah, marker/cue,
  timestamp, serta prosody Hz/frame/version per ujaran jika tersedia. Pitch
  ekspor per ujaran berbeda dari window preview satu detik; bukan full trace.
- Bundle lama yang persis didukung tetap usable dan diberi provenance legacy.
  **Profile → Perbarui** diperlukan untuk Whisper; tidak otomatis mengunduh.
  Import/download kandidat memerlukan integrity dan native preflight sebelum
  commit aktivasi; kegagalan mempertahankan bundle lama, bukan menghapus sesi.
  Import saat speech lease aktif ditolak. Idle legacy tidak di-hash per detik;
  mirror SAF memakai dan memulihkan generation versi yang benar.
- PPG/ONNX Watch, kanal independen, quality gates, baseline, dan marker existing
  tidak diubah. Pitch/text tidak difusikan menjadi skor kebohongan atau stres.

BMAD/SDD membekukan input/output/failure/test sebelum source diubah. PONYTAIL
menjaga satu runtime/PCM pipeline; domain/policy murni dan komponen Compose
stateless mempertahankan MVVM/UDF. RTK/CAVEMAN/PONYTAIL tidak menjadi dependency.

## Identitas model

Bundle baru:
`sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-whisper-tiny-v4`.

Revision upstream:
`65176e2deb88badc814a94058666cadccc29b61c`.
Byte count dan SHA-256 di katalog dicocokkan dengan metadata LFS upstream;
tokens juga di-hash dari bytes upstream revision yang sama. **Bobot Whisper
belum dijalankan pada perangkat dalam perubahan ini.**

| Berkas Whisper | Bytes | SHA-256 |
| --- | ---: | --- |
| Encoder INT8 | 12,937,772 | `d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434` |
| Decoder INT8 | 89,855,401 | `d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925` |
| Tokens | 816,730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

Ukuran berkas bukan RAM/CPU/thermal evidence. Lisensi/model/runtime tetap
tercatat pada katalog. openSMILE yang dibundel berlisensi research/noncommercial;
distribusi komersial memerlukan pemeriksaan lisensi terpisah.

## Riset pembanding

[Listing developer Veritas](https://play.google.com/store/apps/details?id=com.veritasai.app)
dipakai sebagai tinjauan utama workflow review dan organisasi bukti; engine
proprietary/angka pemasaran tidak divalidasi atau direplikasi.
[ATLAS.ti](https://doc.atlasti.com/ManualWin.v9/Multimedia/MultimediaDataHandling.html)
mendokumentasikan media/waveform annotation; waveform bukan pitch.
Perhitungan memakai fitur nyata
[openSMILE](https://audeering.github.io/opensmile-python/usage.html), sementara
[Whisper](https://github.com/openai/whisper) dan
[Sherpa diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html)
memenuhi peran yang berbeda.

[Evaluasi NIJ](https://nij.ojp.gov/topics/articles/voice-stress-analysis-only-15-percent-lies-about-drug-use-detected-field-test)
menunjukkan klaim voice-stress tidak boleh dianggap bukti lie detection.
[NICHD](https://pmc.ncbi.nlm.nih.gov/articles/PMC2180422/) menyediakan metodologi
wawancara forensik, bukan label kebenaran otomatis atau benchmark ASR.
[FLEURS](https://huggingface.co/datasets/google/fleurs) (CC-BY-4.0; `id_id`/`en_us`)
merupakan kandidat evaluasi read-speech, bukan wawancara spontan atau benchmark
diarization. Tidak ada rekaman korban/perkara sensitif yang diunduh/dipakai.
Kasus Indonesia salah yang dilaporkan belum memiliki pasangan audio/reference
dan speaker ground truth; perbaikan kode tidak membuktikan kasus itu teratasi.

## Checklist pengujian manual perangkat

Gunakan APK hasil receipt di bawah setelah memverifikasi serial, package,
version dan signer. Replacement install harus menggunakan `adb install -r`
pada serial pilihan pengguna; jangan uninstall, `pm clear`, atau menghapus
folder model/sesi. Jika signer/package tidak cocok, hentikan instalasi dan
laporkan ketidakcocokan, bukan menghapus data.

- [ ] Sebelum/ sesudah install: sesi, audio, edit, settings dan model lama tetap
  tersedia. Package research lain tidak disentuh.
- [ ] Profile menawarkan Perbarui pada legacy. Update/import ZIP tervalidasi
  menghasilkan profile Whisper; gagal/batal/tidak cukup storage mempertahankan
  bundle lama. Uji rollback hanya dengan direktori/candidate khusus pengujian.
- [ ] Setelah provisioning, airplane mode: session audio-only Indonesia dan
  Inggris berjalan tanpa network; kegagalan model/native tampil eksplisit.
- [ ] Dua pembicara yang menyetujui rekaman: reference literal dan speaker turn
  disiapkan manual. Uji satu speaker, giliran pendek/panjang, overlap, nama,
  negasi, filler, aksen dan code-switching. Catat WER/CER/DER beserta model/device,
  jangan menganggap heuristic audio quality sebagai ASR confidence.
- [ ] Beberapa ujaran berurutan setelah endpoint tidak kehilangan partial/final
  kedua. Final tidak ditimpa partial lama; speaker unresolved tidak diberi peran
  otomatis. Edit speaker/transcript persisten tanpa menimpa raw ASR.
- [ ] Rekam minimal 300 detik, lakukan pause/resume dan stop: timeline PCM/WAV,
  transcript, marker seek dan playback menunjuk momen yang sama; tidak ada
  observasi late/session lama pada sesi berikutnya.
- [ ] Pitch voiced muncul dalam Hz selama perekaman, bukan hanya setelah STT
  selesai. Diam/noise/jeda/native failure tidak meneruskan Hz terakhir. Tidak
  ada garis melewati gap. Jika membandingkan pitch referensi, gunakan PCM/window
  dan jenis rerata voiced yang sama; jangan membandingkan F0 instan dengan mean.
- [ ] Export TXT dapat dibuka/download; marker explanation, literal text,
  speaker edit, timestamp dan prosody provenance sesuai bukti rekaman.
- [ ] Phone compact/standard, landscape, tablet/expanded, 200% font dan TalkBack:
  text wrapping, chart semantics, transcript scroll, Pause/Stop, edit, marker
  navigation dan export tetap dapat digunakan. Jalankan tes Compose/native
  yang dikompilasi sebelum menyatakan device acceptance lulus.
- [ ] Ukur startup, backlog/latency, RAM/CPU, thermal dan battery pada audio nyata;
  cadence satu detik/native packaging bukan performance evidence.
- [ ] Gate Watch terpisah: paired Galaxy Watch dan PPG nyata, quality/baseline
  lengkap, channel identity, ONNX marker dan unavailable/gap. Audio-only tidak
  menunggu Watch. Tanpa evidence tersebut PPG belum dinyatakan lulus.

## Receipt lokal

Final invocation: **BUILD SUCCESSFUL, 4m41s**, 152 tasks (27 executed,
125 up-to-date). Source dibekukan selama invocation final. Semua gate berikut
lulus, tanpa mengubah lint baseline atau melemahkan checks:

```powershell
.\gradlew.bat "-PinternalModelIndex=../data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json" `
  "-PdetectorContract=../data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json" `
  :app:ktlintCheck :app:detekt :app:lintProductionDebug `
  :app:testProductionDebugUnitTest :app:compileProductionDebugAndroidTestKotlin `
  :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest `
  --console=plain --max-workers=2 --continue
```

Unit receipt: **706 tests, 170 suites, 0 failures/errors/skips**. Detekt:
zero findings. Android lint: zero errors, **72 warnings, 2 hints**;
nonfatal warnings belum dihapus dan tidak boleh disebut warning-free.
Scoped ktlint formatting, spec resolver dan `git diff --check` juga dijalankan.
Attempt awal gagal karena lint dan constructor fixture test; perbaikan diikuti
rerun penuh, bukan menyembunyikan kegagalan tersebut.

APK aplikasi: `app/build/outputs/apk/production/debug/app-production-debug.apk`.
Package `com.stressdetector`, version code/name `1`/`0.1.0`, minimum SDK 26.
SHA-256:
`671ca6f89dca9c7e4bcb9b00de5293aac67ff4140b333b826ec5e8356de53d31`.

APK tes:
`app/build/outputs/apk/androidTest/production/debug/app-production-debug-androidTest.apk`.
SHA-256:
`d4f61fee743fba6d5920d8c82a03a360019ca32e0b35d72258c51614ce6d52c0`.

Lima kasus Compose pitch, dua lifecycle native, dan tiga rollback/import
instrumentation baru **dikompilasi, tidak dieksekusi**. Pilih kelas
`VerbatimPitchPanelTest`, `SherpaNativeRuntimeInstrumentedTest` dan
`SpeechModelBundleManagerInstrumentedTest` untuk pengujian manual terisolasi;
native tests tetap memerlukan bundle nyata. Fixture archive/numerik menguji
mekanik, bukan ASR quality, sensor, diagnosis atau perangkat.

Traceability: CAP-10 producer/projection/semantics/window-gap-cutoff lulus
software; CAP-11 language/profile/role integrity, session stream renewal,
speaker partition/evidence, upgrade/generation dan lease policy lulus software.
Runtime import rollback tersedia sebagai compiled instrumentation, belum
physical pass. Tidak ada WER/CER/DER, latency, thermal atau Watch metric baru.
Branch delivery memakai `android-devsecops-20260808`, non-force, dengan
allowlist source/spec/report dan hanya hunk README Verbatim+; `main` bukan target.
