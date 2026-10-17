# Pinned Sherpa-ONNX runtime (not vendored)

- Artifact: `sherpa-onnx-static-link-onnxruntime-1.13.4.aar`
- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4
- SHA-256: `dc5ac19a28dee3bffc5e5a5d50cb6afa977703fc4a7ee535a308506990fdd295`
- License: Apache-2.0 (upstream project)

This release statically links ONNX Runtime for the supported Android ABIs
(`armeabi-v7a`, `arm64-v8a`, and `x86_64`). The app keeps Microsoft ONNX
Runtime Mobile 1.22.0 as the sole packaged `libonnxruntime.so` for stress
inference. The Gradle native-runtime contract fails the build if that invariant
is broken. Speech model weights are not packaged in the APK; they are downloaded
only after an explicit Profile action and activated after checksum validation.

Gradle downloads this exact asset into the ignored `app/build/third-party/`
directory and validates the pinned byte count and SHA-256 before compilation.
Do not add the archive to Git or a repository release asset.
