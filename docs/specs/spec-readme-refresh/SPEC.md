---
id: SPEC-readme-refresh
companions:
  - plan.md
  - acceptance.md
sources:
  - https://github.com/Fission-AI/OpenSpec
  - https://github.com/bmad-code-org/BMAD-METHOD
  - https://github.com/cucumber/docs
---

# README Project Refresh

## Why

Pembaca repo kesulitan menemukan APK yang benar dan membedakan fitur yang tersedia dari fitur yang sudah diuji. README yang panjang dan bertumpuk meningkatkan risiko unduhan salah dan klaim end-to-end yang tidak didukung.

## Capabilities

- id: CAP-1
  intent: Pembaca dapat memilih pasangan APK Phone dan Watch terbaru serta memverifikasi identitas artifact.
  success: Tautan menuju asset rilis yang benar; versi, package, dan checksum cocok dengan metadata rilis GitHub.
- id: CAP-2
  intent: Pembaca dapat memahami tujuan, menu, alur pengguna, stack, dan struktur repository.
  success: README menampilkan ringkasan, diagram user flow, fitur, modul, prasyarat build, dan status verifikasi.
- id: CAP-3
  intent: Pembaca dapat membedakan bukti yang lulus dari acceptance yang tertunda atau belum dijalankan.
  success: Setiap screenshot menyebut asal/tanggal/build bila diketahui; fitur tanpa evidence tidak diberi status lulus.

## Constraints

- Gunakan asset APK dan checksum yang sudah diterbitkan; jangan membangun ulang atau mengganti binary sebagai bagian perubahan dokumentasi.
- Screenshot harus asli dan tidak diberi label terbaru jika tanggal/perangkat/build tidak terbukti.
- Status repo, release, branch, device, dan test harus berasal dari pemeriksaan nyata.
- Repo saat ini privat; unduhan membutuhkan akses GitHub.
- README dan acceptance scenarios mengikuti kontrak PRD/AGENTS/DESIGN.

## Non-goals

- Mengubah kode runtime, model, preprocessing, threshold, data pengguna, atau sensor.
- Mengklaim semua fitur lulus end-to-end, clinical validation, atau keberhasilan web RAG tanpa evidence.
- Membuat screenshot tiruan atau mengubah screenshot untuk menyamarkan state aplikasi.

## Success signal

Pengunjung dapat menemukan unduhan Phone/Watch terkini, memahami alur serta batas setiap fitur, dan membuka bukti aktual tanpa harus menebak status acceptance. Semua link lokal valid; kekurangan bukti dinyatakan sebagai tertunda, bukan sukses.
