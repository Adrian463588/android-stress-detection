# ADR-005: Divergence-Threshold Calibration

## Context
The Live Monitor feature reuses the existing validated stress model to compute physiological arousal divergence against a rolling baseline. We need to determine how divergence thresholds are calibrated and stored.

## Decision
Divergence trigger thresholds will be configuration-driven and stored as versioned assets rather than hardcoded logic. The specific threshold values must be backed by calibration data derived from real-world or reference session data to ensure physiological arousal is accurately represented without false positives or deception claims.

## Status
Proposed

## Consequences
- Thresholds can be adjusted via config updates without changing compiled business logic.
- Requires a documented calibration phase with empirical data before final values are locked.
- UI must strictly frame these events as physiological arousal divergence.
