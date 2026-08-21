<p align="center">
  <img src="./docs/images/social-preview.png" alt="Android SMB video player — play straight from a network share" width="100%" />
</p>

# Android SMB video player

[中文](./README.md) · English

[![CI](https://github.com/ferretgeek/android-smb-player/actions/workflows/ci.yml/badge.svg)](https://github.com/ferretgeek/android-smb-player/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-0f766e.svg)](./LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](#building-it-yourself)

> Open a folder shared from your desktop or NAS, tap a file, and it plays.

## Why this exists

Your movies live on a desktop or a NAS. To watch them on a phone or tablet, you usually pick one of two paths: copy a file over (slow, wasteful), or stand up a whole media server (something else to maintain, transcode, and keep running).

There's a simpler third path: a Windows shared folder, a Synology box, anything speaking SMB — connect and play. That's what this app is.

- Subtitles are matched automatically, with a character-set switch for when they come out garbled.
- Playback position is remembered, so you pick up where you left off.
- History, bookmarks, tags, and notes all stay on the device.
- **No account, no cloud, no transcoding server, no telemetry.**

Its "server side" is the share you already have. Nothing extra to install.

## Interface

The screenshot below comes from the project's built-in anonymous gallery: no SMB connection, no private media, no real accounts or filenames.

<p align="center">
  <img src="./docs/images/gallery-preview.png" alt="Anonymous gallery preview" width="360" />
</p>

## What it does

- **Find it and get in** — LAN scanning, share discovery, guest or account sign-in, browsing, search, and sorting.
- **Actually play it** — Media3 as the primary engine with an automatic libVLC fallback for awkward codecs, plus hardware decoding, speed control, scaling, and frame-rate matching.
- **Subtitles and audio** — automatic external-subtitle matching, character-set selection, embedded tracks, and external audio tracks.
- **Remember things** — resume, watch history, bookmarks, tags, notes, trash, and full backup and restore.
- **Look right** — multiple light palettes and a `#17191d` deep-gray dark mode across browsing, details, and playback.
- **Leave nothing behind** — no account system, cloud sync, or telemetry; credentials are encrypted on-device and logs are redacted.
- **Posters, if you want them** — an optional desktop scraper generates posters and structured metadata into the share ahead of time; the phone only reads the result.

## Building it yourself

The first public version **ships source code rather than a general signed APK**, so a development signature never gets mistaken for a trusted distribution identity.

Install JDK 21, Android SDK 37, and Android Studio, then:

```powershell
cd LanPlay
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=2
```

The debug APK is written to `LanPlay/app/build/outputs/apk/debug/`. Release signing material is read only from environment variables or a configuration outside the workspace; nothing is committed.

Tests:

```powershell
cd LanPlay
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=2

cd ..\lanplay-scraper
.\.venv\Scripts\python.exe -m unittest -v
```

## Worth noting technically

**SMB 2/3 is implemented natively.** No WebDAV bridge, no third-party gateway, no companion service on the desktop. The folder you right-clicked and shared in Windows *is* the data source.

**Two playback engines, switched automatically.** Media3 covers almost everything; anything it can't decode falls back to libVLC without the user needing to know it happened. Hardware decoding, speed control, scaling, and frame-rate matching work on both.

**The scraper is a one-shot tool, not a daemon.** It runs on demand on a PC, writes posters and metadata back into the share, and then you close it. The phone reads the result over SMB — so nothing in this project needs to stay running 24/7. Real directories only ever go into the git-ignored `config.toml`.

**Credentials and logs are treated as sensitive.** SMB credentials are encrypted on-device, and logs are redacted before output rather than printing share paths and usernames verbatim.

Scraper installation, configuration, and network boundaries are documented in [`lanplay-scraper/README.md`](./lanplay-scraper/README.md).

## Layout

```text
LanPlay/             Android app and Gradle project
lanplay-scraper/     Optional Windows / Python metadata scraper
docs/images/         Redacted previews and the social preview
播放器规格.md         Implemented product and technical specification
设计系统.md           Visual, layout, and interaction specification
需求文档.md           Full requirements and acceptance boundaries
```

## Real-world limits

- Playback quality depends on the SMB server itself, network conditions, vendor background policies, and device decoders — none of which the app controls.
- The optional scraper visits public third-party pages. Follow your local law and those sites' terms.
- The first public version has no signed APK; build it yourself.

## More documentation

[Install, upgrade, backup, restore, troubleshooting](./docs/OPERATIONS.md) · [Changelog](./CHANGELOG.md) · [Contributing](./CONTRIBUTING.md) · [Security policy](./SECURITY.md)

## License

The source is released under the [MIT License](./LICENSE). Third-party libraries, including libVLC, keep their own licenses and are not relicensed by this repository.
