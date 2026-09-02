# ADR-001: LLM Runtime and Quantization

## Context
The Clinical Companion Features require an on-device LLM to support Verbatim AI Analysis and AI Counselor Chat. The PRD specifies Gemma 4 E2B as the foundational model. We need to decide on the runtime engine (e.g., MediaPipe, llama.cpp, ExecuTorch) and the quantization level (e.g., int4, int8) to balance inference latency, memory constraints, and output quality on the reference Android device. All inference must be offline and privacy-preserving.

## Decision
[Pending implementation benchmarking] We will adopt `OnDeviceLlmEngine` as the core interface. The exact runtime and quantization config will be locked only after real-device benchmarking across target devices to ensure P95 latency and memory stability, particularly during concurrent biosignal inference (Live Monitor). Prompts will be stored as versioned assets.

## Status
Proposed / Needs Benchmarking

## Consequences
- Requires a modular design to allow swapping the underlying LLM backend during the evaluation phase.
- Must establish rigorous benchmarking protocols for latency, memory, and thermal load.
