<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/icon.png" alt="Piko" width="96"></p>

<h1 align="center">Piko</h1>

<p align="center"><a href="README.md">简体中文</a> | <b>English</b></p>

<p align="center">
<a href="#install"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white"></a>
<a href="#install"><img alt="Windows 10+ x64 | arm64" src="https://img.shields.io/badge/Windows-10%2B%20x64%20%7C%20arm64-306EFF?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/piko/releases/latest"><img alt="Latest Release" src="https://img.shields.io/github/v/release/NihilDigit/piko?style=flat-square&color=306EFF"></a>
<a href="LICENSE"><img alt="MIT" src="https://img.shields.io/github/license/NihilDigit/piko?style=flat-square&color=306EFF"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-306EFF?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-306EFF?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-306EFF?style=flat-square&logo=materialdesign&logoColor=white">
</p>

<p align="center"><b>A fast, cross-platform PikPak client</b></p>

## Android and Windows

It is built with Kotlin Multiplatform: both platforms share the interface and the business logic,
and the interface follows Material 3 Expressive.
- **Android**: a native app, with the interface on Jetpack Compose and playback on libmpv.
- **Windows**: a GPU-accelerated interface on Compose Multiplatform, with video frames passed from
  D3D11 straight into Skia and composited without a copy.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/desktop.jpg" height="380" alt="Windows: poster wall">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/phone.jpg" height="380" alt="Android: poster wall">
</p>

## Offline downloads

Magnet links are parsed into works, sections and episodes, with subtitles grouped under their
video. Videos PikPak already has can be previewed in full before saving, and only the ticked
files are kept.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/magnet.jpg" height="528" alt="Parsed magnet">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/magnet-sections.jpg" height="528" alt="Works and sections">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/preview.jpg" height="528" alt="Preview before saving">
</p>

## Browse the drive by title

Drive folders are grouped the same way, by work, section and episode, with a poster wall view and
drive-wide search.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/drive.jpg" height="528" alt="List view">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/search.jpg" height="528" alt="Drive-wide search">
</p>

## Playback

The episode list is split by work and section, and progress syncs with the official PikPak
clients. Android has gestures for brightness, volume and position; Windows works with the mouse and
keyboard.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/player.jpg" width="410" alt="Landscape playback">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/episodes.jpg" width="410" alt="Episode list">
</p>

## Transfers and housekeeping

Downloads and streaming read over eight connections at once, using the full bandwidth even on a
poor network. Piko also cuts lossless video clips, uploads files, extracts archives on the server
and finds duplicates.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/segment.jpg" height="528" alt="Clip download">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/duplicates.jpg" height="528" alt="Duplicate finder">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/account.jpg" height="528" alt="Storage and traffic">
</p>

## Install

Download from [Releases](https://github.com/NihilDigit/piko/releases/latest). Every package is built
from this repository by GitHub Actions, and `SHA256SUMS.txt` lists the checksums.

- **Android** needs Android 8.0 or later. Pick the APK for your device's architecture, or
  `universal` if unsure.
- **Windows** needs Windows 10 or later, on x64 or arm64.
  - `.msi` installs for the current user without administrator rights and supports in-app updates.
  - `.zip` is the portable build: unpack it and run `Piko.exe`.

## Contributing

Issues and pull requests are welcome. Small bug fixes, crash reports and documentation fixes can go
straight in as a pull request.

For a new feature or an architectural change, open an issue first describing the use case and the
approach, so the direction is agreed before the work starts.

If you write code with the help of an LLM, make sure you understand what it adds and test it on a
real device.

## License and credits

- The source code is under the [MIT](LICENSE) license. The Android packages bundle GPL builds of
  mpv and FFmpeg and are therefore distributed under GPLv3.
- The PikPak API implementation draws on [52funny/pikpakcli](https://github.com/52funny/pikpakcli).
- Several features are modelled on the PikPak web enhancement script
  [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master).
- Zero-copy mpv rendering on Windows is provided by [MediaMP](https://github.com/open-ani/mediamp).
