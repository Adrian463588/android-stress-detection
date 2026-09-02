# ADR-004: Retrieval Licensing and Corpus Packaging

## Context
The system relies on a local knowledge base (PNPPK, PPDGJ-III, ICD-10/11) to ground AI Counselor Chat and Verbatim Coding. We need to define how this corpus is packaged into the app and ensure licensing compliance.

## Decision
The future approved counseling corpus may be pre-indexed into a local vector
database or search index packaged as read-only assets within the APK. Ingestion
must preserve document provenance and chunk traceability, and every included
text must comply with its distribution license for offline use.

The implemented runtime is explicitly local-first and fail-closed:

```text
APP_RAG → BUNDLED_LOCAL → WEB (explicit consent) → NONE
```

`APP_RAG` is the encrypted user/document knowledge base. `BUNDLED_LOCAL` is
limited to text, Markdown, and JSON files listed by the checksummed
`bundled_knowledge_manifest.json`; the manifest carries version, title,
license, provenance, and source checksum. The current manifest is `EMPTY`
until a real licensed corpus is supplied. Research data, participant metadata,
ECG/PPG, logs, and engineering documents are not valid chatbot sources.

Web grounding is not a replacement for the LiteRT-LM generator. It is allowed
only after consent, sends the sanitized user question without private session
context, accepts HTTPS public URLs through the secure URL policy, and requires
validated citations. If no evidence or citation is valid, the response is
`UNAVAILABLE`; no model answer or citation is fabricated. Source tier,
provenance, checksum, URL/path, and retrieval time are persisted with the chat
message.

## Status
Accepted — runtime contract implemented; licensed bundled corpus pending

## Consequences
- Increases APK size due to pre-packaged vector indices and texts.
- Updates to the knowledge base will require app updates or a secure out-of-band asset download mechanism.
- Strict mapping must be maintained between retrieved chunks and their citation metadata.
