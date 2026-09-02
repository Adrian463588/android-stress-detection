# ADR 005: Unified Jetpack Compose Navigation and Module Boundaries

## Status
Accepted

## Context
As part of the Clinical Companion features extension, we are integrating complex new flows (Verbatim Analysis, Live Monitor, AI Counselor) into the existing app. Previously, navigation was managed via enum states and `AnimatedContent`. Additionally, the codebase resided primarily in a single `:mobile` application module, leading to high coupling and risk of spaghetti code as the scope increases.
Furthermore, earlier iterations of this project experimented with Role-Based Access Control (RBAC), which contradicts the updated product mandate of a single, unified user experience.

## Decision
1. **Lean module boundary:**
   Keep only modules with an independent runtime or reusable contract.
   - `:app` (phone application, navigation, and mobile presentation/domain/data packages)
   - `:wear` (Wear OS application and Samsung sensor acquisition)
   - `:shared` (versioned phone-Watch transport and shared domain contracts)
   - `:inference` (canonical ONNX packaging and parity boundary)

   Mobile Clean Architecture remains enforced through package boundaries inside `:app`.
   Empty feature modules and disconnected legacy implementations are not retained as architectural placeholders.

2. **Navigation System:**
   We will use `androidx.navigation:navigation-compose` to manage the app navigation graph (`NavGraph.kt`) rooted in the `:app` module.
   The navigation graph has exactly 5 product destinations:
   - `HOME_STRESS`
   - `HISTORY`
   - `VERBATIM_PLUS` (including live physiological monitoring)
   - `AI_PLUS`
   - `PROFILE`

3. **Eradication of RBAC:**
   All features are visible to all users. Permissions and privacy are enforced through user consent and standard data privacy practices, not through profession-based enums or role branching.

## Consequences
- **Positive:** Smaller Gradle graph, no mock feature modules, and one production implementation per feature.
- **Trade-off:** Package boundaries inside `:app` require static analysis and review because Gradle cannot enforce every mobile layer dependency.

## Dependencies and Input/Output Contracts
- **Input:** Compose NavController events from Bottom Navigation.
- **Output:** Canonical feature UI content composed by `:app`.
- **Contracts:** UI uses immutable state and UDF events; presentation calls domain abstractions, while Room, ONNX, audio, and Wear Data Layer remain infrastructure boundaries.
