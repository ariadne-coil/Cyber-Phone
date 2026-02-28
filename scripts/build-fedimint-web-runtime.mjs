#!/usr/bin/env node

import { spawnSync } from "node:child_process"
import { existsSync, mkdirSync, readFileSync, rmSync } from "node:fs"
import os from "node:os"
import path from "node:path"

const args = process.argv.slice(2)
if (args.length !== 3) {
  console.error("Usage: build-fedimint-web-runtime.mjs <fedimint-source-root> <output-dir> <target-dir>")
  process.exit(64)
}

const [sourceRootArg, outputDirArg, targetDirArg] = args
const sourceRoot = path.resolve(sourceRootArg)
const outputDir = path.resolve(outputDirArg)
const targetDir = path.resolve(targetDirArg)
const crateDir = path.join(sourceRoot, "fedimint-client-wasm")
const lockFile = path.join(sourceRoot, "Cargo.lock")

const fail = (message) => {
  console.error(message)
  process.exit(1)
}

if (!existsSync(crateDir)) {
  fail(
    `Fedimint source is missing at ${crateDir}.\n` +
      "Run: git submodule update --init --recursive third_party/fedimint-web",
  )
}

if (!existsSync(lockFile)) {
  fail(`Fedimint Cargo.lock not found at ${lockFile}.`)
}

const env = { ...process.env }
const homeDir = env.HOME || env.USERPROFILE || os.homedir()
if (homeDir) {
  const cargoBin = path.join(homeDir, ".cargo", "bin")
  if (existsSync(cargoBin)) {
    const currentPath = env.PATH || ""
    const pieces = currentPath.split(path.delimiter).filter(Boolean)
    if (!pieces.includes(cargoBin)) {
      env.PATH = [cargoBin, currentPath].filter(Boolean).join(path.delimiter)
    }
  }
}

env.CARGO_TARGET_DIR = targetDir

const wasmGetrandomFlag = '--cfg getrandom_backend="wasm_js"'
const existingWasmRustFlags = env.CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS || ""
if (!existingWasmRustFlags.includes("getrandom_backend")) {
  env.CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS = [existingWasmRustFlags, wasmGetrandomFlag]
    .filter(Boolean)
    .join(" ")
}

const run = (command, commandArgs, options = {}) => {
  const { cwd, allowFailure = false, capture = false } = options
  const result = spawnSync(command, commandArgs, {
    cwd,
    env,
    encoding: "utf8",
    stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
  })

  if (result.error) {
    if (allowFailure) return result
    fail(`Failed to start '${command}': ${result.error.message}`)
  }

  if (result.status !== 0 && !allowFailure) {
    const stderr = (result.stderr || "").trim()
    fail(stderr ? `'${command}' failed: ${stderr}` : `'${command}' exited with status ${result.status}.`)
  }

  return result
}

const hasTool = (name) => {
  const probe = run(name, ["--version"], { allowFailure: true, capture: true })
  return !probe.error && probe.status === 0
}

if (!hasTool("cargo")) {
  fail("Required tool 'cargo' is not installed or not on PATH.")
}

if (!hasTool("clang")) {
  fail("Required tool 'clang' is not installed or not on PATH.")
}

if (!hasTool("wasm-bindgen")) {
  fail("Required tool 'wasm-bindgen' is not installed or not on PATH.")
}

if (!env.CC_wasm32_unknown_unknown) {
  env.CC_wasm32_unknown_unknown = "clang"
}
if (!env["CC_wasm32-unknown-unknown"]) {
  env["CC_wasm32-unknown-unknown"] = "clang"
}

if (hasTool("rustup")) {
  const targetList = run("rustup", ["target", "list", "--installed"], { capture: true })
  const installedTargets = (targetList.stdout || "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  if (!installedTargets.includes("wasm32-unknown-unknown")) {
    run("rustup", ["target", "add", "wasm32-unknown-unknown"])
  }
}

mkdirSync(outputDir, { recursive: true })
mkdirSync(targetDir, { recursive: true })

for (const filename of [
  "fedimint_client_wasm.js",
  "fedimint_client_wasm_bg.wasm",
  "fedimint_client_wasm.d.ts",
]) {
  rmSync(path.join(outputDir, filename), { force: true })
}

run(
  "cargo",
  [
    "build",
    "--locked",
    "--release",
    "--target",
    "wasm32-unknown-unknown",
    "-p",
    "fedimint-client-wasm",
  ],
  {
    cwd: sourceRoot,
  },
)

const rawWasm = path.join(
  targetDir,
  "wasm32-unknown-unknown",
  "release",
  "fedimint_client_wasm.wasm",
)

if (!existsSync(rawWasm)) {
  fail(`Expected wasm output not found at ${rawWasm}.`)
}

run("wasm-bindgen", [
  "--target",
  "web",
  "--out-dir",
  outputDir,
  "--out-name",
  "fedimint_client_wasm",
  rawWasm,
])

if (hasTool("wasm-opt")) {
  const optimized = path.join(outputDir, "fedimint_client_wasm_bg.wasm")
  run("wasm-opt", ["-Oz", "-o", optimized, optimized])
}

const generatedJs = path.join(outputDir, "fedimint_client_wasm.js")
const generatedJsContent = readFileSync(generatedJs, "utf8")
if (generatedJsContent.includes("from 'env'") || generatedJsContent.includes('from "env"')) {
  fail(
    "Generated Fedimint runtime still references bare module specifier 'env'. " +
      "This output is not compatible with the in-app WebView loader.",
  )
}
