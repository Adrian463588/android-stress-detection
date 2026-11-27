# Third-Party Notices & Attributions

This document lists third-party software libraries, native runtimes, AI models, SDKs, and assessment materials referenced or used by **Stress Detector — Clinical Psychological Companion**. Original project code (`Copyright (c) 2026 Adrian Syah Abidin`) is governed separately by [`LICENSE`](LICENSE). Nothing in `LICENSE` modifies or restricts upstream rights granted under open-source licenses for independent third-party libraries.

---

## 1. Open-Source Android, Kotlin & JVM Libraries

| Component | Version | License | Role |
|---|---|---|---|
| Kotlin & `kotlinx-coroutines` / `kotlinx-datetime` | `2.3.0` / `1.7.3` / `0.4.1` | Apache-2.0 | Language runtime, concurrency, UTC timestamps |
| AndroidX (`activity-compose`, `core-ktx`, `lifecycle`, `work-runtime-ktx`, `security-crypto`, `room`) | Pinned in `build.gradle` (`Room 2.7.2`) | Apache-2.0 | UI lifecycle, offline WorkManager scheduling, Room persistence, EncryptedSharedPreferences/Keystore |
| Jetpack Compose (`ui`, `material3`, `material-icons-extended`, `navigation-compose`, `wear-compose`) | `1.9.0` / `1.1.2` / `2.7.6` | Apache-2.0 | Declarative Phone & Wear OS user interfaces |
| Microsoft ONNX Runtime (`onnxruntime-android`, JVM `onnxruntime`) | `1.22.0` | MIT | On-device inference for PPG stress classifiers |
| Sherpa-ONNX (`sherpa-onnx-static-link-onnxruntime`) | `1.13.4` | Apache-2.0 | Local offline speech recognition & VAD (`Verbatim+`) |
| Google LiteRT-LM (`litertlm-android`) | `0.13.1` | Apache-2.0 | On-device LLM & multimodal vision inference wrapper |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) & AndroidX SQLite (`2.6.2`) | `4.17.0` | BSD-style (Zetetic) | AES-encrypted local Room databases (`StressDatabase`, `KnowledgeBaseDatabase`) |
| PDFBox-Android (`com.tom-roush:pdfbox-android`) | `2.0.27.0` | Apache-2.0 | Local PDF parsing for offline counseling knowledge documents |
| OkHttp (`4.12.0`), Gson (`2.11.0`), Apache Commons Compress (`1.28.0`), `javax.inject` (`1`) | Pinned | Apache-2.0 | Transport/serialization/SAF archive packaging |
| Ksoup (`com.fleeksoft.ksoup`) | `0.2.6` | MIT | HTML/markup sanitization utilities |
| JUnit (`4.13.2`), Mockito (`5.3.1`), AndroidX Test (`junit`, `espresso-core`) | Pinned | EPL-1.0 / MIT / Apache-2.0 | Automated unit and instrumentation testing |

---

## 2. Research-Licensed & Proprietary Native/SDK Components

1. **openSMILE (`v3.0.2`, audEERING GmbH)**
   - **License**: audEERING Research License (**non-commercial research only**). Commercial distribution or commercial product integration requires a separate commercial license from audEERING GmbH (`https://www.audeering.com/`).
   - **Packaging**: Fetched & SHA-256 verified during Gradle build (`prepareOpenSmile`); `LICENSE`, `CITING`, and upstream `licenses/` notices are packaged inside the app assets in accordance with upstream notice requirements.
   - **Citation request**: Refer to `openSMILE` `CITING` for academic publications utilizing eGeMAPS/GeMAPS acoustic prosody extraction.

2. **Samsung Health Sensor SDK (`1.4.1`)**
   - **License**: Proprietary Samsung Health Sensor SDK License Agreement.
   - **Packaging**: Binary `.aar` files are excluded from source control (`*.aar` in `.gitignore`) and must be obtained directly from Samsung Developer by authorized developers building `:wear`. Raw proprietary SDK binaries are not redistributed in the public source repository.

3. **Google Play Services Wearable (`18.1.0`) & Google ML Kit Text Recognition (`16.0.1`)**
   - **License**: Google APIs Terms of Service / Google ML Kit Terms.

4. **User-Imported Local LLM Weights (e.g., Google Gemma 4 E2B `gemma-4-E2B-it.litertlm`)**
   - **License**: Gemma Terms of Use (`https://ai.google.dev/gemma/terms`). Model weights are NOT bundled in the repository or APK; users/researchers must import verified weights locally on-device via Storage Access Framework (SAF).

---

## 3. Psychometric Assessment Instruments & Clinical Materials

The application catalog references **40 psychological assessment instruments** across cognitive, personality, work-style, vocational-interest, mental-health screening, and projective modalities:
- **Public-domain / open-access research instruments** (e.g., IPIP-BFM-50, WHO SRQ-29, Copenhagen Burnout Inventory [CBI], O*NET Interest Profiler 60) remain attributable to their original academic authors/institutions.
- **Copyrighted / publisher-controlled psychometric tests, manuals, norms, and stimulus cards** (including `MMPI`, `Rorschach`, `Wartegg`, `CFIT`, `IST`, `SPM`, `WPT`, `EPPS`, `16PF`, `MBTI`, `PAPI Kostick`, `Kuder`, `Army Alpha`, `FRT`, `RMIB`, `HTP`, `DAP`, `Baum`, `TIU 5/6`, `Pauli`, `Kraepelin`, `MSDT`, `MSAI`, `DISC`, `AQ-15`, etc.) belong exclusively to their respective authors, publishers, or institutional copyright holders. Inclusion of structural protocols, demonstration prompts, or educational documentation in this research prototype does **NOT** transfer copyright or authorize commercial reproduction or unsupervised clinical use. Researchers and institutions remain solely responsible for obtaining necessary test-publisher permissions before conducting formal clinical or commercial testing.
