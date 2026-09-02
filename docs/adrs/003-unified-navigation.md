# ADR 003: Unified Navigation and RBAC Removal

## Status
Accepted

## Context
The clinical companion features (Verbatim Analysis, Live Monitor, AI Counselor) were originally scoped with potential clinician vs. patient roles. However, managing separate user roles (RBAC) increases architecture complexity, risk of data leakage, and duplicates UI flows. A unified experience is required to comply with PRD-Clinical-Features-Clean.md.

## Decision
We will remove all role-based feature gating (RBAC). The application will use a single unified bottom navigation bar containing 6 tabs:
1. Home/Stress
2. History
3. Verbatim Analysis
4. Live Monitor
5. AI Counselor
6. Profile

The navigation is implemented using Jetpack Compose `NavHost` in a statically configured `NavGraph.kt`. All features are accessible to any authenticated user.

## Consequences
- **Positive:** Simplifies UI and navigation logic. Single source of truth for routing. Reduces testing surface area for roles.
- **Negative:** We must strictly rely on data isolation/encryption per user to handle privacy, rather than hiding features behind a role.
