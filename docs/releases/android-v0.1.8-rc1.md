# Release Notes — Stress Detector Android `v0.1.8-rc1` & Galaxy Watch `v0.1.2-rc1`

**Status Rilis:** Pre-release (Release Candidate untuk penelitian, pembelajaran, dan evaluasi *expert judgment* nonkomersial)  
**Git Tag:** `android-v0.1.8-rc1`  
**Tanggal:** 27 November 2026  

---

## Ringkasan Perubahan Sejak `v0.1.6-rc1` / `v0.1.7-rc1`

1. **Penyelesaian Skoring Universal (`40` Instrumen Psikometri) & Audit Materi**
   - Menambahkan instrumen `HTP`, `DAP`, `Baum Test (Tes Pohon)`, `CBI-TBI`, dan `ESJ` sehingga seluruh **40 instrumen** berjalan dalam satu alur terpadu (`Asesmen → Skoring → Visualisasi → Interpretasi → AI+`) dengan riwayat snapshot unik per `resultId` (`KnowledgeBaseDatabase v3`).
   - Memulihkan alur demonstrasi dan eksploratif agar psikolog penilai dapat mencoba seluruh instrumen tanpa pemblokiran antarmuka yang menghentikan evaluasi purwarupa, sambil tetap memisahkan hasil eksploratif/skor mentah dari skor tervalidasi klinis.
   - Memperbaiki perhitungan `AQ-15` agar mengikuti matriks `Q/Ca/Cl` pada workbook referensi (`Adversity Quotient.xlsx`) dan memulihkan opsi butir `#48` `WPT`.

2. **Tes Proyektif: Akuisisi `MotionEvent`, Kinematika & AI Multimodal Lokal**
   - Menyatukan kanvas gambar proyektif (`Wartegg`, `HTP`, `DAP`, `Baum`, serta anotasi opsional `Rorschach`) dengan `MotionEvent` + koordinat historis, kompensasi offset koordinat Compose, seleksi mode input (`Auto / Jari / Stylus`), penghapus, *undo/redo*, dan deteksi kapabilitas stylus (`pressure`, `tilt`, `orientation`, *hover*) yang mempertahankan `null` secara jujur saat sensor tidak tersedia.
   - Mengekstrak fitur kinematik, temporal, dan spasial (kecepatan, percepatan, *jerk*, kelengkungan, jeda antar-goresan `>= 250 ms`, revisi, kepadatan ruang, *roughness index*), merender grafik deret waktu, serta mengirimkan PNG kanvas ke model visi multimodal lokal (`Gemma 4 E2B` melalui `LiteRTLlmEngine.analyzeMultimodal`) dengan fallback ekstraksi fitur terstruktur saat visi lokal tidak tersedia.

3. **Integrasi AI Counselor (`AI+`) & Basis Pengetahuan Lokal**
   - Menambahkan pencarian hibrida lokal (BM25 + *cosine similarity*), `AdaptiveCounselingRouter`, dukungan gelembung rekomendasi interaktif, dan pengikatan hasil asesmen spesifik melalui `pinnedResultId` dengan isolasi ketat `participantId` serta pemeriksaan persetujuan (*consent*) aktif.

4. **Workspace Klinis `Verbatim+` & Kandidat Pemantauan Suhu Kulit**
   - Menyempurnakan ruang kerja `Verbatim+` untuk perekaman audio, prosodi vokal (`Sherpa-ONNX` + `openSMILE`), penanda sesi PPG/HR, paket portabel terenkripsi Storage Access Framework (`SAF ZIP`), serta dukungan skema **Skin Temperature Monitoring** (`Room v24 → v25`, `ThermalMonitoringEngine`) dengan kebijakan awal `CANDIDATE_OBSERVE_ONLY`.

5. **Keamanan, Lisensi & Penyelarasan Modul Wear OS (`v0.1.2-rc1`)**
   - Memperbaiki paritas checksum integritas aset `AppHardeningManager` untuk berkas berakhiran baris `LF` (Linux CI) maupun `CRLF` (Windows).
   - Menambahkan tampilan versi langsung pada UI **Profil → Tentang aplikasi** (Phone `v0.1.8-rc1`, build `9`) dan footer **Stress Watch** (Wear `v0.1.2-rc1`, build `3`).
   - Menandatangani `wear-production-release.apk` menggunakan identitas sertifikat yang sama dengan aplikasi Phone untuk menjaga pengiriman Wear Data Layer.
   - Menambahkan dokumen lisensi [`LICENSE`](../../LICENSE) (*Research, Educational and Non-Commercial Use Only*) serta [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md).

