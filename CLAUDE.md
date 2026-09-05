# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Filmax** is an unofficial kino.watch client for Android TV written in 100% Kotlin + Jetpack Compose. The app is optimized for 10-foot D-pad navigation on television screens.

## Quick Start

### Build and run
```bash
# Debug APK
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Lint
./gradlew detekt

# Test core logic
./gradlew :core:domain:testDebugUnitTest
```

### Requirements
- JDK 17 (required)
- Android SDK 35 (compileSdk)
- minSdk: 26, targetSdk: 35

### Local configuration
All config files are in `.gitignore` — create them locally if needed:
- `local.properties`: TMDB API key, demo OAuth tokens
- `keystore.properties`: Release signing credentials

## Architecture

### Modular structure with dependency flow

```
app/                          # TV app entry point
│
core/                         # Shared cross-cutting concerns
├─ domain/                    # Models, interfaces, use cases (KMP module; iOS/tvOS targets
│                                configured but unused — no iOS app consumes them anymore)
├─ network/                   # Ktor client, OAuth, token refresh
├─ presentation/               # MVI: BaseScreenModel — used by every feature/*/common ScreenModel
├─ designsystem/               # Material3 tokens (Color/Shape/Type) — pulled in transitively by core:ui
├─ tv-designsystem/            # TV theme, focus-aware components (TvPosterCard, TvFocusCard, …)
└─ ui/                         # Shared Composables actually used by TV screens (PosterImage,
                                 HeroBackdrop, KeepScreenOn, VoiceSearch, FilmaxCards helpers, …)

data/                         # Repository implementations + DTO + mappers
└─ auth, catalog, search, user, watching, tmdb

feature/                      # TV features
├─ onboarding/
│  ├─ common/                 # ScreenModel + routes
│  └─ tv/                     # TV UI
├─ home, search, collections, library, profile, details, player  # Common + TV
```

Dependency rule: `app` → `feature:tv` → `feature:common` → `core:ui`/`core:presentation`/`data` → `core:domain`.

Despite the names, `core:ui`, `core:presentation`, and `core:designsystem` are **not** mobile-only
leftovers — TV screens import components from them directly (e.g. `PosterImage`, `continueMeta`,
`FilmaxVersionLabel`), and every `ScreenModel` extends `core:presentation`'s `BaseScreenModel`.
`core:designsystem` is pulled in only as a transitive dependency of `core:ui` (default parameter
values like `ShapePoster`). Don't remove these without checking usages in `feature/*/tv` first.

### Navigation (TV)

TV navigation uses callback-based approach with D-pad focus management. Screens emit navigation callbacks.

### Data flow (MVI pattern)

```
Composable (collectAsState / dispatch(Event))
  ↓
ScreenModel (State + one-shot SideEffect)
  ↓
Repository (RequestResult<T>: Success / Error)
  ↓
Network (Ktor Client)
```

## Key Patterns & Gotchas

### Continuation (watch progress)

`/api/v1/history` is the source of truth. Logic in `core:domain/watching/model/Continuation.kt`:
- Series identified by `(season, number)` pair, not position in tracklist
- Finals with `< 90s` remaining count as watched
- Use `calculateContinuation()` + `ContinuationResolver`
- Pass **only** `Continuation.isActualContinuation` to UI
- `PlayerRoute.resumePositionSeconds` gets the saved position only from actual continuations
- Normal series launch from details = zero position, starts from beginning

### Build configuration

- **Convention plugins**: `build-logic/` (reusable, applied to feature modules)
- **Firebase/Crashlytics**: Optional, requires `google-services.json`; build succeeds without it
- **Version name**: Git tag (e.g., `v1.2.3` → `1.2.3`)
- **Version code**: Commit count (montonically increasing)
- **In-app updates**: Reads GitHub Releases from `remote.origin.url`; forks auto-detect

### Signing and CI

- **Local signing**: `keystore.properties` + `Taskfile.yaml generate:secrets` helper
- **Release workflow** (`android-release.yml`): Tag `vX.Y.Z` → signed APK + changelog + GitHub Release + Telegram
- **Debug workflow** (`android-build.yml`): Every push → artifacts + Telegram

## Common Tasks

### Add a TV screen
1. Create `:feature:screenname:common` with `ScreenModel`
2. Create `:feature:screenname:tv` with TV-optimized `Screen` + focus handling

### Debug state
Extend `ScreenModel` state, emit events, check in `collectAsState`. Use `RequestResult.Error` for failures.

### Lint / check detekt
```bash
./gradlew detekt
```

## Testing

- **Core logic**: `./gradlew :core:domain:testDebugUnitTest`

## Debugging

- **Network traffic**: Chucker inspector (debug builds)
- **Logs**: `logcat` from device
- **State dumps**: Print `ScreenModel.uiState` via log or debugger

## References

- **TV UI specs**: `docs/TV.md`
- **Refactoring roadmap**: `docs/REFACTORING_PLAN.md`
- **Changelog**: `CHANGELOG.md`
