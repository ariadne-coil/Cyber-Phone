import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test, after } from 'node:test'
import { pathToFileURL } from 'node:url'
import vm from 'node:vm'

const root = new URL('../', import.meta.url)
const assets = new URL('app/src/main/assets/fedimint/', root)
const temporary = await mkdtemp(join(tmpdir(), 'cyberphone-fedimint-test-'))
// Load the actual ESM wrapper without changing the repository's package configuration.
const wrapper = join(temporary, 'index.mjs')
await writeFile(wrapper, await readFile(new URL('index.js', assets)))
const { FedimintWallet } = await import(pathToFileURL(wrapper))
after(() => rm(temporary, { recursive: true, force: true }))

function transport(onRpc = () => ['operation-id', 'ecash-notes']) {
  const requests = []
  globalThis.Worker = class {
    postMessage(request) {
      requests.push(request)
      queueMicrotask(async () => {
        const { type, requestId, payload } = request
        let data
        if (type === 'init') data = {}
        else if (type === 'open' || type === 'join') data = { success: true }
        else if (type === 'rpc') {
          data = payload.method === 'get_federation_id' ? 'federation-id' : await onRpc(payload)
        } else return
        this.onmessage({ data: { requestId, data } })
        if (type === 'rpc') this.onmessage({ data: { requestId, end: true } })
      })
    }
    terminate() {}
  }
  return requests
}

const html = await readFile(new URL('engine.html', assets), 'utf8')
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]

function engine() {
  const pending = new Map()
  let id = 0
  const context = vm.createContext({
    addEventListener() {},
    CyberPhoneFedimint: {
      onResponse(id, json) { pending.get(id)(JSON.parse(json)); pending.delete(id) },
      onError(message) { throw new Error(message) },
    },
    walletConstructor: FedimintWallet,
    setTimeout, clearTimeout,
  })
  // Browser startup is the only substituted part; the production handlers and queue execute unchanged.
  vm.runInContext(script.slice(0, script.lastIndexOf('      bootstrap().catch')) +
    '\nFedimintWallet = walletConstructor;', context)
  return (method, params = {}) => new Promise(resolve => {
    const requestId = ++id
    pending.set(requestId, resolve)
    context.__cyber_fedimint.request(JSON.stringify({ id: requestId, method, params }))
  })
}

test('spend_notes sends amount and Serde Duration and retains its federation', { timeout: 5000 }, async () => {
  const requests = transport()
  const wallet = new FedimintWallet(true)
  await wallet.open('client-a')
  assert.deepEqual(await wallet.mint.spendNotes(123000, 600, false, {}), ['operation-id', 'ecash-notes'])
  const rpc = requests.find(request => request.type === 'rpc').payload
  assert.deepEqual(rpc, { module: 'mint', clientName: 'client-a', method: 'spend_notes',
    body: { amount: 123000, try_cancel_after: { secs: 600, nanos: 0 }, include_invite: false, extra_meta: {} } })
})

