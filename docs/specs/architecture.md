# Architecture Spec

## Goal
Production-grade Wear + Phone stress detection app using MVVM, Jetpack Compose, Room, ONNX Runtime, and Samsung Health Sensor SDK 1.4.1.

## Modules
- `shared`: stable DTOs, session constants, message paths, codecs.
- `wear`: Samsung PPG acquisition and session control.
- `mobile`: inference, persistence, report UI.

## Data Flow
1. Phone sends `/session/start` with a generated session ID.
2. Watch records PPG Green and IR for 5 minutes and sends batches with session lineage.
3. Phone keeps rolling buffers, performs final ONNX inference when the 5-minute session completes, and stores one Room record.
4. Report screen queries Room aggregates.

## Constraints
- Green model is primary; IR is secondary/diagnostic.
- App is wellness/research support only, not medical diagnosis.
- `android/legacy/` is archived and not modified.
