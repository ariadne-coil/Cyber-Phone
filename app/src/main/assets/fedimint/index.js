const LOG_LEVELS = ["debug", "info", "warn", "error", "none"]
const DEFAULT_CLIENT_NAME = "fm-default"

class Logger {
  constructor(level = "none") {
    this.level = level
  }

  setLevel(level) {
    this.level = level
  }

  normalizeLevel(level) {
    const normalized = String(level || "").trim().toLowerCase()
    return LOG_LEVELS.includes(normalized) ? normalized : "info"
  }

  shouldLog(level) {
    const normalized = this.normalizeLevel(level)
    return (
      normalized !== "none" &&
      LOG_LEVELS.indexOf(this.normalizeLevel(this.level)) <= LOG_LEVELS.indexOf(normalized)
    )
  }

  log(level, message, ...rest) {
    const normalized = this.normalizeLevel(level)
    if (!this.shouldLog(normalized)) return
    console[normalized](`[${normalized.toUpperCase()}] ${message}`, ...rest)
  }

  debug(message, ...rest) {
    this.log("debug", message, ...rest)
  }

  info(message, ...rest) {
    this.log("info", message, ...rest)
  }

  warn(message, ...rest) {
    this.log("warn", message, ...rest)
  }

  error(message, ...rest) {
    this.log("error", message, ...rest)
  }
}

const logger = new Logger("none")

class WorkerClient {
  constructor() {
    this.requestCounter = 0
    this.requestCallbacks = new Map()
    this.initPromise = null
    this.worker = new Worker(new URL("./worker.js", import.meta.url), { type: "module" })
    this.worker.onmessage = this.handleWorkerMessage.bind(this)
    this.worker.onerror = this.handleWorkerError.bind(this)
    logger.info("WorkerClient instantiated")
    logger.debug("WorkerClient", this.worker)
  }

  initialize() {
    if (!this.initPromise) {
      this.initPromise = this.sendSingleMessage("init")
    }
    return this.initPromise
  }

  handleWorkerLogs(message) {
    const { level, message: text, ...rest } = message || {}
    logger.log(level || "info", text || "Worker log", ...Object.values(rest))
  }

  handleWorkerError(error) {
    logger.error("Worker error", error)
  }

  handleWorkerMessage(event) {
    const { type, requestId, ...payload } = event.data || {}
    if (type === "log") {
      this.handleWorkerLogs(event.data)
    }

    const callback = this.requestCallbacks.get(requestId)
    logger.debug("WorkerClient - handleWorkerMessage", event.data)

    if (callback) {
      callback(payload)
      return
    }

    logger.warn(
      "WorkerClient - handleWorkerMessage - received message with no callback",
      requestId,
      event.data,
    )
  }

  sendSingleMessage(type, payload) {
    return new Promise((resolve, reject) => {
      const requestId = ++this.requestCounter
      logger.debug("WorkerClient - sendSingleMessage", requestId, type, payload)

      this.requestCallbacks.set(requestId, (response) => {
        this.requestCallbacks.delete(requestId)
        logger.debug("WorkerClient - sendSingleMessage - response", requestId, response)

        if (response && Object.prototype.hasOwnProperty.call(response, "data")) {
          resolve(response.data)
          return
        }

        if (response && Object.prototype.hasOwnProperty.call(response, "error")) {
          reject(response.error)
          return
        }

        logger.warn("WorkerClient - sendSingleMessage - malformed response", requestId, response)
      })

      this.worker.postMessage({ type, payload, requestId })
    })
  }

  rpcStream(moduleName, method, body, onData, onError, onEnd = () => {}) {
    const requestId = ++this.requestCounter
    logger.debug("WorkerClient - rpcStream", requestId, moduleName, method, body)

    let streamRegistered = false
    let cancelRequested = false

    const unsubscribe = () => {
      if (!streamRegistered) {
        cancelRequested = true
        return
      }

      this.worker?.postMessage({ type: "unsubscribe", requestId })
      this.requestCallbacks.delete(requestId)
    }

    this.requestCallbacks.set(requestId, (response) => {
      if (response && Object.prototype.hasOwnProperty.call(response, "error")) {
        onError(response.error)
        return
      }

      if (response && Object.prototype.hasOwnProperty.call(response, "data")) {
        onData(response.data)
        return
      }

      if (response && Object.prototype.hasOwnProperty.call(response, "end")) {
        this.requestCallbacks.delete(requestId)
        onEnd()
      }
    })

    this.worker.postMessage({
      type: "rpc",
      payload: {
        module: moduleName,
        method,
        body,
      },
      requestId,
    })

    streamRegistered = true
    if (cancelRequested) {
      unsubscribe()
    }

    return unsubscribe
  }

