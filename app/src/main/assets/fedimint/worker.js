// Web Worker for the Fedimint WASM runtime.
// Low-level module is generated at build time from third_party/fedimint-web (pinned source).

// HACK: Fixes vitest browser runner
globalThis.__vitest_browser_runner__ = { wrapDynamicImport: (foo) => foo() }

let WasmModule = null
let RpcHandlerCtor = null
let rpcHandler = null
let dbAccessHandle = null
let dbFilename = 'fedimint.db'
let currentClientName = null

const cancelledStreamIds = new Set()
let nextControlRequestId = 1_000_000

console.log('Worker - init')

const nextRequestId = () => {
  nextControlRequestId += 1
  return nextControlRequestId
}

const normalizeError = (error) => {
  if (!error) return 'Unknown error'
  if (typeof error === 'string') return error
  if (typeof error.message === 'string' && error.message.trim()) return error.message
  return String(error)
}

const sanitizeClientName = (clientName) => {
  const raw = String(clientName || '').trim().toLowerCase()
  if (!raw) return 'default'
  const sanitized = raw.replace(/[^a-z0-9._-]+/g, '_')
  return sanitized.slice(0, 96) || 'default'
}

const dbFilenameForClient = (clientName) => {
  const safe = sanitizeClientName(clientName)
  return `fedimint-${safe}.db`
}

const isRuntimeUnreachable = (error) => {
  const msg = normalizeError(error).toLowerCase()
  return msg.includes('runtimeerror') && msg.includes('unreachable')
}

const parseRpcMessage = (raw) => {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  if (typeof raw !== 'string') return null
  try {
    return JSON.parse(raw)
  } catch (_) {
    return null
  }
}

const extractMnemonicWords = (payload) => {
  if (!payload) return []

  if (Array.isArray(payload)) {
    return payload.map((word) => String(word || '').trim()).filter((word) => word.length > 0)
  }

  if (typeof payload === 'object') {
    if (Array.isArray(payload.mnemonic)) {
      return payload.mnemonic.map((word) => String(word || '').trim()).filter((word) => word.length > 0)
    }
    if (Array.isArray(payload.words)) {
      return payload.words.map((word) => String(word || '').trim()).filter((word) => word.length > 0)
    }
  }

  return []
}

const normalizeMnemonicWords = (mnemonic) => {
  if (typeof mnemonic !== 'string') return []
  return mnemonic
    .trim()
    .split(/\s+/)
    .map((w) => w.trim().toLowerCase())
    .filter((w) => w.length > 0)
}

const mnemonicWordsEqual = (left, right) => {
  if (!Array.isArray(left) || !Array.isArray(right)) return false
  if (left.length !== right.length) return false
  for (let i = 0; i < left.length; i += 1) {
    if (String(left[i]).trim().toLowerCase() !== String(right[i]).trim().toLowerCase()) {
      return false
    }
  }
  return true
}

const ensureWasmInitialized = async () => {
  if (WasmModule != null) return

  WasmModule = await import('./fedimint_client_wasm.js')
  RpcHandlerCtor = WasmModule.RpcHandler
  if (!RpcHandlerCtor) {
    throw new Error('Fedimint RpcHandler export is unavailable')
  }

  const wasmUrl = new URL('./fedimint_client_wasm_bg.wasm', import.meta.url)
  await WasmModule.default({ module_or_path: wasmUrl })
}

const cleanupHandler = async () => {
  try {
    if (rpcHandler && typeof rpcHandler.free === 'function') {
      rpcHandler.free()
    }
  } catch (_) {}
  rpcHandler = null

  try {
    if (dbAccessHandle && typeof dbAccessHandle.close === 'function') {
      dbAccessHandle.close()
    }
  } catch (_) {}
  dbAccessHandle = null
  currentClientName = null
  cancelledStreamIds.clear()
}

const ensureRpcHandler = async (filename) => {
  await ensureWasmInitialized()

  const requestedFilename =
    typeof filename === 'string' && filename.trim().length > 0 ? filename.trim() : 'fedimint.db'

  if (rpcHandler && dbAccessHandle && dbFilename === requestedFilename) {
    return
  }

  await cleanupHandler()
  dbFilename = requestedFilename

  const storage = globalThis.navigator?.storage
  if (!storage || typeof storage.getDirectory !== 'function') {
    throw new Error('OPFS is unavailable in this WebView')
  }

  const root = await storage.getDirectory()
  const fileHandle = await root.getFileHandle(dbFilename, { create: true })
  if (!fileHandle || typeof fileHandle.createSyncAccessHandle !== 'function') {
    throw new Error('FileSystemSyncAccessHandle is unavailable in this WebView')
  }

  dbAccessHandle = await fileHandle.createSyncAccessHandle()
  rpcHandler = await new RpcHandlerCtor(dbAccessHandle)
}

