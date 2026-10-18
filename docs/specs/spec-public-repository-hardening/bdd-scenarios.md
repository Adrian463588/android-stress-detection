# BDD acceptance scenarios

## Credential and participant-data boundary

```gherkin
Feature: Keep publication artifacts free of secrets and participant identity

  Scenario: Full history contains no credentials
    Given the repository branches and tags are available to the scanner
    When the redacted secret scan examines all reachable commits
    Then it reports zero credential findings
    And its output never includes a credential value

  Scenario: No raw participant identity or data is tracked
    Given repository policy and source history are inspected
    When tracked paths and participant-related identity fields are checked
    Then no raw participant dataset or identifying name field is present
    And research references use approved pseudonymous identifiers only
```

## Dependency and release hardening

```gherkin
Feature: Build without vendored AARs and harden release variants

  Scenario: Sherpa dependency is acquired from its pinned upstream release
    Given the local build cache does not contain the Sherpa AAR
    When a Phone build requires the speech runtime
    Then Gradle downloads only the configured HTTPS upstream artifact
    And verifies the pinned SHA-256 before compilation uses it
    And fails closed on a checksum mismatch or incomplete download

  Scenario: The exact legacy Sherpa archive remains in history
    Given the reachable history contains the approved Sherpa path and SHA-256
    When the publication policy scans all refs
    Then that historical object is accepted without rewriting Git history
    And the same AAR remains forbidden in the current worktree

  Scenario: Samsung or any other AAR is found
    Given an AAR is not the exact approved historical Sherpa object
    When the worktree or history policy scans it
    Then publication safety fails with a redacted finding

  Scenario: The same blob appears under an unapproved path
    Given the approved Sherpa blob is also reachable under another AAR path
    When the history scanner enumerates each distinct commit tree
    Then the approved path remains allowed
    And the unapproved alias path fails publication safety

  Scenario: Release variants are optimized and non-debuggable
    Given Phone and Wear release build types are configured
    When the release APKs are assembled
    Then R8 minification and resource shrinking are enabled
    And the packaged applications are not debuggable
    And no signing secret or keystore is stored in the repository
```

## Regression guard

```gherkin
Feature: Prevent future publication regressions

  Scenario: Pull request introduces a forbidden binary
    Given the repository security workflow runs with read-only permissions
    When a change adds any current-tree AAR, signing key, or raw data artifact
    Then the publication-safety check fails before merge

  Scenario: Pull request introduces a credential
    Given the repository security workflow scans the complete available history
    When a credential pattern is detected
    Then the workflow fails with redacted diagnostics
```
