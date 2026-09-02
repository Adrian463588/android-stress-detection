# ADR 004: Clean Architecture Base Packages

## Status
Accepted

## Context
To ensure SOLID and DRY principles are maintained across the application, especially with the introduction of new clinical companion features (Verbatim Analysis, Live Monitor, AI Counselor), we need strict module boundaries. We must prevent UI components from carrying business logic and avoid duplicated data/domain models.

## Decision
We establish Clean Architecture base packages within the app module:
- `com.stress.app.presentation`: Contains all Jetpack Compose UI, Navigation, and ViewModels.
- `com.stress.app.domain`: Contains business rules, use cases, and domain contracts (e.g., `AudioDomainContracts`, `InferenceDomainContracts`).
- `com.stress.app.data`: Contains data sources, repositories implementations, and local/remote models.

## Consequences
- **Positive:** Clear separation of concerns. UI is decoupled from business logic and data access. Easier to test ViewModels and Domain logic in isolation.
- **Negative:** Slightly more boilerplate required to map models between Data, Domain, and Presentation layers.
