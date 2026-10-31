<p align="right"><a href="README.md">简体中文</a> | <a href="README.zh-Hant.md">繁體中文</a> | <b>한국어</b> | <a href="README.ja.md">日本語</a> | <a href="README.en.md">English</a></p>

<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/icon.png" alt="Piko" width="96"></p>

<h1 align="center">Piko</h1>

<p align="center">
<a href="#설치"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white"></a>
<a href="#설치"><img alt="Windows 10+ x64" src="https://img.shields.io/badge/Windows-10%2B%20x64-306EFF?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="#설치"><img alt="Windows 11+ arm64" src="https://img.shields.io/badge/Windows-11%2B%20arm64-306EFF?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="#설치"><img alt="macOS 12+ arm64" src="https://img.shields.io/badge/macOS-12%2B%20arm64-306EFF?style=flat-square&logo=apple&logoColor=white"></a>
<a href="#설치"><img alt="Linux x64" src="https://img.shields.io/badge/Linux-x64-306EFF?style=flat-square&logo=linux&logoColor=white"></a>
<br>
<a href="https://github.com/NihilDigit/piko/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/NihilDigit/piko?style=flat-square&color=306EFF&label=Release&logo=github&logoColor=white"></a>
<a href="https://github.com/NihilDigit/piko/attestations"><img alt="SLSA Build L3" src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/badges/slsa-l3.svg"></a>
<a href="LICENSE"><img alt="License AGPL-3.0-or-later" src="https://img.shields.io/badge/License-AGPL--3.0--or--later-306EFF?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0xIDIxdi0yaDEwVjcuODI1UTEwLjM1IDcuNiA5Ljg3NSA3LjEyNVQ5LjE3NSA2SDZsMyA3cTAgMS4yNS0xLjAyNSAyLjEyNVQ1LjUgMTZxLTEuNDUgMC0yLjQ3NS0uODc1VDIgMTNsMy03SDNWNGg2LjE3NXEuMy0uODc1IDEuMDc1LTEuNDM3VDEyIDJxLjk3NSAwIDEuNzUuNTYzVDE0LjgyNSA0SDIxdjJoLTJsMyA3cTAgMS4yNS0xLjAyNSAyLjEyNVQxOC41IDE2cS0xLjQ1IDAtMi40NzUtLjg3NVQxNSAxM2wzLTdoLTMuMTc1cS0uMjI1LjY1LS43IDEuMTI1VDEzIDcuODI1VjE5aDEwdjJIMVptMTUuNjI1LThoMy43NUwxOC41IDguNjUgMTYuNjI1IDEzWm0tMTMgMGgzLjc1TDUuNSA4LjY1IDMuNjI1IDEzWk0xMiA2cS40MjUgMCAuNzEzLS4yODhUMTMgNXEwLS40MjUtLjI4OC0uNzEzVDEyIDRxLS40MjUgMC0uNzEzLjI4OFQxMSA1cTAgLjQyNS4yODguNzEzVDEyIDZaIi8+PC9zdmc+Cg=="></a></p>

<p align="center"><b>빠르고 가벼운 크로스 플랫폼 PikPak 클라이언트</b></p>

<p align="center">개발 소식과 변경 내역은 Telegram 채널 <a href="https://t.me/piko_dev">@piko_dev</a>에 게시됩니다</p>

<p align="center"><a href="https://trendshift.io/repositories/263280?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-263280" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/263280/weekly?language=Kotlin" alt="Trendshift: NihilDigit/piko" width="250" height="53"/></a></p>

## 적응형 레이아웃

데스크톱에서는 키보드와 마우스 조작에 최적화되고 모바일에서는 간결하게 동작합니다. 다중 탭 작업 공간, 갤러리 동영상 및 이미지 보기, 로컬 폴더를 현재 드라이브 폴더 안에 읽기 전용으로 마운트하여 통합 탐색하는 기능을 지원합니다. 인터페이스는 중국어 간체, 중국어 번체, 한국어, 일본어 및 영어를 제공합니다.

<p align="center">
<picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/hero-dark.jpg">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/hero.jpg" alt="데스크톱과 모바일의 포스터 뷰">
</picture>
</p>

## 클립 피드

현재 폴더나 선택한 동영상의 구간을 무작위로 재생하며 위아래로 넘겨 전환합니다. 자동 연장 재생과 자동 다음 재생을 지원합니다.

<p align="center">
<picture>
<source media="(prefers-color-scheme: dark)" type="image/avif" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/feed-dark.avif">
<source type="image/avif" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/feed.avif">
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/feed-dark.jpg">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/feed.jpg" alt="드라이브 우측의 클립 피드">
</picture>
</p>

