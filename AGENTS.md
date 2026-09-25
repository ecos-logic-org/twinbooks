# TwinBooks Tablet

Android tablet app for bilingual reading: two EPUBs side-by-side (left=learning language, right=native language).

## Build & Verify

```bash
./gradlew assembleDebug          # Build (compilation check)
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
- **Persistence**: Room (DB version 8, migrations 1→2→…→8 in `TwinBooksDatabase.kt`)
- **Reading modes**: dual (two EPUBs side by side) or **single-book mode** (one EPUB full
  screen, Spanish side comes from the server's `POST translate` (server-side model, one ES sentence
  per EN sentence, next paragraph prefetched) with on-device ML Kit as fallback, rendered inline
  below each paragraph; TTS unit is the PARAGRAPH: EN paragraph → translated ES paragraph → EN again
  in EN↔ES mode; flag `isSingleBookMode` on the session, entry points: bookshelf
  new-session dialog and the reader's empty state)
- **EPUB**: `epub4j-core` library, images embedded as base64 data URIs

### Key Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Single activity, edge-to-edge, landscape-only |
| `ReaderViewModel.kt` | All state management, session persistence, TTS orchestration |
| `ReaderScreen.kt` | Main composable: two-panel layout, divider controls, book launchers |
| `BookPanel.kt` | WebView rendering with JS interface (`ParagraphBridge`) |
| `TtsManager.kt` | Android TextToSpeech wrapper (sentence-by-sentence playback) |
| `EpubParser.kt` | EPUB parsing, TOC extraction, image embedding as base64 data URIs |
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

Book content renders in WebView with injected CSS/JS. The JS interface `ParagraphBridge` (registered via `addJavascriptInterface`) handles:
- Paragraph highlighting (reading zone)
- Sentence navigation
- Sync state between panels
- Double-click for sync offset calculation

Dark theme overrides all EPUB styles via `!important` CSS in `BookPanel.kt`.

**NLP**: `compromise.js` (in `app/src/main/assets/`) provides client-side sentence splitting inside the WebView. It's loaded via `<script src="file:///android_asset/compromise.js">`. If sentence detection breaks, check this library first.

## Room Migrations

When adding columns to `reading_sessions` table:
1. Add field to `ReadingSessionEntity`
2. Add migration in `TwinBooksDatabase.MIGRATION_X_Y`
3. Increment DB version in `@Database(version = N)`
4. Add mapping in `BookRepositoryImpl` (toDomain/toEntity)
5. Update `ReadingSession` domain model if needed

**Note**: `ReadingSessionEntity.lastOpenedTimestamp` exists on the entity but is NOT mapped to the domain model `ReadingSession`. New fields that follow the same pattern (entity-only, no domain exposure) are fine, but be intentional about it.

## Skills

- `android-kotlin-development`: Kotlin/Compose/Hilt patterns
- `frontend-design`: WebView HTML/CSS styling guidance
