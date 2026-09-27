# Third-party notices

TwinBooks' own source code is licensed under the [Apache License 2.0](LICENSE).

The Android app (APK) also contains the third-party components below. Because it links
**slimt**, licensed under the GPL-2.0-or-later, the APK as a whole is distributed under the
terms of the **GNU General Public License, version 3**. The complete corresponding source code
of every release is this repository at the release tag, including its git submodules
(`git clone --recurse-submodules`).

## Native code (on-device translation, `app/src/main/cpp`)

| Component | License | Source |
|---|---|---|
| slimt (fork by David Ventura) | GPL-2.0-or-later | https://github.com/DavidVentura/slimt (submodule `app/src/main/cpp/slimt`) |
| SentencePiece (browsermt fork) | Apache-2.0 | submodule `app/src/main/cpp/slimt/3rd-party/sentencepiece` |
| protobuf-lite (bundled in SentencePiece) | BSD-3-Clause | `…/sentencepiece/third_party/protobuf-lite` |
| ruy | Apache-2.0 | submodule `app/src/main/cpp/slimt/3rd-party/ruy` |
| cpuinfo (used by ruy) | BSD-2-Clause | submodule `…/ruy/third_party/cpuinfo` |
| slimt-sys (layer-count detection in `slimt_engine.cc` is derived from it) | MIT | https://github.com/DavidVentura/slimt-sys |

slimt itself contains code derived from browsermt/bergamot-translator and browsermt/marian-dev
(MPL-2.0) and browsermt/ssplit (Apache-2.0); see its `COPYRIGHT` file.

## JavaScript (`app/src/main/assets`)

| Component | License | Source |
|---|---|---|
| compromise 14.17.0 (`compromise.js`, upstream minified build) | MIT | https://github.com/spencermountain/compromise |

## Java / Kotlin libraries (Maven)

| Component | License |
|---|---|
| AndroidX, Jetpack Compose, Room, Media | Apache-2.0 |
| Dagger / Hilt | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| Retrofit, OkHttp, Moshi | Apache-2.0 |
| epub4j | Apache-2.0 |

## Downloaded at runtime (not included in the APK)

| Component | License | Source |
|---|---|---|
| Firefox Translations English → Spanish model (Mozilla) | MPL-2.0 | https://github.com/mozilla/firefox-translations-models |
