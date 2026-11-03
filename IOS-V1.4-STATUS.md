# iOS v1.4.0 integration — work in progress

Upstream baseline: `v1.4.0` (commit `d61c1580783911be557d4c39026c300517187bcb`). The old iOS port has been merged into the release on a separate branch.

This integration is not yet validated. The iOS compile, simulator tests, signed installation, account login, browsing, playback, uploads and downloads must be verified before it can be called usable. The existing `IOS-PORT-STATUS.md` describes historical work and is not a current verification report.

Initial integration includes a Foundation NFKC implementation and persistence for the new language, feed, gallery, sorting, and local mount preferences. Local folder mounting itself still lacks an iOS implementation. Resolve any additional compile and runtime regressions found in CI and on real devices.

The `ios-ipa` workflow runs for pushes to this branch, or can be launched manually in Actions. If signing secrets are absent, it builds an unsigned IPA for re-signing. A successful archive alone does not establish application usability.
