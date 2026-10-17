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

## Scenario: Screenshot lama tidak disebut sebagai tampilan build terbaru

**Given** screenshot Home/History/AI+ Xiaomi yang tersedia berasal dari Oktober 3–5 dan build sebelum v0.1.5; screenshot Verbatim berasal dari Samsung pada Oktober 6

**When** screenshot ditampilkan pada README

**Then** tanggal, perangkat, dan batas versi harus dicantumkan

**And** screenshot Verbatim dari Samsung tidak boleh diberi label Xiaomi.

Status: PASS untuk caption historis; tanggal/build Profile tetap ditandai tidak terverifikasi.

## Scenario: Screenshot baru diambil dari Xiaomi

**Given** Xiaomi 2412DPC0AG muncul sebagai device pada ADB

**When** UI v0.1.5 dibuka dan menu smoke test dijalankan

**Then** screenshot dari Home, History, Verbatim+, AI+, dan Profile harus disimpan sebagai PNG tanpa klaim hasil yang tidak diuji.

Status: BLOCKED. Xiaomi QSWSEMRKNFZ9LJRC terlepas dari ADB sebelum screenshot dan navigasi; hanya Samsung RRCN3008VYE yang masih terlihat.

## Scenario: README siap merge

**Given** README dan acceptance artifacts diperbarui

**When** validasi dokumentasi dijalankan

**Then** setiap link lokal/image ada, checksum cocok, Git diff bersih dari whitespace error, dan repo metadata benar.

Status: PASS untuk validasi konten, tautan/aset lokal, checksum rilis, dan metadata repo; Git delivery dicatat terpisah pada commit/PR.
