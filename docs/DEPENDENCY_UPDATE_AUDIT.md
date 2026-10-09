# Dependency update audit

Audit date: 2026-10-09 (UTC). Scope: repository-declared libraries and build/release tools, the complete Bundler graph, and the pinned upstream Fedimint release. Upstream Cargo dependencies retain the release’s lockfile.

Upstream versions were independently checked against Maven Central, Google Maven, JitPack, RubyGems, official Gradle release metadata, and upstream Git refs. Repository-declared updates select stable releases; commit-only libraries remain pinned to immutable published commits.

## Android dependencies and build tools

| Version key | Previous | Selected |
| --- | --- | --- |
| `geocoderVersion` | `3.26` | `3.41` |
| `carrierVersion` | `2.26` | `2.41` |
| `kotlin` | `2.3.10` | `2.4.21` |
| `kotlinxSerializationJson` | `1.10.0` | `1.11.0` |
| `ksp` | `2.3.6` | `2.3.12` |
| `detekt` | `1.23.8` | `1.23.8` |
| `detektCompose` | `0.5.6` | `0.4.28` |
| `androidx-appcompat` | 1.7.1 (transitive) | 1.8.0 (explicit) |
| `androidx-constraintlayout` | `2.2.1` | `2.2.2` |
| `androidx-swiperefreshlayout` | `1.2.0` | `1.2.0` |
| `androidx-lifecycleprocess` | `2.10.0` | `2.11.0` |
| `androidx-documentfile` | `1.1.0` | `1.1.0` |
| `androidx-webkit` | `1.15.0` | `1.17.1` |
| `eventbus` | `3.3.1` | `3.3.1` |
| `room` | `2.8.4` | `2.8.5` |
| `commons` | `6.1.6` | `7.1.1` |
| `commons-compress` | `1.28.0` | `1.28.0` |
| `mmslib` | `1.0.0` | `1.0.0` |
| `indicator-fast-scroll` | `4524cd0b61` | `c7873f7168` |
| `gradlePlugins-agp` | `9.2.0` | `9.4.1` |
| `ez-vcard` | `0.12.2` | `0.12.2` |
| `autofitTextView` | `0.2.1` | `0.2.1` |
| `libphonenumberVersion` | `9.0.26` | `9.0.41` |
| `libphonenumberinfo` | `c77bdd2b69` | `c77bdd2b69` |
| `mediapipeTasksTextVersion` | `0.10.26.1` | `1.1.0` |
| `okhttp` | `5.3.2` | `5.5.0` |
| `slf4j` | `1.7.36` | `1.7.36` |
| `work` | `2.11.1` | `2.12.0` |
| `bouncycastle` | `1.83` | `1.86` |
| `msgpack` | `0.9.11` | `0.9.12` |
| `zxingCore` | `3.5.4` | `3.5.4` |
| `zxingEmbedded` | `4.3.0` | `4.3.0` |
| `concentus` | `6c2328dc19` | `3885c4e465` |
| `junit` | `4.13.2` | `4.13.2` |
| `robolectric` | `4.16.1` | `4.17` |
| `ldkNode` | `0.7.0` | `0.7.0` |
| Gradle wrapper | `9.4.1` | `9.8.1` |
| Compile SDK | `36` | `37.2` |
| Fedimint source | `e8570bc6b4f9` (0.11 alpha) | `53be4684015c` (0.12.2 stable) |
| wasm-bindgen CLI | `0.2.100` | `0.2.106` (matches upstream lockfile) |

The application target SDK remains 36 and minimum SDK remains 26. API 37.2 is needed to compile current Lifecycle and OkHttp artifacts. The generated Gradle wrapper JAR SHA-256 matches the official Gradle 9.8.1 wrapper checksum. The pinned Gradle distribution checksum is also retained.

## Compatibility decisions

- Commons Codec remains forced to 1.15. LibPhoneNumberInfo is already at its latest upstream commit (`c77bdd2b692a…`) and bundles caller-ID code that accesses Codec internals changed by newer releases; blindly updating it risks Android verification failures. The newest published Codec is 1.22.1 and needs an upstream caller-ID compatibility fix before adoption.
- Detekt remains at its latest stable release, 1.23.8. Compose rules 0.5.x and 0.6.x require prerelease Detekt 2. The selected 0.4.28 is the newest published rules version compatible with stable Detekt 1.x, correcting the previously incompatible 0.5.6 pin.
- Android CI jobs share setup for SDK 37.2/build-tools 37.0.0, the wasm32 Rust target, and wasm-bindgen 0.2.106. This supplies the wallet generator prerequisites for both lint and assembly. Workflow/action YAML parses locally; GitHub-hosted jobs have not been executed.
- Existing F-Droid build records for historical app tags retain their historical wasm-bindgen version; current release scripts and current source documentation use 0.2.106.
- Concentus now uses its published, case-sensitive library coordinate, `com.github.lostromb.Concentus:Concentus`. The earlier environment-only substitution is no longer required.
- AppCompat is explicitly updated to 1.8.0 in both modules. Commons brings in Material 1.14.0, whose toolbar styles require the new `center_vertical` button-gravity flag missing in transitive AppCompat 1.7.1. The latest stable AppCompat supplies that flag without patching vendor resources. Jetifier is enabled because the transitive pattern-lock library still references pre-AndroidX support classes and brings AppCompat v7 25.3.0; migrating those dependencies prevents their old attributes from overriding AndroidX. The resource-linking task passes with this migration. AGP reports Jetifier as deprecated and scheduled for removal in AGP 10; adopting AGP 10 will require an upstream AndroidX migration of that legacy library.
- Library updates include Android API-compatible percent decoding in both token and LNURL parsing, plus the explicit generated-wallet-asset dependencies needed by lint. The app now declares telephony hardware optional, overriding the messages library’s implicit required feature, so phone permissions no longer impose a telephony hardware filter. These fixes address errors exposed by the current lint version. No lint baseline was regenerated.