const sendRpc = (request, onMessage) => {
  if (!rpcHandler) {
    throw new Error('Fedimint RpcHandler is not initialized')
  }
  rpcHandler.rpc(JSON.stringify(request), (raw) => {
    const msg = parseRpcMessage(raw)
    if (msg) onMessage(msg)
  })
}

const rpcSingle = async (type, payload = {}, timeoutMs = 120_000) =>
  new Promise((resolve, reject) => {
    const requestId = nextRequestId()
    let settled = false

    const finish = (fn) => (value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      fn(value)
    }

    const resolveOnce = finish(resolve)
    const rejectOnce = finish(reject)

    const timer = setTimeout(() => {
      rejectOnce(new Error(`${type} timed out`))
    }, timeoutMs)

    sendRpc(
      {
        request_id: requestId,
        type,
        ...payload,
      },
      (msg) => {
        if (msg.request_id !== requestId) return

        if (msg.type === 'error') {
          rejectOnce(new Error(normalizeError(msg.error)))
          return
        }
        if (msg.type === 'data') {
          resolveOnce(msg.data)
          return
        }
        if (msg.type === 'end') {
          resolveOnce(null)
        }
      },
    )
  })

const ensureMnemonicReady = async () => {
  let hasMnemonic = false

  try {
    const existing = await rpcSingle('get_mnemonic', {}, 20_000)
    hasMnemonic = extractMnemonicWords(existing).length > 0
  } catch (_) {
    hasMnemonic = false
  }

  if (hasMnemonic) return

  const generated = await rpcSingle('generate_mnemonic', {}, 20_000)
  if (extractMnemonicWords(generated).length > 0) return

  // Some runtimes may not return words in the response. Verify after generation.
  const recheck = await rpcSingle('get_mnemonic', {}, 20_000)
  if (extractMnemonicWords(recheck).length > 0) return

  throw new Error('Failed to initialize wallet mnemonic')
}

const setMnemonicIfNeeded = async (mnemonic) => {
  const desired = normalizeMnemonicWords(mnemonic)
  if (desired.length === 0) {
    throw new Error('Mnemonic is required')
  }

  let existingWords = []
  try {
    const existing = await rpcSingle('get_mnemonic', {}, 20_000)
    existingWords = extractMnemonicWords(existing)
  } catch (_) {
    existingWords = []
  }

  if (existingWords.length > 0) {
    if (mnemonicWordsEqual(existingWords, desired)) {
      return existingWords
    }
    throw new Error('Wallet already initialized with a different mnemonic')
  }

  let applied = false
  const attempts = [
    () => rpcSingle('set_mnemonic', { mnemonic: desired }, 30_000),
    () => rpcSingle('set_mnemonic', { words: desired }, 30_000),
    () => rpcSingle('recover_mnemonic', { mnemonic: desired }, 30_000),
    () => rpcSingle('recover_mnemonic', { words: desired }, 30_000),
  ]

  let lastErr = null
  for (const attempt of attempts) {
    try {
      await attempt()
      applied = true
      break
    } catch (e) {
      lastErr = e
    }
  }
  if (!applied) {
    throw new Error(`Failed to set mnemonic: ${normalizeError(lastErr)}`)
  }

  const check = await rpcSingle('get_mnemonic', {}, 20_000)
  const checkWords = extractMnemonicWords(check)
  if (!mnemonicWordsEqual(checkWords, desired)) {
    throw new Error('Mnemonic verification failed after restore')
  }
  return checkWords
}

const forwardRpcToLegacyClient = (requestId, msg) => {
  if (!msg) return

  if (msg.type === 'data') {
    self.postMessage({ type: 'rpcResponse', requestId, data: msg.data })
    return
  }

  if (msg.type === 'error') {
    cancelledStreamIds.delete(requestId)
    self.postMessage({ type: 'rpcResponse', requestId, error: normalizeError(msg.error) })
    return
  }

  if (msg.type === 'end') {
    cancelledStreamIds.delete(requestId)
    self.postMessage({ type: 'rpcResponse', requestId, end: true })
    return
  }

  if (msg.type === 'log') {
    self.postMessage({
      type: 'log',
      level: typeof msg.level === 'string' ? msg.level : 'debug',
      message: typeof msg.message === 'string' ? msg.message : 'Fedimint log',
    })
  }
}

