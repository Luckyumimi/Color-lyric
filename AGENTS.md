# Repository Guidelines

## Project Structure

TerraMoor is a single-module Android app for ColorOS 17. Under `app/src/main/java/shop/terramoor/github/io/`, `settings/` owns COUI, provider discovery, preview, and backup. `lyrics/` parses playback metadata and schedules line changes. `systemui/` resolves and draws into the native media capsule. `app/src/main/java/io/github/libxposed/service/` verifies the API 102 service caller. Xposed metadata lives in `app/src/main/resources/META-INF/xposed/`, and Android strings and drawables live in `app/src/main/res/`.

## Build and Test

Use Android SDK 37 and the wrapper’s JDK 25 toolchain. Run `./gradlew.bat` on Windows and `./gradlew` elsewhere.

- `:app:assembleLocalDebug :app:testLocalDebugUnitTest :app:lintLocalDebug` builds and checks the local APK.
- `:app:compileLocalDebugAndroidTestKotlin` compiles device tests. Use `:app:connectedLocalDebugAndroidTest` on a dedicated test device where the local debug package is allowed.
- `:app:assemblePublicRelease` builds the optimized, unsigned `io.github.luckyumimi.terramoor` APK. Sign outside Gradle using the release procedure in `docs/lsposed/submission.md`.

Use JUnit 4 with descriptive `*Test` classes. Test fresh defaults and old backup imports separately. Track actual hardware verification in `docs/acceptance.md`. Reuse successful checks when inputs are unchanged.

## Implementation

Use four-space Kotlin indentation, `PascalCase` types, `camelCase` members, and `lower_snake_case` resources. Reuse COUI components, color surfaces, and haptic feedback. Keep `CapsuleSettings` as the source of defaults for the app and hook. Route writes through `TerraMoorApp` so SystemUI receives changes in order. Keep the SystemUI scope fixed. Check native signatures and view ownership before drawing. Resolve OEM view IDs by name and avoid model or firmware allowlists. Keep the LSPosed entry named in `java_init.list` under R8. Restore native presentation when disabled or detached. Keep lyric parsing, provider detection, and setting writes local. The manual update button is the only in-app network request path. Keep it in `settings/GitHubUpdates.kt`, with bounded timeouts and no automatic retries or model calls.

## Maintenance

Read `HANDOFF.md` if present. Fix reproducible bugs with targeted changes. Preserve OEM transitions, live settings, backups, and native restoration. Compare firmware, plugin contracts, and media ownership for device-specific reports. Use signed `io.github.luckyumimi.terramoor` on the maintainer's unlocked phone. Ask for an unlock if it locks. Do not capture screenshots from the maintainer's phone, including over ADB. Use an emulator or dedicated test device, and save verification screenshots under the local `captures/<version>/` directory, for example `captures/1.7.0/`. Choose local checks or CI per change and reuse matching evidence.

## Changes and Releases

Use short imperative commit subjects. Include what changed and how it was verified. Include before/after screenshots for visible changes when available from an allowed source. Keep APKs out of source commits, and keep signing keys, credentials, local settings, logs, and reference APKs out of Git. Update `README.md` for user-facing setup changes and reuse the same release signing key for upgrades.
