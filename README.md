# TwinBooks

[![Pipeline status](https://gitlab.com/ecos.logic.org/twinbooks/badges/master/pipeline.svg)](https://gitlab.com/ecos.logic.org/twinbooks/-/pipelines)
[![Latest release](https://gitlab.com/ecos.logic.org/twinbooks/-/badges/release.svg)](https://gitlab.com/ecos.logic.org/twinbooks/-/releases/permalink/latest)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**TwinBooks** is a free and open source Android app for learning English by reading the
same book twice: the English edition and its Spanish translation, side by side and kept in sync
paragraph by paragraph.

## Features

- **Two books side by side**: open an English EPUB on the left and its Spanish edition on the
  right. Both panels scroll together and the current paragraph is highlighted in each.
- **Automatic paragraph alignment**: chapters and paragraphs in both books are matched
  automatically, even when the translation splits or merges them. You can correct a mismatch by
  hand and your correction is remembered.
- **Single-book mode**: only have the English book? Read it full screen with an automatic Spanish
  translation shown below each paragraph.
- **Bilingual read-aloud (TTS)**: listen to the book using several modes: English only,
  EN → ES, ES → EN or EN → ES → EN. You can adjust the reading speed, and playback continues
  from paragraph to paragraph.
- **Lock screen and notification controls**: play, pause and skip while the screen is off, with
  the book cover shown in the player.
- **Sentence and paragraph navigation**: step forward and back through sentences and paragraphs.
- **Table of contents** for jumping between chapters.
- **Bookshelf**: keep your book pairs and single books on a shelf with covers and per-book
  progress, and continue where you left off.
- **Automatic progress saving**: your position, reading mode and TTS settings are saved all the
  time.
- **Comfortable reading**: a dark theme designed for landscape reading. Images inside the books
  are shown too.

## Alignment and translation service (optional)

TwinBooks works **out of the box with no server**: it aligns the paragraphs of both books and
translates on the device, even offline (see below).

Optionally, you can connect it to
[**twinbooks-alignment-service**](https://gitlab.com/ecos.logic.org/twinbooks-alignment-service),
a companion service that is **also open source** and improves:

- **Paragraph and sentence alignment** between the two books.
- **Translation** for single-book mode.

You can **host the service wherever you want**, for example on your own machine or server.
There is also an instance at <https://twinbooks.duckdns.org/>, but it **requires an API key**.
If the server cannot be reached, the app falls back to on-device alignment and translation.

### Connecting to a server

In the app, tap the ⚙️ button on the bookshelf, turn on **Usar un servidor**, enter the server
address (a bare host such as `twinbooks.example.org` is enough) and your API key, and use
**Probar conexión** to check both. The server must be reachable over **HTTPS**: book text and
the API key travel in every request, so plain HTTP is refused.

For your own builds you can also bake a default server in, through an untracked
`secrets.properties` file at the project root:

```properties
alignment.baseUrl=https://your-server.example.com/api/v1/
alignment.apiKey=your-api-key
```

or the `TWINBOOKS_BASE_URL` and `TWINBOOKS_API_KEY` environment variables. Without them the
build starts with no server. Whatever is saved in the app takes precedence.

## Offline translation

Without the service, TwinBooks translates on the device with
[slimt](https://github.com/DavidVentura/slimt), a small inference engine for the open
[Firefox Translations](https://github.com/mozilla/translations) models by Mozilla. No Google
services are involved. The English → Spanish model (about 25 MB) is downloaded from Mozilla
the first time it is needed and then works without a connection.

## Download

Download the APK from the
[**latest release**](https://gitlab.com/ecos.logic.org/twinbooks/-/releases/permalink/latest)
and install it on your device. You may need to allow installing apps from unknown sources.

Requirements: Android 11 (API 30) or newer.

**Coming soon to [F-Droid](https://f-droid.org/).**

## Building from source

The on-device translator is native code (C++, built with the Android NDK and CMake through
Gradle). Clone with submodules:

```bash
git clone --recurse-submodules https://gitlab.com/ecos.logic.org/twinbooks.git
# or, in an existing clone:
git submodule update --init --recursive

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## License

TwinBooks' own source code is open source software released under the
[Apache License 2.0](LICENSE).

The app links [slimt](https://github.com/DavidVentura/slimt), which is licensed under the
GPL-2.0-or-later, so the **APK as a whole is distributed under the GPL-3.0**; its complete
source is this repository at the release tag, submodules included. The translation model is
Mozilla's (MPL-2.0) and is downloaded at runtime. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for every third-party component and its
license.
Contributions, bug reports and suggestions are welcome through
[GitLab issues](https://gitlab.com/ecos.logic.org/twinbooks/-/issues) and merge requests.

## Donations

If you enjoy TwinBooks and feel like supporting it…

[<img src="https://www.paypalobjects.com/webstatic/de_DE/i/de-pp-logo-100px.png" border="0"
    alt="PayPal Logo">](https://www.paypal.com/paypalme/PVergaraCastro)
