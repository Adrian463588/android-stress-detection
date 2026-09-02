# Android static-analysis gate

The active Gradle release path is `shared`, `inference`, `app`, and `wear`.
The supporting `core`, `domain`, `data`, and `feature-*` modules are compiled as
dependencies of `app`; there is no `:mobile` module.

Compilation and active-module unit tests are blocking CI checks. Ktlint and Detekt remain
a release blocker until their configuration is introduced without applying a broad,
unreviewable rewrite to the current Kotlin sources. The enabling change must:

1. pin `org.jlleitschuh.gradle.ktlint` and `io.gitlab.arturbosch.detekt` plugin versions;
2. apply both tools only to the four active modules and exclude generated/build trees;
3. check in reviewed configuration and any baseline with an owner and removal issue;
4. add `ktlintCheck` and `detekt` to the blocking `android-host` CI job; and
5. never use `ignoreFailures`, warning-only mode, or the legacy `android/app` tree to claim a pass.

Until those five conditions are met, release evidence must report Android static analysis as
`pending`; compile/unit-test success alone does not satisfy the release gate.
