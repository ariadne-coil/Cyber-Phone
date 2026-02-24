# Fedimint Web Runtime Assets

Cyber Phone bundles Fedimint Web runtime assets from:

- Package: `@fedimint/fedimint-client-wasm-web`
- Version: `0.0.0-canary-ba376955df27b42d3afa7c84f1e2458d5bac019b`

Bundled app assets:

- `app/src/main/assets/fedimint/fedimint_client_wasm.js`
- `app/src/main/assets/fedimint/fedimint_client_wasm_bg.wasm`
- `app/src/main/assets/fedimint/index.js`
- `app/src/main/assets/fedimint/worker.js`
- `app/src/main/assets/fedimint/engine.html`

## Rebuild / Refresh Procedure

1. Run `npm pack @fedimint/fedimint-client-wasm-web@0.0.0-canary-ba376955df27b42d3afa7c84f1e2458d5bac019b`
2. Extract the generated tarball.
3. Copy runtime outputs (`fedimint_client_wasm.js`, `fedimint_client_wasm_bg.wasm`, `index.js`, `worker.js`) into `app/src/main/assets/fedimint/`.
4. Keep `engine.html` in sync with the bridge API expected by `FedimintWebEngine.kt`.

## Integrity Notes

- Keep package version pinned to an immutable tag/commit-derived release.
- Record SHA-256 hashes of copied artifacts as part of release prep.
- Validate runtime startup with `./gradlew :app:assembleRelease -x lint`.
