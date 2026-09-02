# ADR-006: Window and Step Configuration Changes

## Context
Continuous live monitoring requires running the existing batch stress model in a real-time serving capacity using a rolling window buffer of PPG signals.

## Decision
We will define a fixed rolling window size and step interval that satisfies the model's expected input dimensions. To prevent UI blocking and ensure smooth monitoring, P95 inference latency must stay below half of the step interval. Invalid windows will be explicitly dropped and will not generate predictions or annotations.

## Status
Proposed

## Consequences
- Requires robust signal quality gating before passing windows to the inference engine.
- Shared `RealtimeInferenceEngine` must efficiently manage the sliding window without memory leaks over sessions lasting 60+ minutes.
