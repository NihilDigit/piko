# Repository Guidelines

## Project Structure & Module Organization

Piko is a Kotlin Multiplatform/Compose application built with Gradle. Put shared business logic and state in `shared/src/commonMain`; put shared UI in `ui/src/commonMain`. Platform entry points and integrations live in `app` (Android) and `desktopApp` (Windows, macOS, Linux). The `cli` and `shots` modules provide development utilities. Tests are colocated by source set, primarily under `shared/src/desktopTest`, `ui/src/desktopTest`, `desktopApp/src/desktopTest`, and `app/src/androidTest`. Documentation and screenshots are under `docs/`.

## Build, Test, and Development Commands

Use the repository wrapper (`./gradlew` on Unix-like systems, `gradlew.bat` on Windows):

```bash
./gradlew :app:compileDebugKotlin
./gradlew :desktopApp:compileKotlinDesktop
./gradlew :desktopApp:run
./gradlew :shared:desktopTest :ui:desktopTest
./gradlew :desktopApp:desktopTest       # Windows-only desktop/WinRT tests
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug             # install on a connected Android device
```

Before opening a PR, compile both Android and Desktop targets. Use `--tests '*TestName*'` for a focused test run. Release-only issues can be checked with `./gradlew :desktopApp:createReleaseDistributable`.

## Coding Style & Naming Conventions

Follow existing Kotlin formatting: four-space indentation, trailing commas where the surrounding code uses them, and expressive names. Use `PascalCase` for classes/composables, `camelCase` for functions, properties, and local variables, and suffix tests with `Test` or `SmokeTest`. Keep platform-neutral code in shared source sets; isolate Android/Desktop APIs behind the existing interfaces and platform implementations. Follow module-specific guidance in `ui/CLAUDE.md`, `desktopApp/CLAUDE.md`, and nested `CLAUDE.md` files.

## Testing Guidelines

Prefer deterministic unit tests for shared rules and state, then add platform smoke tests for native behavior. Place tests beside the module and source set they exercise. Run the narrowest relevant Gradle task during iteration, then run both shared/UI desktop tests and Android unit tests for cross-module changes. CI workflows in `.github/workflows/test.yml` define the broader matrix, including connected Android and packaging smoke tests.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects, optionally with a scope, such as `fix(drive): restore account cache` or `feat(release): sign windows packages`. Keep commits focused. Small bug fixes and documentation changes may go directly to a PR; new features or architectural changes should first have an issue describing the use case and approach. PRs must explain behavior changes and testing performed, include screenshots for UI changes, and comply with `CONTRIBUTING.md` and the CLA requirement.

## Security & Configuration Tips

Do not commit credentials, tokens, `local.properties`, or machine-specific SDK paths. For local SDK/bridge development, use the documented `pikpak.sdk.dir` or `windowstouch.dir` properties in `local.properties`; never use `mavenLocal` as a substitute.
