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
                                 HeroBackdrop, KeepScreenOn, VoiceSearch, continueMeta/durationLabel, …)

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

### Typed contracts (no "text contracts")

Anything that crosses a module boundary is a type, never a string that both sides must spell the
same way. The compiler (exhaustive `when`, distinct types) or a unit test must catch a mismatch:

- **Errors**: `safeRequest` resolves the failure kind once into `RequestResult.Error.kind: AppError`
  (typed by `KtorErrorClassifier` from the exception class / HTTP status; text heuristics are only a
  fallback in `AppError.resolveHeuristically`). Presentation branches on `kind`, never on `message`.
- **Enums instead of API strings**: `ItemType`, `WatchStatus` (`watching.status` -1/0/1),
  `WatchingListType` (`watching/movies|serials`), `PosterSize` (image cache key suffix),
  `ItemsShortcut` (`items/hot|new`). The raw value lives in one `apiValue`/`key` property and is
  only touched in the mapper or the API class.
- **Playback**: `TrackLanguage` (ISO codes ↔ display ↔ short code), `AudioPreference`,
  `SubtitlePreference`, `QualityPreference` (`Auto` is a case, not a magic label), `PresetSelection`.
  Persisted per-title keys are value classes `VoiceKey` / `SubtitleKey`; only
  `SubtitleSelection.parse` in `feature:player:common` knows their format.
- **Events carry values, not labels**: `PlayerEvent.SelectAudio(option)`, `SelectPreset(preset?)`,
  `ProfileEvent.SetQuality(QualityPreference)` … The TV menu (`PlayerActions`) hands out typed
  `PlayerChoice`s built in `PlayerChoices.kt`; UI code never parses a label back into meaning.
- **Storage keys** (`TokenStorage.PREFERENCES_NAME`, playback keys) are declared next to the code
  that reads them; nothing else spells them.
- **Lint**: `ElseCaseInsteadOfExhaustiveWhen` and `UnsafeCast` are on (type-resolution tasks
  `detektDebug` / `detektAndroidDebug`). Adding an enum case or a sealed subtype must break every
  `when` that has to handle it. Pure mapping logic gets a unit test next to it
  (`PlayerChoicesTest`, `SubtitleSelectionTest`, `ImageCacheKeysTest`).

### Build configuration

- **Convention plugins**: `build-logic/` (reusable, applied to feature modules)
- **Firebase/Crashlytics**: Optional, requires `google-services.json`; build succeeds without it
- **Version name**: Git tag (e.g., `v1.2.3` → `1.2.3`)
- **Version code**: Commit count (montonically increasing)
- **In-app updates**: Reads GitHub Releases from `remote.origin.url`; forks auto-detect

### Signing and CI

- **Local signing**: `keystore.properties` + `Taskfile.yaml generate:secrets` helper
- **Release workflow** (`release.yml`): Tag `vX.Y.Z` → signed APK + changelog + GitHub Release

## Domain knowledge (extracted from former inline comments)

These facts are not derivable from the code and the kino.watch API is undocumented. Keep this
section current when you learn something new about the backend or the platform.

### kino.watch API quirks

- **Pagination**: `pagination.total` is the number of *pages*, not items; pages are 1-based;
  `perpage` is the page size. Pages of any list can overlap (same id on two pages) and search can
  return one id twice (actor in several roles). Every list that feeds a lazy container keyed by id
  must be `distinctBy { it.id }`, otherwise Compose crashes with "Key … was already used".
- **Catalog**: `api/v1/items` requires `type`; "All" is a union of concrete types (`ItemType.TV`
  is excluded). There is no `anime` type: anime is genre `25` over movie+serial, and the API takes a
  single `genre` param, so "anime + another genre" is intersected locally. `api/v1/genres` returns
  genres of every section including music ("Blues", "Chillout") — filter by `Genre.type`.
  Home rows mirror the server `home_blocks` config (kpapp.link/config.json): cartoons = genre 23,
  stand-up = genre 101. Sort: `sort=-field` is DESC, `sort=field` is ASC (verified live). 4K-only
  filter is `quality=4` (2160p). Range filters go as repeated `conditions[]=year>=2020`; the PHP
  backend decodes `%5B%5D`. `finished=1` only completed series, `0` only ongoing, absent = any.
  Only two shortcuts exist: `items/hot` and `items/new`.
