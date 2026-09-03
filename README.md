# One Pace — Aniyomi extension (personal build)

This repository contains a custom One Pace source module and a GitHub Actions workflow that builds an installable **debug APK** against the current Yuzono anime-extension codebase.

## Build from GitHub (no Android Studio needed)

1. Upload all files in this repository to your GitHub repository.
2. Open **Actions**.
3. Select **Build One Pace Aniyomi Extension**.
4. Tap **Run workflow**.
5. When the run is green, open it and download the artifact named **onepace-aniyomi-apk**.
6. Extract the downloaded ZIP. Inside is `onepace-aniyomi-debug.apk`.
7. Install that APK on Android, then open Aniyomi and look for **One Pace** under anime sources/extensions.

## Current behavior

- Source page: `https://onepace.net/ja/watch`
- Lists One Pace arcs as entries.
- Reads public Pixeldrain collection links exposed by the One Pace watch page.
- Uses collection files as episodes.
- Tries to expose matching resolutions/subtitle variants as video options.

## Build command used by the workflow

`./gradlew src:all:onepace:assembleDebug`

This follows the current Yuzono contribution documentation for building a single anime extension.

## Important

This is the **test APK stage**. Do not add an `index.min.json` repository URL to Aniyomi yet. First verify that the APK installs and that One Pace loads arcs, episodes, and playback correctly. After that, the repository can be upgraded to a signed release + repo index.
