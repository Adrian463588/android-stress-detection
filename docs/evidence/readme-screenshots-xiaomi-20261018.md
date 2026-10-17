# Xiaomi README Screenshot Evidence

## Capture context

- Capture date: 18 October 2026, WIB.
- Phone: Xiaomi POCO X7 Pro, model `2412DPC0AG`, ADB serial `QSWSEMRKNFZ9LJRC`.
- Android: 16 (SDK 36).
- Display: 1220 × 2712 pixels, density 520 dpi.
- Installed app: `com.stressdetector`, versionName `0.1.5`, versionCode `6`.
- Package `lastUpdateTime`: `2026-10-18 00:41:08` device local time.
- Installed Phone APK digest: `073531f002e8f9a41fd600a0629b43916331c0bac0ccb68cff7abafae9f838d3`; this package digest was pulled and matched to the GitHub v0.1.5 asset in the preceding device verification, and no reinstall occurred before these captures.
- Capture method: Android ADB screenshot (`screencap`) after opening each tab in the installed app. PNG files are original captures; they were not redrawn or edited.
- User approved including screenshots that contain AI chat content.

The UI smoke opened Home, History, Verbatim+, AI+, and Profile. AI+ was also opened to the existing web-answer source panel to check that one disclosure control shows both W1 and W2. No stress session, audio recording, import, delete, export, permission change, model operation, or app-data mutation was performed. A Galaxy Watch (`SM_R920`, ADB endpoint `192.168.0.6:44707`) was visible during device inventory but was not used for this UI capture.

## Screenshot manifest

All PNG dimensions are 1220 × 2712.

| Screen | File | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Home | [`home-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/home-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 203630 | `9b3153db9a862e2ef3e58b5652c5df8d0c4e9b8afb1f92c5c0cdc09327868bb9` |
| History | [`history-calendar-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/history-calendar-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 151115 | `cbfb44efefe4157f032226c3264c9a190ffc44c7ce4068b4ada8a06cf1730820` |
| Verbatim+ | [`verbatim-sessions-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/verbatim-sessions-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 144575 | `f2664bc3149871a13b5955fe8688389e64f875411398ff8c1cc63d61f7a36cd5` |
| AI+ conversation | [`ai-plus-chat-top-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/ai-plus-chat-top-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 219564 | `c87384305fb592bccd9ceec2a9064df62f2a641de8a46d7b782343db323ba657` |
| AI+ W1/W2 source panel | [`ai-plus-web-sources-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/ai-plus-web-sources-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 253446 | `d32e09cde84a0a0187c6d6a99e6d485b633e08a3ac6276f8cdc3e6f8e781bac0` |
| Profile | [`profile-xiaomi-2412DPC0AG-v0.1.5-20261018.png`](../screenshots/profile-xiaomi-2412DPC0AG-v0.1.5-20261018.png) | 200248 | `3e9aa6001c16fcb8d93f7206938472ce9006730e110a6e4e22cea4ac079c1e96` |

## Evidence limits

- Home screenshot shows a stored `Stress` result for Green and IR. It does not establish the user's subjective state, model correctness, signal provenance, or clinical validity.
- AI+ screenshots show persisted chat and the UI-rendered W1/W2 references, URLs, excerpts, and app-displayed relevance values. They do not independently validate the web response, the sources, or answer faithfulness.
- Screenshot presence and tab navigation are visual/UI evidence only. They do not demonstrate successful recording, sensor acquisition, inference parity, export, reminders, full Profile operations, or phone–Watch end-to-end behavior.
- This evidence was captured from Phone v0.1.5; it is not a screenshot of the Watch app.
