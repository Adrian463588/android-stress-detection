# Clinical Companion - Manual Test Script

This script is designed for the clinical psychologist to manually verify the core end-to-end functionality of the Stress Detector Companion App on the Dimensity 8400 Ultra physical device.

## Prerequisites
- Physical Android device (preferably Dimensity 8400 Ultra for inference speed).
- WearOS device paired, or Wear connection successfully bypassed for local PPG debug.
- A dummy PDF file saved on the phone (e.g., `clinical_guidelines.pdf`).
- Internet connection (only required for the initial model download step).

---

## 1. Profile Gating & LLM Download (Sub-Agent 1 & 2)

**Goal:** Verify the app enforces model presence and correctly downloads the LiteRT-LM weights.

1. **Launch the App** for the first time.
2. Tap on the **Verbatim** or **Live Monitor** (Unified Session) tab.
   - *Expected:* A Toast appears ("Download the on-device AI model in Profile to use this feature") and you cannot start the session.
3. Tap on the **Profile** tab.
4. Tap the **Download Local AI Model** button.
   - *Expected:* A persistent notification appears indicating download progress.
5. Wait for the download to complete.
   - *Expected:* The Profile screen updates to show "Model Ready" or "Downloaded".
6. **Force Quit** the app and re-open it.
   - *Expected:* The model is still marked as downloaded (verifying local idempotency without re-downloading).

---

## 2. Knowledge Base Ingestion (Sub-Agent 4)

**Goal:** Verify local PDF extraction and RAG ingestion.

1. Navigate to the **Document Library** or Profile settings (wherever the PDF upload button is located).
2. Tap **Upload PDF**.
3. Use the Android file picker to select `clinical_guidelines.pdf`.
4. Wait for the background worker to finish ingestion.
   - *Expected:* A Toast or UI state confirms the PDF was successfully ingested.
5. Navigate to the **AI Counselor** tab.
6. Ask a question directly related to the PDF contents (e.g., "What are the first-line treatments mentioned for anxiety?").
   - *Expected:* The Gemma 4 E2B model responds accurately and ideally cites the PDF, functioning entirely offline (turn on Airplane mode to be certain).

---

## 3. Unified Session & Transcription (Sub-Agent 3)

**Goal:** Verify simultaneous audio diarization and PPG stress inference.

1. Ensure **Airplane mode** is turned ON to prove offline capabilities.
2. Navigate to the **Session** tab.
3. Tap **Start Session**.
   - *Expected:* The app requests Microphone recording permissions (if not already granted).
4. Have a short 30-second back-and-forth conversation with another person.
   - *Expected:* The screen displays live transcribed text with Speaker 1 / Speaker 2 labels.
5. Watch the PPG Divergence UI (the sliding window stress timeline).
   - *Expected:* The divergence events populate in real-time alongside the conversation text.
6. Tap **Stop Session**.
   - *Expected:* The session is saved to a local timestamped folder (e.g., `2026-07-28_1030/audio.wav`).

---

## 4. Final Review
If all above steps succeed without crashes, the Remediation PRD Addendum v4.0 is fully verified as production-ready.