test('SDK preserves absent and zero cancellation durations and rejects unsafe amounts', async () => {
  const requests = transport()
  const wallet = new FedimintWallet(true)
  await wallet.open('client-a')
  await wallet.mint.spendNotes(1000, null, false, {})
  await wallet.mint.spendNotes(1000, 0, false, {})
  assert.deepEqual(requests.filter(r => r.type === 'rpc').map(r => r.payload.body.try_cancel_after),
    [null, { secs: 0, nanos: 0 }])
  for (const amount of [0, -1, NaN, Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(wallet.mint.spendNotes(amount, 600, false, {}), /Invalid ecash amount/)
  }
  await assert.rejects(wallet.mint.spendNotes(1000, -1, false, {}), /Invalid ecash cancellation/)
  assert.equal(requests.filter(r => r.type === 'rpc').length, 2)
})

test('engine normalizes the Rust tuple and preserves zero timeout', { timeout: 5000 }, async () => {
  const requests = transport()
  const request = engine()
  assert.equal((await request('openOrJoin', { clientName: 'client-a' })).ok, true)
  assert.deepEqual(await request('spendNotes', { expectedClientName: 'client-a', amountMsats: 1000,
    tryCancelAfterSecs: 0 }), { ok: true, result: { operation_id: 'operation-id', notes: 'ecash-notes' } })
  assert.deepEqual(requests.find(r => r.payload?.method === 'spend_notes').payload.body.try_cancel_after,
    { secs: 0, nanos: 0 })
})

test('engine rejects malformed spend tuples and mismatched federations', { timeout: 5000 }, async () => {
  const requests = transport(() => ['operation-id', ''])
  const request = engine()
  await request('openOrJoin', { clientName: 'client-a' })
  const wrong = await request('spendNotes', { expectedClientName: 'client-b', amountMsats: 1000 })
  assert.equal(wrong.ok, false)
  assert.match(wrong.error, /federation does not match/)
  assert.equal(requests.filter(r => r.payload?.method === 'spend_notes').length, 0)
  const malformed = await request('spendNotes', { expectedClientName: 'client-a', amountMsats: 1000 })
  assert.equal(malformed.ok, false)
  assert.match(malformed.error, /Invalid spend_notes response/)
})

test('client switching waits for an in-flight spend and cannot redirect it', { timeout: 5000 }, async () => {
  let finishSpend, enteredSpend
  const entered = new Promise(resolve => { enteredSpend = resolve })
  const result = new Promise(resolve => { finishSpend = resolve })
  const requests = transport(() => { enteredSpend(); return result })
  const request = engine()
  await request('openOrJoin', { clientName: 'client-a' })
  const spend = request('spendNotes', { expectedClientName: 'client-a', amountMsats: 1000 })
  await entered
  const switchClient = request('openOrJoin', { clientName: 'client-b' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(requests.filter(r => r.type === 'open').length, 1)
  finishSpend(['operation-a', 'notes-a'])
  assert.deepEqual(await spend, { ok: true, result: { operation_id: 'operation-a', notes: 'notes-a' } })
  assert.equal((await switchClient).ok, true)
  assert.equal(requests.find(r => r.payload?.method === 'spend_notes').payload.clientName, 'client-a')
  assert.equal(requests.filter(r => r.type === 'open').at(-1).payload.clientName, 'client-b')
})

test('worker rejects stale federation requests before calling Rust', async () => {
  const replies = [], calls = []
  const self = { postMessage(message) { replies.push(message) } }
  globalThis.self = self
  const workerFile = join(temporary, 'worker.mjs')
  await writeFile(workerFile, await readFile(new URL('worker.js', assets), 'utf8') +
    '\nexport function setTestClient(handler) { rpcHandler = handler; currentClientName = "client-a"; }')
  const { setTestClient } = await import(pathToFileURL(workerFile))
  setTestClient({ rpc(json, callback) {
      const request = JSON.parse(json)
      calls.push(request)
      callback(JSON.stringify({ type: 'data', request_id: request.request_id, data: ['op', 'notes'] }))
  } })
  const payload = { module: 'mint', method: 'spend_notes', body: { amount: 1000 } }
  await self.onmessage({ data: { type: 'rpc', requestId: 1, payload: { ...payload, clientName: 'client-b' } } })
  assert.equal(calls.length, 0)
  assert.match(replies[0].error, /does not match/)
  await self.onmessage({ data: { type: 'rpc', requestId: 2, payload: { ...payload, clientName: 'client-a' } } })
  assert.equal(calls[0].client_name, 'client-a')
  assert.deepEqual(JSON.parse(JSON.stringify(replies[1].data)), ['op', 'notes'])
})

const rustSource = new URL('third_party/fedimint-web/modules/fedimint-mint-client/src/lib.rs', root)
test('pinned Rust source agrees with the spend RPC contract', { skip: !existsSync(rustSource) }, async () => {
  const source = await readFile(rustSource, 'utf8')
  const request = source.match(/struct SpendNotesRequest\s*\{([^}]+)\}/)[1]
  assert.match(request, /amount: Amount/)
  assert.doesNotMatch(request, /min_amount/)
  assert.match(request, /try_cancel_after: Option<Duration>/)
  assert.match(source, /pub async fn spend_notes_with_selector[\s\S]*?anyhow::Result<\(OperationId, OOBNotes\)>/)
})