  rpcSingle(moduleName, method, body) {
    logger.debug("WorkerClient - rpcSingle", moduleName, method, body)
    return new Promise((resolve, reject) => {
      this.rpcStream(moduleName, method, body, resolve, reject)
    })
  }

  cleanup() {
    this.worker.terminate()
    this.initPromise = null
    this.requestCallbacks.clear()
  }

  _getRequestCounter() {
    return this.requestCounter
  }

  _getRequestCallbackMap() {
    return this.requestCallbacks
  }
}

class MintModule {
  constructor(client) {
    this.client = client
  }

  async redeemEcash(notes) {
    await this.client.rpcSingle("mint", "reissue_external_notes", {
      oob_notes: notes,
      extra_meta: null,
    })
  }

  async reissueExternalNotes(notes, extraMeta) {
    return this.client.rpcSingle("mint", "reissue_external_notes", {
      oob_notes: notes,
      extra_meta: extraMeta,
    })
  }

  subscribeReissueExternalNotes(operationId, onData = () => {}, onEnd = () => {}) {
    return this.client.rpcStream(
      "mint",
      "subscribe_reissue_external_notes",
      { operation_id: operationId },
      onData,
      onEnd,
    )
  }

  async spendNotes(minAmount, tryCancelAfter, includeInvite, extraMeta) {
    return this.client.rpcSingle("mint", "spend_notes", {
      min_amount: minAmount,
      try_cancel_after: tryCancelAfter,
      include_invite: includeInvite,
      extra_meta: extraMeta,
    })
  }

  async validateNotes(notes) {
    return this.client.rpcSingle("mint", "validate_notes", { oob_notes: notes })
  }

  async tryCancelSpendNotes(operationId) {
    await this.client.rpcSingle("mint", "try_cancel_spend_notes", {
      operation_id: operationId,
    })
  }

  subscribeSpendNotes(operationId, onData = () => {}, onEnd = () => {}) {
    return this.client.rpcStream(
      "mint",
      "subscribe_spend_notes",
      { operation_id: operationId },
      (payload) => onData(payload),
      onEnd,
    )
  }

  async awaitSpendOobRefund(operationId) {
    return this.client.rpcSingle("mint", "await_spend_oob_refund", {
      operation_id: operationId,
    })
  }
}

class BalanceModule {
  constructor(client) {
    this.client = client
  }

  async getBalance() {
    return this.client.rpcSingle("", "get_balance", {})
  }

  subscribeBalance(onData = () => {}, onEnd = () => {}) {
    return this.client.rpcStream(
      "",
      "subscribe_balance_changes",
      {},
      (payload) => onData(parseInt(payload, 10)),
      onEnd,
    )
  }
}

class LightningModule {
  constructor(client) {
    this.client = client
  }

  async createInvoiceWithGateway(amount, description, expiryTime = null, extraMeta = {}, gateway) {
    return this.client.rpcSingle("ln", "create_bolt11_invoice", {
      amount,
      description,
      expiry_time: expiryTime,
      extra_meta: extraMeta,
      gateway,
    })
  }

  async createInvoice(amount, description, expiryTime = null, extraMeta = {}) {
    await this.updateGatewayCache()
    const gatewayInfo = await this._getDefaultGatewayInfo()
    return this.client.rpcSingle("ln", "create_bolt11_invoice", {
      amount,
      description,
      expiry_time: expiryTime,
      extra_meta: extraMeta,
      gateway: gatewayInfo.info,
    })
  }

  async payInvoiceWithGateway(invoice, gateway, extraMeta = {}) {
    return this.client.rpcSingle("ln", "pay_bolt11_invoice", {
      maybe_gateway: gateway,
      invoice,
      extra_meta: extraMeta,
    })
  }

