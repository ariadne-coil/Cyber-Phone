# Fedimint Web Runtime Assets

Cyber Phone bundles Fedimint Web runtime assets from:

- Package: `@fedimint/fedimint-client-wasm-web`
- Version: `0.0.0-canary-ba376955df27b42d3afa7c84f1e2458d5bac019b`
- Lock file: `package-lock.json`

Bundled app assets:

- `app/src/main/assets/fedimint/fedimint_client_wasm.js`
- `app/src/main/assets/fedimint/fedimint_client_wasm_bg.wasm`
- `app/src/main/assets/fedimint/index.js` (Cyber Phone bridge asset)
- `app/src/main/assets/fedimint/worker.js` (Cyber Phone bridge asset)
- `app/src/main/assets/fedimint/engine.html` (Cyber Phone bridge asset)

Current vendored SHA-256:

- `fedimint_client_wasm.js`: `68f053be04c4900eb42ccfc49e70b4da562aa6c1deecd3be59c04d651683245a`
- `fedimint_client_wasm_bg.wasm`: `714cc7a5d8060235e0dcdc29fa084beb93251c18a7806779faaaeee76ef7b18c`

## Rebuild / Refresh Procedure

1. Run `npm pack @fedimint/fedimint-client-wasm-web@0.0.0-canary-ba376955df27b42d3afa7c84f1e2458d5bac019b`
   or `npm run pack:fedimint-web-runtime`
2. Extract the generated tarball.
3. Copy `fedimint_client_wasm.js` and `fedimint_client_wasm_bg.wasm` into `app/src/main/assets/fedimint/`.
4. Keep `index.js`, `worker.js`, and `engine.html` in sync with the bridge API expected by `FedimintWebEngine.kt`.

## Integrity Notes

- Keep package version pinned to an immutable tag/commit-derived release.
- Keep `package.json` and `package-lock.json` committed so the vendored source and integrity hash stay auditable.
- Record SHA-256 hashes of copied artifacts as part of release prep.
- Validate runtime startup with `./gradlew :app:assembleRelease -x lint`.