## 링크 및 파일 이름 분석

마그넷 링크, 토렌트, 다운로드 주소 및 공유 링크를 분석하며, 저장하기 전에 미리 보고 원하는 파일만 선택하거나 드라이브 용량을 차지하지 않는 참조로 저장할 수 있습니다. 파일은 작품과 회차별로 정리되며 샘플과 부가 영상은 접어 둡니다.

<p align="center">
<picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/links-dark.jpg">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/links.jpg" alt="작품별로 정리된 에피소드와 마그넷 링크 분석 패널">
</picture>
</p>

## 전송

다중 연결을 병렬로 스케줄링하고 느리거나 응답이 없는 노드를 자동으로 우회하여 고지연 및 불안정한 네트워크에서도 원활한 재생과 다운로드를 제공합니다. 저속 모드로 전송 속도를 제한해도 동영상 재생에는 영향을 주지 않습니다.

<p align="center">
<picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/transfers-dark.jpg">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/transfers.jpg" alt="전송 페이지: 진행 중이거나 완료된 다운로드, 업로드 및 클라우드 작업">
</picture>
</p>

## 파일 도구

로컬 폴더 트리 기반의 즉각적인 전체 퍼지 검색을 지원하며, 공간 확보를 위한 보관, 동영상 구간 추출, 일괄 이름 변경 및 폴더 간 중복 파일 찾기를 제공합니다.

<p align="center">
<picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/tools-dark.jpg">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/tools.jpg" alt="모바일에서의 동영상 구간 다운로드 및 중복 파일 찾기">
</picture>
</p>

## 설치

[Releases](https://github.com/NihilDigit/piko/releases/latest)에서 다운로드합니다. 모든 패키지는 GitHub Actions에서 소스 코드로부터 빌드되며, 릴리스 페이지에 기기별로 다운로드할 파일이 안내되어 있습니다.

- **Android**: Android 8.0 이상. 기기 아키텍처에 맞는 APK를 선택하며, 확실하지 않은 경우 `universal`을 선택합니다.
- **Windows**: x64는 Windows 10 이상, arm64는 Windows 11 이상. `.msi`는 설치판, `.7z`는 포터블 버전입니다. Windows 11에서는 파일 탐색기에서 바로 압축을 풀 수 있으며, Windows 10에서는 [NanaZip](https://apps.microsoft.com/detail/9n8g7tscl18r) 또는 7-Zip을 사용합니다.
- **macOS** (실험적 지원): macOS 12 이상, Apple Silicon 전용. 공증되지 않은 패키지이므로 처음 실행할 때 「시스템 설정」→「개인정보 보호 및 보안」에서 허용해야 합니다.
- **Linux** (실험적 지원): x64, glibc 2.38 이상(Ubuntu 24.04, Debian 13, Fedora 39 이상). AppImage에 실행 권한을 부여한 뒤 실행합니다.

## 기여 안내

로드맵은 [#10](https://github.com/NihilDigit/piko/issues/10)에서 확인할 수 있습니다.

이슈 제보와 PR을 환영합니다. 작은 버그 수정, 비정상 종료 분석 및 문서 보완은 바로 PR로 제출할 수 있습니다.

새로운 기능을 추가하거나 아키텍처를 변경하려는 경우, 먼저 이슈를 열어 사용 시나리오와 설계 방향을 논의한 뒤 구현을 진행해 주세요.

LLM의 도움을 받아 코드를 작성할 때는 추가되는 로직을 충분히 이해하고 실제 기기에서 검증해 주세요.

PR을 제출하기 전에 [기여자 라이선스 계약](CLA.md)에 서명해야 합니다. 자세한 방법은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.

## 라이선스 및 감사의 글

- 소스 코드는 [AGPL-3.0-or-later](LICENSE) 라이선스로 공개됩니다. 1.1.0 이하 버전은 MIT 라이선스로 배포되었습니다.
  Android 패키지에는 GPLv3로 빌드된 mpv와 FFmpeg가 포함되어 있습니다.
- PikPak API 구현은 [52funny/pikpakcli](https://github.com/52funny/pikpakcli)를 참고했습니다.
- 여러 기능 설계는 PikPak 웹 확장 스크립트 [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master)를 참고했습니다.
- Windows용 mpv 제로카피 렌더링은 [MediaMP](https://github.com/open-ani/mediamp)로 구현되었습니다.

## Star History

<a href="https://www.star-history.com/#nihildigit/piko&Date">
<picture>
<source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=nihildigit/piko&type=Date&theme=dark&legend=bottom-right">
<img alt="Star History Chart" src="https://api.star-history.com/svg?repos=nihildigit/piko&type=Date&legend=bottom-right">
</picture>
</a>
