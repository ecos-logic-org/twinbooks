# TwinBooks Tablet

Android tablet app for bilingual reading: two EPUBs side-by-side (left=learning language, right=native language).

## Build & Verify

```bash
./gradlew assembleDebug          # Build
./gradlew lintDebug              # Lint
./gradlew testDebug              # Tests (currently none)
```

CI runs: `lintDebug` → `assembleDebug` → `testDebug` (GitLab CI, `.gitlab-ci.yml`).

Always run `./gradlew assembleDebug` after changes to verify compilation.

## Architecture

- **Package**: `org.ecos.logic.twinbooks`
- **Pattern**: MVVM (Hilt + ViewModel + StateFlow)
- **UI**: Jetpack Compose + WebView for EPUB rendering
- **DI**: Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`)
- **Persistence**: Room (DB version 4, migrations 1→2→3→4 in `TwinBooksDatabase.kt`)
- **EPUB**: `epub4j-core` library, images embedded as base64 data URIs

### Key Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Single activity, edge-to-edge, landscape-only |
| `ReaderViewModel.kt` | All state management, session persistence |
| `BookPanel.kt` | WebView rendering with JS bridge (`ParagraphBridge`) |
| `EpubParser.kt` | EPUB parsing, TOC extraction, image embedding |
| `TwinBooksDatabase.kt` | Room DB + migrations (add new migrations here) |
| `AppModule.kt` | Hilt modules (DatabaseModule, RepositoryModule) |

### Data Flow

`ReaderViewModel` → `BookRepository` → `EpubParser` + `ReadingSessionDao` → Room

State is persisted on every position change via `saveCurrentSession()`.

## Conventions

- **Language**: All code, comments, and docs in English
- **Theme**: Dark mode only (black background, white text)
- **Orientation**: Landscape-only, tablet-only (min 1920x1200, xhdpi+)
- **Min SDK**: 30 (Android 11), Target SDK: 35

## EPUB Rendering

Book content renders in WebView with injected CSS/JS. The JS bridge `ParagraphBridge` handles:
- Paragraph highlighting (reading zone)
- Sentence navigation
- Sync state between panels
- Double-click for sync offset calculation

Dark theme overrides all EPUB styles via `!important` CSS in `BookPanel.kt`.

## Room Migrations

When adding columns to `reading_sessions` table:
1. Add field to `ReadingSessionEntity`
2. Add migration in `TwinBooksDatabase.MIGRATION_X_Y`
3. Increment DB version
4. Add mapping in `BookRepositoryImpl` (toDomain/toEntity)
5. Update `ReadingSession` domain model if needed

## Skills

- `android-kotlin-development`: Kotlin/Compose/Hilt patterns
- `frontend-design`: WebView HTML/CSS styling guidance