self.onmessage = async (event) => {
  const { type, payload, requestId } = event.data

  try {
    if (type === 'init') {
      await ensureWasmInitialized()
      if (payload?.filename) {
        await ensureRpcHandler(payload.filename)
      }
      self.postMessage({ type: 'initialized', data: { filename: dbFilename }, requestId })
    } else if (type === 'getMnemonic') {
      const requestedFilename = payload?.filename || (payload?.clientName ? dbFilenameForClient(payload.clientName) : dbFilename)
      await ensureRpcHandler(requestedFilename)
      const data = await rpcSingle('get_mnemonic', {}, 20_000)
      const words = extractMnemonicWords(data)
      self.postMessage({
        type: 'getMnemonic',
        data: { words, mnemonic: words.join(' ') },
        requestId,
      })
    } else if (type === 'setMnemonic') {
      const requestedFilename = payload?.filename || (payload?.clientName ? dbFilenameForClient(payload.clientName) : dbFilename)
      await ensureRpcHandler(requestedFilename)
      const words = await setMnemonicIfNeeded(payload?.mnemonic)
      self.postMessage({
        type: 'setMnemonic',
        data: { success: true, words, mnemonic: words.join(' ') },
        requestId,
      })
    } else if (type === 'open') {
      const clientName = payload?.clientName?.toString().trim()
      if (!clientName) {
        throw new Error('Client name is required')
      }

      const requestedFilename = payload?.filename || dbFilenameForClient(clientName)
      await ensureRpcHandler(requestedFilename)
      await ensureMnemonicReady()
      try {
        await rpcSingle('open_client', { client_name: clientName })
      } catch (e) {
        if (isRuntimeUnreachable(e)) {
          throw new Error(
            'Fedimint runtime reported unreachable while opening wallet state. ' +
              'Automatic DB deletion is disabled for safety. Restore from backup or migrate state manually.',
          )
        } else {
          throw e
        }
      }
      currentClientName = clientName
      self.postMessage({
        type: 'open',
        data: { success: true, filename: dbFilename },
        requestId,
      })
    } else if (type === 'join') {
      const inviteCode = payload?.inviteCode?.toString().trim()
      const joinClientName = payload?.clientName?.toString().trim()
      if (!inviteCode) {
        throw new Error('Invite code is required')
      }
      if (!joinClientName) {
        throw new Error('Client name is required')
      }

      const requestedFilename = payload?.filename || dbFilenameForClient(joinClientName)
      await ensureRpcHandler(requestedFilename)
      await ensureMnemonicReady()
      try {
        await rpcSingle('join_federation', {
          invite_code: inviteCode,
          client_name: joinClientName,
          force_recover: false,
        })
      } catch (e) {
        if (isRuntimeUnreachable(e)) {
          throw new Error(
            'Fedimint runtime reported unreachable while joining federation state. ' +
              'Automatic DB deletion is disabled for safety. Restore from backup or migrate state manually.',
          )
        } else {
          throw e
        }
      }
      currentClientName = joinClientName
      self.postMessage({
        type: 'join',
        data: { success: true, filename: dbFilename },
        requestId,
      })
    } else if (type === 'rpc') {
      const { module, method, body } = payload
      if (!rpcHandler || !currentClientName) {
        self.postMessage({
          type: 'error',
          error: 'Fedimint client is not initialized',
          requestId,
        })
        return
      }

      cancelledStreamIds.delete(requestId)
      sendRpc(
        {
          request_id: requestId,
          type: 'client_rpc',
          client_name: currentClientName,
          module,
          method,
          payload: body || {},
        },
        (msg) => {
          if (msg.request_id !== requestId) return
          if (cancelledStreamIds.has(requestId) && msg.type !== 'end') return
          forwardRpcToLegacyClient(requestId, msg)
        },
      )
    } else if (type === 'unsubscribe') {
      if (rpcHandler) {
        cancelledStreamIds.add(requestId)
        sendRpc(
          {
            request_id: nextRequestId(),
            type: 'cancel_rpc',
            cancel_request_id: requestId,
          },
          () => {},
        )
      }
    } else if (type === 'cleanup') {
      await cleanupHandler()
      self.postMessage({ type: 'cleanup', data: { filename: dbFilename }, requestId })
      close()
    } else {
      self.postMessage({
        type: 'error',
        error: 'Unknown message type',
        requestId,
      })
    }
  } catch (e) {
    console.error('ERROR', e)
    self.postMessage({ type: 'error', error: normalizeError(e), requestId })
  }
}

// self.postMessage({ type: 'init', data: {} })