---

## Artefak Rilis & Verifikasi Keamanan APK

| Aset Rilis | Modul / Platform | Versi (`versionCode`) | Ukuran File | ABI | SHA-256 |
|---|---|---|---|---|---|
| `stress-detector-android-v0.1.8-rc1.apk` | Phone Android (`Min SDK 26`, `Target SDK 34`, `Compile SDK 35`) | `0.1.8-rc1` (`9`) | `288,075,007` byte (`274.7 MiB`) | `arm64-v8a`, `armeabi-v7a`, `x86_64` | `3E083F6E684F73884327EE6ED931AC3C4407055967B2B2B6EB515488B2747119` |
| `stress-detector-wear-v0.1.2-rc1.apk` | Galaxy Watch / Wear OS (`Min SDK 28`, `Target SDK 34`, `Compile SDK 34`) | `0.1.2-rc1` (`3`) | `1,907,662` byte (`1.8 MiB`) | Universal DEX | `DA3CECCBF87A186B96A8A3F2FF230817261622329C5BE90099AD3A252D47E2E5` |

- **Package ID**: `com.stressdetector`
- **Build Type**: Production Release (`debuggable=false`, R8 full mode & resource shrinking aktif)
- **Signing Certificate (`apksigner verify`)**: `CN=Android Debug` (sertifikat kunci internal proyek yang disematkan pada `AppHardeningManager`), **SHA-256**: `fc6cc5cec1eb93984bc0cb0842b6d2f9c4bb6549f97617d42f457dc8983b55b4` (identik pada Phone dan Wear).

---

## Ringkasan Verifikasi Build & Pengujian

| Kategori Pemeriksaan | Status | Rincian Hasil Teramati |
|---|---|---|
| Unit Test `:shared` (`:shared:test`) | **PASS** | `45` tes pada `10` berkas XML (`0` gagal, `0` error, `0` dilewati) |
| Unit Test `:inference` (`:inference:testProductionDebugUnitTest`) | **PASS** | `34` tes pada `5` berkas XML (`0` gagal, `0` error, `0` dilewati) |
| Unit Test `:wear` (`:wear:testProductionDebugUnitTest`) | **PASS** | `35` tes pada `13` berkas XML (`0` gagal, `0` error, `0` dilewati) |
| Unit Test `:app` Debug (`:app:testProductionDebugUnitTest`) | **PASS** | `956` tes pada `207` berkas XML (`0` gagal, `0` error, `0` dilewati) |
| Unit Test `:app` Release (`:app:testProductionReleaseUnitTest`) | **PASS** | `956` tes pada `207` berkas XML (`0` gagal, `0` error, `0` dilewati) |
| Lint & Release APK Build (`lintVitalProductionRelease`, `:wear:lintProductionDebug`, `assembleProductionRelease`) | **PASS** | `BUILD SUCCESSFUL`; APK Phone & Watch lolos verifikasi `apksigner` dan `aapt2` |
| Uji Instalasi & Smoke UI Perangkat Fisik (`SM-G988B`, `RRCN3008VYE`, Android 13) | **PASS** | Instalasi preserve-data (`-r -d`) berhasil (`versionCode=9`, `versionName=0.1.8-rc1`), `0` crash `AndroidRuntime:E`, 7 tangkapan layar aktual terverifikasi |
| Kebijakan Publikasi (`check_publication_safety.py`) | **PASS** | `9` unit test kebijakan lulus; pemindaian *worktree* dan seluruh riwayat Git bersih |
| Pengujian Active Stylus fisik (`S Pen`) & Wear OS Live Hardware (Galaxy Watch 5) | **NOT RUN** | Tidak ada perangkat keras Galaxy Watch atau S Pen aktif yang terpasang saat rilis ini; deteksi software tetap diuji lewat unit test |
| Validasi Norma Populasi Eksternal & *Expert Judgment* Psikolog | **BLOCKED** | Memerlukan evaluasi langsung oleh psikolog klinis / peneliti berlisensi sebelum pemakaian riset lanjut |