- **Ratings and counters**: `0` means "no rating"/"no data", never a real zero (imdb, kinopoisk,
  `views`, `quality`). `quality` is the frame height in px (2160/1080/720/480 → 4K/FHD/HD/SD).
  `advert = true` means the video carries ads. `imdb` is a numeric IMDb id (pad to 7 digits and
  prefix `tt` for TMDB); TMDB `credits` has no `crew`, so directors never get TMDB photos.
- **Title payloads**: movies carry `videos[]`, series carry `seasons[].episodes[]`. List endpoints
  (home rows, search, similar, collections) return titles WITHOUT `videos`/`seasons`; such a
  preview must never overwrite a full `items/{id}` in the cache, and playback must `forceRefresh`.
  Season number `0` = series without seasons. Episode `thumbnail` is often empty. Subtitle `url`
  can be null (drop the track). Titles can be multi-line ("Name / Original Title / …").
  `posters.wide` is `null` when absent, never `""` (screens rely on `wide ?: big`). The server
  always returns a poster URL even when the file is a 404 (e.g. collection 967).
- **Progress**: `watching/marktime?video=` takes the video **number** (`MediaTrack.number`), not
  the track id, and the number is unique only within a season, so `season` is mandatory for
  series. `marktime` does NOT set `watching.status`; the reference client marks an episode watched
  with `watching/toggle?status=1` (idempotent, omit `season` for movies). `watching.status` is
  -1/0/1 (`WatchStatus`). `/history` is per *episode*, newest first, page = raw episode entries:
  a marathon of one show fills the first page, so paginate (`perpage=100`) until enough distinct
  titles. `watching/{type}` accepts only `movies` or `serials` (`all` silently returns nothing),
  has no timecode, and `subscribed` is ignored for movies but splits series into subscribed /
  unsubscribed — query both. Continue-watching is the intersection of `/history` with
  `watching/{type}` (reference client `getAwaitItems`); the reference client resumes the first
  episode with `status <= 0`. `/history` and `watching/*` entries lack genres/ratings/trailer and
  need background enrichment. The last 90 s of any episode count as finished.
- **Watchlist vs bookmarks**: `watching/togglewatchlist` only toggles and returns `in_watchlist`,
  never a list, and it adds to "watching" only for series (the button is hidden for movies). The
  app's "Буду смотреть" list is a dedicated bookmark folder found by title. The server can store
  duplicate `(folderId, itemId)` links; `removeFromBookmark` removes all copies, so dedupe by
  remove + single add. `bookmarks/get-item-folders?item=` lists a title's folders; a 200 without
  the `folders` key is an unknown shape and must be treated as an error (fallback: page scan).
- **User/device**: `api/v1/user` has no `id`; the subscription may be nested in `user` or top
  level; `end_time` is unix seconds and `days` is fractional. All timestamps are seconds.
  `device/info` and `device/settings` answer 500 (Sep 2026) — the device-settings screen is kept
  in the graph but not reachable from Profile. `streaming_type` is numeric; the API never lists
  server locations (only `0` = auto is known). Send `device/notify` after login, best effort.
- **Auth**: OAuth device flow polls every ~5 s and gets `400 authorization_pending` until the user
  confirms — not a failure, so it bypasses `safeRequest` and telemetry. OAuth secrets travel in
  query params and JSON bodies; the HTTP logger masks them. Refresh: 4xx (`invalid_grant`) means
  logout, transient errors keep the session. Any 401 on a fresh device login may just mean the
  token cache was empty at startup.
- **Hosts and CDNs**: primary API host is `smarttvcdn`, mirrors are health-checked with an
  endpoint that answers 401 without a token (v1 wrongly accepted 404). Automatic failover is not
  persisted across restarts; manual selection is. Stream variants `hls4 → hls → http` sit on
  different CDN hosts and some are DPI/SNI-blocked, so a source error triggers the next variant.
  CDN links live well over 45 s (the speculative-prefetch adoption window). Trailer URLs are
  temporary `.m3u8` with an expiring token. HLS `NAME` carries only a language code; real audio
  metadata is `audios[]` (`index` 1-based = manifest order, `voiceType`, `voiceAuthor`), and the
  original track has an empty `lang`. Actor photos: no people API; the reference client guesses
  `md5(utf8(name)).jpg` on the CDN, which may 404. The image proxy worker (`cf/kwip`) mirrors the
  CDN rewrite of kpapp.link.

### Platform and Compose gotchas (Android TV)

