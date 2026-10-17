# Review resolution — Verbatim+ audio evidence

Three independent BMAD review roles received tracked and untracked changes:
blind adversarial review, path/edge-case review, and spec acceptance audit.
Findings were deduplicated as local implementation patches; no frozen product
intent or model contract was changed. A targeted post-fix boundary review follows.

The targeted review found two remaining patches: preserve quarantine when a
decoder entry is missing, and record failed cleanup of already published trace
chunks. Both now have focused regressions. Session deletion also recognizes only
the exact owned UUID staging folders, not similarly named neighboring archives.

| Changed boundary | Resolution / regression |
| --- | --- |
| Optional trace blocks core archive | Core WAV/transcript/native evidence commits first; unavailable trace manifest and warnings are explicit. `VerbatimSessionFinalizerTest`. |
| Failed decoder restore enables cues | Typed `recognitionEvidenceUnavailableReason` keeps unedited text quarantined. `VerbatimArtifactServiceTest`. |
| Decoder attached only by ID | Validate immutable raw text, PCM bounds and session before binding; retain Room edits. Artifact-service regressions. |
| Overdense archive allocates before rejection | Validate metadata count before chunk reads; require complete non-final chunks and positive-duration observations. `VerbatimAudioSignalTraceTest`. |
| Corrupt page traps navigation | Retain validated manifest on payload failure and permit return to healthy pages. Trace tests. |
| Missing analysis tail hides later review | Persist known pause-aware capture duration and allow empty later pages; never invent observations. Trace tests. |
| `finish()` cannot retry | Set terminal flag only after successful final chunk/manifest persistence. Transient-failure regression. |
| Cleanup failures silently drop context | Keep committed artifacts, expose warnings with exact retry paths, and persist scoped `artifact_warnings.json.enc`. Finalizer/portable-graph tests. |
| Folderless failed-session deletion | Validate session ownership and clean capture prefix before deleting rows. Artifact-service tests. |
| Archive transcript lacks session identity | Repository mapping carries sessionId, so archived chart cues remain session-owned. Repository regression. |
| Empty-final TXT unreachable | One `hasFinalReviewEvidence` policy serves renderer, workspace and export preparation. Workspace/renderer tests. |
| Unbound audio origin presented as zero | Nullable measured origin; pre-capture notes cannot pass readiness using zero fallback. SessionTimeline tests. |
| Listen label differs from seek target | Display the actual playback target; keep marker's evidence range separate. Compiled Compose regression. |
| Version download history ambiguous | Exact preview APK/release/checksum and historical links are verified at publication; no fabricated screenshot. |

The storage/provider catches are deliberately scoped to their trust boundary,
return explicit unavailable/warning context, and do not replace core evidence.
No global lint baseline or blanket error-control suppression was introduced.
No new runtime dependency, Room migration, second microphone, model threshold,
PPG fusion or cloud inference was added. Physical acceptance is not inferred.
