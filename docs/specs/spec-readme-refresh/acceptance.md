# BDD Acceptance — README Project Refresh

## Scenario: Unduhan terbaru dapat diverifikasi

**Given** repo privat memiliki release prerelease android-v0.1.5

**When** pembaca memilih APK Phone atau Watch dari README

**Then** tautan harus menuju asset release tersebut

**And** versi, package, serta SHA-256 sesuai metadata asset GitHub.

Status: PASS. Digest aset diverifikasi melalui GitHub Release API pada 18 Oktober 2026.

## Scenario: Fitur tidak dipasarkan melebihi bukti

**Given** acceptance suatu fitur belum lengkap

**When** README menjelaskan fitur tersebut

**Then** status harus menyatakan batas atau prasyarat yang belum terpenuhi

**And** “semua fitur lolos end-to-end” tidak boleh diklaim.

Status: PASS. Wear, sensor penuh, web-grounded AI+, Verbatim recording, dan seluruh operasi Profile tetap memiliki gap yang terlihat.

## Scenario: Screenshot historis tidak disebut sebagai tampilan build terbaru

**Given** repo memiliki screenshot historis dari perangkat/build sebelumnya dan screenshot baru Xiaomi v0.1.5

**When** screenshot ditampilkan pada README

**Then** tanggal, perangkat, dan batas versi harus dicantumkan

**And** screenshot historis tidak boleh diberi label sebagai capture v0.1.5.

Status: PASS. Galeri utama memakai lima tab Xiaomi v0.1.5; aset lama disimpan terpisah sebagai arsip.

## Scenario: Screenshot baru diambil dari Xiaomi

**Given** Xiaomi POCO X7 Pro 2412DPC0AG (`QSWSEMRKNFZ9LJRC`) muncul sebagai device pada ADB dan `com.stressdetector` versi 0.1.5/versionCode 6 terpasang

**When** lima tab utama dibuka melalui navigasi dan UI dibaca tanpa memulai sesi/rekaman atau mengubah data

**Then** screenshot dari Home, History, Verbatim+, AI+, dan Profile harus disimpan sebagai PNG tanpa klaim hasil yang tidak diuji.

**And** UI AI+ memperlihatkan satu kontrol untuk membuka daftar sumber W1/W2.

Status: PASS untuk navigasi dan rendering UI pada 18 Oktober 2026. Ini bukan acceptance end-to-end fitur, validitas sumber, akurasi model, atau Watch.

## Scenario: README siap merge

**Given** README dan acceptance artifacts diperbarui

**When** validasi dokumentasi dijalankan

**Then** setiap link lokal/image ada, checksum cocok, Git diff bersih dari whitespace error, dan repo metadata benar.

Status: PASS untuk validasi konten, tautan/aset lokal, checksum rilis, dan metadata repo; Git delivery dicatat terpisah pada commit/PR.