## Pull requests reviewed

All 13 open PRs were listed through GitHub’s public pull-request page and their exact head refs were fetched through the existing HTTPS Git proxy. Their diffs were inspected. GitHub API access was blocked by the running network policy; the complete web listing reported one page and 13 open requests. No remote PR was merged or modified.

- [#58](https://github.com/ariadne-coil/Cyber-Phone/pull/58): chore(deps): bump json from 2.19.2 to 2.19.9 — superseded by current upstream versions.
- [#57](https://github.com/ariadne-coil/Cyber-Phone/pull/57): chore(deps): bump actions/labeler from 6 to 7 — adopted the current major action version.
- [#56](https://github.com/ariadne-coil/Cyber-Phone/pull/56): chore(deps): bump excon from 0.112.0 to 1.5.0 — superseded by current upstream versions.
- [#55](https://github.com/ariadne-coil/Cyber-Phone/pull/55): chore(deps): bump fastlane from 2.232.2 to 2.237.0 — superseded by current upstream versions.
- [#54](https://github.com/ariadne-coil/Cyber-Phone/pull/54): chore(deps): bump faraday from 1.10.5 to 1.10.6 — superseded by current upstream versions.
- [#53](https://github.com/ariadne-coil/Cyber-Phone/pull/53): chore(deps): bump actions/checkout from 6 to 7 — adopted the current major action version.
- [#51](https://github.com/ariadne-coil/Cyber-Phone/pull/51): chore(deps): bump jwt from 2.10.2 to 2.10.3 — superseded by current upstream versions.
- [#45](https://github.com/ariadne-coil/Cyber-Phone/pull/45): chore(deps): bump addressable from 2.8.9 to 2.9.0 — adopted through the complete Bundler update.
- [#44](https://github.com/ariadne-coil/Cyber-Phone/pull/44): chore(deps): bump com.googlecode.libphonenumber:carrier from 2.26 to 2.27 — superseded by current upstream versions.
- [#43](https://github.com/ariadne-coil/Cyber-Phone/pull/43): chore(deps): bump gradle-wrapper from 9.4.0 to 9.4.1 — the proposed wrapper was already present; updated further to 9.8.1.
- [#42](https://github.com/ariadne-coil/Cyber-Phone/pull/42): chore(deps): bump androidx.work:work-runtime-ktx from 2.11.1 to 2.11.2 — superseded by current upstream versions.
- [#41](https://github.com/ariadne-coil/Cyber-Phone/pull/41): chore(deps): bump com.googlecode.libphonenumber:geocoder from 3.26 to 3.27 — superseded by current upstream versions.
- [#40](https://github.com/ariadne-coil/Cyber-Phone/pull/40): chore(deps): bump com.googlecode.libphonenumber:libphonenumber from 9.0.26 to 9.0.27 — superseded by current upstream versions.

## Ruby release tooling

Bundler 4.0.22 refreshed the complete graph within the Gemfile’s compatibility constraints: 60 gems were updated or added, and 11 obsolete dependencies were removed. Fastlane’s move to Faraday 2 is resolved as one compatible graph rather than applying individual older PR lockfiles.

| Gem | Previous | Selected |
| --- | --- | --- |
| Fastlane | 2.232.2 | 2.240.1 |
| JSON | 2.19.2 | 2.21.2 |
| Excon | 0.112.0 | 1.7.2 |
| Faraday | 1.10.5 | 2.14.4 |
| JWT | 2.10.2 | 3.3.0 |
| Addressable | 2.8.9 | 2.9.0 |
| fastlane-plugin-fossify | 1.0.2 | 1.0.2 |

Validation used Ruby 4.0.7. `bundle check`, a frozen install of the updated lockfile, `bundle exec fastlane --version`, and `bundle exec fastlane lanes` passed. No deployment or publishing lane was run. RubyGems’ full index on its public HTTPS host was used because its compact-index subdomain is blocked by the running network policy.

## Verification

The combined required check passed with Gradle 9.8.1:

```bash
./gradlew --no-daemon --max-workers=2 --no-configuration-cache --continue \
  :app:assembleDebug :messages:assembleDebug \
  :app:lintDebug :messages:lintDebug \
  :app:testDebugUnitTest :messages:testDebugUnitTest
```

- Debug APK and messages AAR build successfully. APK signing verification passes; the APK targets arm64-v8a, minSdk 26, and targetSdk 36.
- All 28 existing JVM tests pass: 12 app and 16 messages, with no failures, errors, or skipped tests. Test XML was checked against the updated dependency build.
- Both lint tasks pass using the existing baselines. They retain 152 app warnings and 263 messages warnings; no baseline was regenerated or assertion disabled.
- Packaged wallet JS/WASM exactly match the generated assets. The packaged WebAssembly compiles, exports 59 entries including the RPC constructor/method, and has no unresolved bare `env` imports.
- Bundler's frozen install and Fastlane/plugin loading pass. All workflow and composite-action YAML parses. The Commons audit script now uses the actual JitPack registry and reports both pins current.
- The generated wrapper JAR matches Gradle's official SHA-256, and the distribution checksum is pinned.
- Optional Detekt still fails on the same existing source findings: 613 app and 460 messages (weighted totals 731 and 532). CI already treats this check as non-blocking. Its plugins load without compatibility errors.

Real Android UI, telephony, radio, caller-ID behavior on older phones, and payment behavior require suitable hardware and were not exercised. GitHub-hosted workflows were not run. All repository changes are local and uncommitted; no remote PR was merged or published.