- The player is the only screen where D-pad does NOT move focus: arrows are transport, cursors
  are emulated by index, and unknown keys must return `false` or volume/system keys get eaten.
- Recreate a per-season `LazyRow`/`LazyColumn` with `key(season)`; sharing one `LazyListState`
  across seasons crashed real boxes with "Place was called on a node which was placed already".
- Focus: `focusRestorer` must be followed by `focusGroup`; request focus from `onPlaced`, never by
  frame retries; `focusProperties` on a container do not propagate to children; return-focus keys
  are `row:id` because one title appears in several rows; `rememberSaveable` (not `remember`) for
  anything that must survive a trip to the player. Restorer state does not survive leaving a
  screen, so `TvScreenFocus` remembers the key itself.
- Read animated alpha and focus state in the draw phase (`graphicsLayer`/`drawWithContent`), not
  in composition; shimmer and marquee only on the focused item — dozens of animations drop FPS.
- `Modifier.verticalScroll` clips horizontally, so wide settings rows cannot use the 1.08 focus
  scale; use a border + background change instead. `requiredSize` for circles that must not
  squash; `width(IntrinsicSize.Max)` for the tab underline; `matchParentSize` inside lazy lists.
- Declare the app-level `BackHandler` AFTER `NavHost` (Compose gives Back to the latest
  registration; otherwise fast presses race the predictive-back pop).
- Gradients: never interpolate to `Color.Transparent` (RGB goes through black); use the surface
  color with alpha 0 and extra stops to avoid banding on 8-bit panels.
- `viewModelScope` is closed BEFORE `onCleared()`, so the final progress save on exit is sent
  from a Compose `DisposableEffect`, not from the ViewModel.
- Media3: `setPreferredAudioLanguage` is only a weak hint; the real selection is a
  `TrackSelectionOverride` per audio group after `onTracksChanged`. `duration` can be
  `TIME_UNSET` (negative); HLS position can exceed the declared duration.
- TV boxes have small heaps: Coil memory cache is 15% (default 25% OOMed), backdrops are
  prefetched only for hero/continue rows, the title cache is SQLite (`SharedPreferences` grew
  without bound and parsed on the main thread). `kotlin.synchronized` is unavailable in
  `commonMain`; `NetworkStats` accepts a benign race.
- Speech recognition needs the `<queries>` package-visibility entry on Android 11+.

### Architecture conventions

- Holders outside DI (`ErrorReporting`, `ErrorClassification`, `ImageDiscovery`, `ItemDiscovery`,
  `ImagePrefetchThrottle`, `NetworkStats`, `ConnectionFailures`, `DataInvalidation`) exist because
  mappers and `safeRequest` run without a Koin graph; their implementations are `createdAtStart`.
- `LastValueCache<T>` singletons need a Koin qualifier (type erasure). Background warmup writes
  them only via `putIfAbsent`; screens are the source of truth.
- Background queues (title details, images) are strictly sequential, drop-newest, and pause for
  `PerformanceTuning.BackgroundThrottle.COOLDOWN_MS` after any foreground request or while video
  plays. All tuning numbers live in `PerformanceTuning`.
- `safeRequest` runs its block on `Dispatchers.IO`; the only network path outside it is the TMDB
  client (explicit `withContext(IO)`).
- Speculative prefetch: focusing "Play" and the auto-next banner call
  `getItemDetails(forceRefresh = true)`; `CatalogRepositoryImpl` adopts that response for 45 s and
  collapses concurrent requests for one id into one network call.
- `DataInvalidation` marks a domain dirty after a mutation on one screen; the reader screen
  refreshes silently on return.
- Design: strict monochrome, white accent; red only for errors/destructive actions and the
  "watching" badge. TV typography floor: 16 sp body, 13 sp secondary, never below 12 sp. Safe area
  58 dp horizontal / 28 dp vertical; focus scale 1.08 with a dark halo outside the white ring.
- Build: `versionName` comes from the git tag, `versionCode` from the commit count; debug/demo use
  suffixed applicationIds, so in-app updates only apply to the release id. `lint-vital` is
  disabled (lint crashes on the Kotlin analyzer). The release workflow builds tags one at a time
  and sets "Latest" by highest tag, not creation time.

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

- **User-facing overview**: `README.md`
- **Release notes**: generated by `.github/workflows/release.yml` from commit messages
  (`feat:` / `fix:` / `perf:` prefixes are grouped) and published on GitHub Releases
- There is no `docs/` directory; everything an agent needs is in this file