  async _getDefaultGatewayInfo() {
    return (await this.listGateways())[0]
  }

  async payInvoice(invoice, extraMeta = {}) {
    await this.updateGatewayCache()
    const gatewayInfo = await this._getDefaultGatewayInfo()
    return this.client.rpcSingle("ln", "pay_bolt11_invoice", {
      maybe_gateway: gatewayInfo.info,
      invoice,
      extra_meta: extraMeta,
    })
  }

  subscribeLnPay(operationId, onData = () => {}, onEnd = () => {}) {
    return this.client.rpcStream("ln", "subscribe_ln_pay", { operation_id: operationId }, onData, onEnd)
  }

  subscribeLnReceive(operationId, onData = () => {}, onEnd = () => {}) {
    return this.client.rpcStream("ln", "subscribe_ln_receive", { operation_id: operationId }, onData, onEnd)
  }

  async getGateway(gatewayId = null, forceInternal = false) {
    return this.client.rpcSingle("ln", "get_gateway", {
      gateway_id: gatewayId,
      force_internal: forceInternal,
    })
  }

  async listGateways() {
    return this.client.rpcSingle("ln", "list_gateways", {})
  }

  async updateGatewayCache() {
    return this.client.rpcSingle("ln", "update_gateway_cache", {})
  }
}

class RecoveryModule {
  constructor(client) {
    this.client = client
  }

  async hasPendingRecoveries() {
    return this.client.rpcSingle("", "has_pending_recoveries", {})
  }

  async waitForAllRecoveries() {
    await this.client.rpcSingle("", "wait_for_all_recoveries", {})
  }

  subscribeToRecoveryProgress(onData, onEnd) {
    return this.client.rpcStream("", "subscribe_to_recovery_progress", {}, onData, onEnd)
  }
}

class FederationModule {
  constructor(client) {
    this.client = client
  }

  async getConfig() {
    return this.client.rpcSingle("", "get_config", {})
  }

  async getFederationId() {
    return this.client.rpcSingle("", "get_federation_id", {})
  }

  async getInviteCode(peer) {
    return this.client.rpcSingle("", "get_invite_code", { peer })
  }

  async listOperations() {
    return this.client.rpcSingle("", "list_operations", {})
  }
}

class FedimintWallet {
  constructor(skipInitialize = false) {
    this._openPromise = new Promise((resolve) => {
      this._resolveOpen = resolve
    })
    this._isOpen = false
    this._client = new WorkerClient()
    this.mint = new MintModule(this._client)
    this.lightning = new LightningModule(this._client)
    this.balance = new BalanceModule(this._client)
    this.federation = new FederationModule(this._client)
    this.recovery = new RecoveryModule(this._client)

    logger.info("FedimintWallet instantiated")

    if (!skipInitialize) {
      this.initialize()
    }
  }

  async initialize() {
    logger.info("Initializing WorkerClient")
    await this._client.initialize()
    logger.info("WorkerClient initialized")
  }

  async waitForOpen() {
    if (this._isOpen) {
      return
    }
    await this._openPromise
  }

  async open(clientName = DEFAULT_CLIENT_NAME) {
    await this._client.initialize()
    if (this._isOpen) {
      throw new Error("The FedimintWallet is already open.")
    }

    const { success } = await this._client.sendSingleMessage("open", { clientName })
    if (success) {
      this._isOpen = true
      this._resolveOpen()
    }
    return success
  }

  async joinFederation(inviteCode, clientName = DEFAULT_CLIENT_NAME) {
    await this._client.initialize()
    if (this._isOpen) {
      throw new Error(
        "The FedimintWallet is already open. You can only call `joinFederation` on closed clients.",
      )
    }

    const response = await this._client.sendSingleMessage("join", { inviteCode, clientName })
    if (response.success) {
      this._isOpen = true
      this._resolveOpen()
    }
  }

  async cleanup() {
    this._openPromise = null
    this._isOpen = false
    this._client.cleanup()
  }

  isOpen() {
    return this._isOpen
  }

  setLogLevel(level) {
    logger.setLevel(level)
    logger.info(`Log level set to ${level}.`)
  }
}

export { FedimintWallet }
