# ADR-007: Exceptions to Shared-Component Reuse

## Context
The PRD mandates strict reuse of shared components (`AudioCaptureEngine`, `SpeechToTextEngine`, `OnDeviceLlmEngine`, etc.) to enforce DRY and SOLID principles. No feature may fork duplicate implementations without explicit approval.

## Decision
Any required fork or deviation from the shared abstractions must be documented in a separate, specific ADR. As of this baseline, no exceptions are approved. All features (Verbatim Analysis, Live Monitor, AI Chat) must utilize the centralized infrastructure layer.

## Status
Active / Baseline

## Consequences
- Enforces strict architectural boundaries and prevents spaghetti code.
- May require slightly more complex interface design upfront to accommodate varying feature needs without code duplication.
