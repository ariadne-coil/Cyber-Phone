package org.fossify.phone.wallet

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.WalletTokenParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Survives activity recreation and owns both the spend and its cancellation work. */
class WalletSendTokenViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Confirming : State
        data object Working : State
        data class Success(val token: String, val operationId: String?) : State
        data class Failure(val message: String) : State
    }

    val state = MutableLiveData<State>(State.Idle)
    private val gate = WalletOperationGate()
    private val started = AtomicBoolean(false)
    private val abandoned = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val cancellationLock = Any()
    private var pendingOperationId: String? = null
    private var appContext: Context? = null
    private var federationId: String? = null

    fun reserve(): Boolean {
        if (!gate.acquire()) return false
        state.value = State.Confirming
        return true
    }

    fun cancelConfirmation() {
        if (state.value == State.Confirming) {
            gate.release()
            state.value = State.Idle
        }
    }

    fun send(context: Context, federation: FederationEntry, amountSats: Long, secureChannel: Boolean) {
        if (!gate.isBusy || !started.compareAndSet(false, true)) return
        val application = context.applicationContext
        appContext = application
        federationId = federation.id
        state.value = State.Working
        val timeoutSecs = if (secureChannel) 24 * 60 * 60 else 10 * 60
        val expires = System.currentTimeMillis() / 1000L + timeoutSecs
        ensureBackgroundThread {
            try {
                val spend = FedimintWalletManager.spendEcashBlocking(application, federation, amountSats, timeoutSecs)
                    ?: throw IllegalStateException(FedimintWalletManager.getLastErrorMessage().orEmpty())
                // Persist cancellation before touching an activity or delivering the token.
                synchronized(cancellationLock) {
                    pendingOperationId = spend.operationId
                    enqueueCancellation(application, federation.id, spend.operationId, if (abandoned.get()) 0 else timeoutSecs)
                }
                val token = WalletTokenParser.buildFedimintEcashMessage(federation.id, amountSats, expires, spend.notes)
                state.postValue(State.Success(token, spend.operationId))
            } catch (e: Exception) {
                started.set(false)
                gate.release()
                state.postValue(State.Failure(e.message.orEmpty()))
            }
        }
    }

    fun markDelivered() { delivered.set(true) }

    override fun onCleared() {
        synchronized(cancellationLock) {
            abandoned.set(true)
            val context = appContext
            val federation = federationId
            if (!delivered.get() && context != null && federation != null) {
                enqueueCancellation(context, federation, pendingOperationId, 0)
            }
        }
    }

    private fun enqueueCancellation(context: Context, federationId: String, operationId: String?, delaySecs: Int) {
        if (operationId.isNullOrBlank()) return
        val request = OneTimeWorkRequestBuilder<WalletEcashCancelWorker>()
            .setInputData(workDataOf(WalletEcashCancelWorker.KEY_FEDERATION_ID to federationId,
                WalletEcashCancelWorker.KEY_OPERATION_ID to operationId))
            .setInitialDelay(delaySecs.toLong(), TimeUnit.SECONDS)
            .addTag(WalletEcashCancelWorker.TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WalletEcashCancelWorker.uniqueName(federationId, operationId), ExistingWorkPolicy.REPLACE, request)
    }
}

internal class WalletOperationGate {
    private val busy = AtomicBoolean(false)
    val isBusy get() = busy.get()
    fun acquire() = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
}
