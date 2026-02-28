# Fedimint Web Runtime Assets

Cyber Phone now builds the low-level Fedimint Web runtime from pinned upstream source instead of
committing the generated `wasm-bindgen` output into `app/src/main/assets`.

Pinned source:

- Submodule: `third_party/fedimint-web`
- Upstream: `https://github.com/fedimint/fedimint.git`
- Pinned commit: `e8570bc6b4f9`
- Crate: `fedimint-client-wasm`

Static source assets kept in the app repo:

- `app/src/main/assets/fedimint/index.js` (Cyber Phone wrapper module)
- `app/src/main/assets/fedimint/worker.js` (Cyber Phone worker bridge)
- `app/src/main/assets/fedimint/engine.html` (Cyber Phone bridge page)

Generated at build time:

- `build/generated/fedimintRuntime/assets/fedimint/fedimint_client_wasm.js`
- `build/generated/fedimintRuntime/assets/fedimint/fedimint_client_wasm_bg.wasm`
- `build/generated/fedimintRuntime/assets/fedimint/fedimint_client_wasm.d.ts`

## Build Requirements

- `cargo`
- `clang`
- `node`
- `wasm-bindgen`
- optional: `rustup` (used to auto-add `wasm32-unknown-unknown` if available)
- optional: `wasm-opt` from Binaryen (used for release-size optimization)

The build entrypoint is:

- `scripts/build-fedimint-web-runtime.mjs`

It is invoked automatically by the Gradle task:

- `:app:generateFedimintWebRuntime`

On Windows hosts, if `cargo` is not available on the Windows `PATH` but `wsl.exe` is available and
the repo lives on a normal drive mount, Gradle falls back to running this generator inside WSL.

## Initial Setup

1. Initialize the pinned source:
   - `git submodule update --init --recursive third_party/fedimint-web`
2. Ensure the Rust toolchain can target WebAssembly:
   - `rustup target add wasm32-unknown-unknown`
3. Install `wasm-bindgen-cli` matching upstream's pinned `wasm-bindgen` version:
   - `cargo install --locked wasm-bindgen-cli --version 0.2.100`

## Manual Validation

Run:

- `./gradlew :app:generateFedimintWebRuntime`
- `./gradlew :app:assembleRelease -x lint`

If the generated runtime is missing, `clang` is unavailable, or the generated JS still contains the
unsupported bare `env` import, the Gradle build fails with a direct error rather than silently
packaging a broken runtime.

## Notes

- The previous npm canary package lock was only provenance for a prebuilt blob and is no longer the
  source of truth.
- The pinned submodule commit is a public upstream source commit. The old npm canary hash
  `ba376955df27b42d3afa7c84f1e2458d5bac019b` was not available as a public git ref.
