package org.fossify.messages.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.usableScreenSize
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.activities.VCardViewerActivity
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemAttachmentImageBinding
import org.fossify.messages.databinding.ItemAttachmentVcardBinding
import org.fossify.messages.databinding.ItemMessageBinding
import org.fossify.messages.databinding.ItemThreadDateTimeBinding
import org.fossify.messages.databinding.ItemThreadErrorBinding
import org.fossify.messages.databinding.ItemThreadSendingBinding
import org.fossify.messages.databinding.ItemThreadSuccessBinding
import org.fossify.messages.dialogs.DeleteConfirmationDialog
import org.fossify.messages.dialogs.MessageDetailsDialog
import org.fossify.messages.dialogs.SelectTextDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.extensions.isImageMimeType
import org.fossify.messages.extensions.isVCardMimeType
import org.fossify.messages.extensions.isVideoMimeType
import org.fossify.messages.extensions.launchViewIntent
import org.fossify.messages.extensions.startContactDetailsIntent
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.helpers.EXTRA_VCARD_URI
import org.fossify.messages.helpers.EXTRA_WALLET_AUTO_PAY
import org.fossify.messages.helpers.EXTRA_WALLET_DESTINATION
import org.fossify.messages.helpers.EXTRA_WALLET_FEDERATION_ID_HINT
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_AMOUNT_SATS
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_ID
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_THREAD_ID
import org.fossify.messages.helpers.EXTRA_WALLET_SECURE_CHANNEL
import org.fossify.messages.helpers.EXTRA_WALLET_TOKEN_TEXT
import org.fossify.messages.helpers.THREAD_DATE_TIME
import org.fossify.messages.helpers.THREAD_RECEIVED_MESSAGE
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_ERROR
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_SENDING
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_SENT
import org.fossify.messages.helpers.WALLET_REQUEST_ACTION_APPROVE
import org.fossify.messages.helpers.WALLET_REQUEST_ACTION_DENY
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.WalletPaymentRequestStateManager
import org.fossify.messages.helpers.generateStableId
import org.fossify.messages.helpers.setupDocumentPreview
import org.fossify.messages.helpers.setupVCardPreview
import org.fossify.messages.helpers.WalletTokenParser
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Message
import org.fossify.messages.models.ThreadItem
import org.fossify.messages.models.ThreadItem.ThreadDateTime
import org.fossify.messages.models.ThreadItem.ThreadError
import org.fossify.messages.models.ThreadItem.ThreadSending
import org.fossify.messages.models.ThreadItem.ThreadSent
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class ThreadAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    val isRecycleBin: Boolean,
    val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean) -> Unit
) : MyRecyclerViewListAdapter<ThreadItem>(activity, recyclerView, ThreadItemDiffCallback(), itemClick) {
    private var fontSize = activity.getTextSize()
    private var reactionsByMessageId: Map<Long, String> = emptyMap()
    private val revealedWalletMessages = HashSet<Long>()

    @SuppressLint("MissingPermission")
    private val hasMultipleSIMCards = (activity.subscriptionManagerCompat().activeSubscriptionInfoList?.size ?: 0) > 1
    private val maxChatBubbleWidth = (activity.usableScreenSize.x * 0.8f).toInt()

    companion object {
        private const val MAX_MEDIA_HEIGHT_RATIO = 3
        private const val SIM_BITS = 21
        private const val SIM_MASK = (1L shl SIM_BITS) - 1
    }

    init {
        setupDragListener(true)
        setHasStableIds(false)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    fun setReactions(map: Map<Long, String>) {
        reactionsByMessageId = map
        notifyDataSetChanged()
    }

    override fun getActionMenuId() = R.menu.cab_thread

    override fun prepareActionMode(menu: Menu) {
        val isOneItemSelected = isOneItemSelected()
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasText = selectedMessages.any { it.body.isNotEmpty() }
        val showSaveAs = getSelectedItems().all {
            it is Message && (it.attachment?.attachments?.size ?: 0) > 0
        } && getSelectedAttachments().isNotEmpty()

        menu.apply {
            findItem(R.id.cab_copy_to_clipboard).isVisible = hasText
            findItem(R.id.cab_save_as).isVisible = showSaveAs
            findItem(R.id.cab_share).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_forward_message).isVisible = isOneItemSelected
            findItem(R.id.cab_select_text).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_properties).isVisible = isOneItemSelected
            findItem(R.id.cab_restore).isVisible = isRecycleBin
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_to_clipboard -> copyToClipboard()
            R.id.cab_save_as -> saveAs()
            R.id.cab_share -> shareText()
            R.id.cab_forward_message -> forwardMessage()
            R.id.cab_select_text -> selectText()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_restore -> askConfirmRestore()
            R.id.cab_select_all -> selectAll()
            R.id.cab_properties -> showMessageDetails()
        }
    }

    override fun getSelectableItemCount() = currentList.filterIsInstance<Message>().size

    override fun getIsItemSelectable(position: Int) = !isThreadDateTime(position)

    override fun getItemSelectionKey(position: Int): Int? {
        return (currentList.getOrNull(position) as? Message)?.getSelectionKey()
    }

    override fun getItemKeyPosition(key: Int): Int {
        return currentList.indexOfFirst { (it as? Message)?.getSelectionKey() == key }
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = when (viewType) {
            THREAD_DATE_TIME -> ItemThreadDateTimeBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_ERROR -> ItemThreadErrorBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENT -> ItemThreadSuccessBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENDING -> ItemThreadSendingBinding.inflate(layoutInflater, parent, false)
            else -> ItemMessageBinding.inflate(layoutInflater, parent, false)
        }

        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isClickable = item is ThreadError || item is Message
        val isLongClickable = item is Message
        holder.bindView(item, isClickable, isLongClickable) { itemView, _ ->
            when (item) {
                is ThreadDateTime -> setupDateTime(itemView, item)
                is ThreadError -> setupThreadError(itemView)
                is ThreadSent -> setupThreadSuccess(itemView, item.delivered)
                is ThreadSending -> setupThreadSending(itemView)
                is Message -> setupView(holder, itemView, item)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is Message -> item.getStableId()
            is ThreadDateTime -> {
                val sim = (item.simID.hashCode().toLong() and SIM_MASK)
                val key = (item.date.toLong() shl SIM_BITS) or sim
                generateStableId(THREAD_DATE_TIME, key)
            }
            is ThreadError -> generateStableId(THREAD_SENT_MESSAGE_ERROR, item.messageId)
            is ThreadSending -> generateStableId(THREAD_SENT_MESSAGE_SENDING, item.messageId)
            is ThreadSent -> generateStableId(THREAD_SENT_MESSAGE_SENT, item.messageId)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ThreadDateTime -> THREAD_DATE_TIME
            is ThreadError -> THREAD_SENT_MESSAGE_ERROR
            is ThreadSent -> THREAD_SENT_MESSAGE_SENT
            is ThreadSending -> THREAD_SENT_MESSAGE_SENDING
            is Message -> if (item.isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
        }
    }

    private fun copyToClipboard() {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val textToCopy = selectedMessages
            .mapNotNull { message -> message.body.takeIf { it.isNotEmpty() } }
            .joinToString("\n\n")
        
        if (textToCopy.isNotEmpty()) {
            activity.copyToClipboard(textToCopy)
        }
    }

    private fun getSelectedAttachments(): List<Attachment> {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        return selectedMessages.flatMap { it.attachment?.attachments.orEmpty() }
    }

    private fun saveAs() {
        val attachments = getSelectedAttachments()
        if (attachments.isNotEmpty()) {
            (activity as ThreadActivity).saveMMS(attachments)
        }
    }

    private fun shareText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        activity.shareTextIntent(firstItem.body)
    }

    private fun selectText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        if (firstItem.body.trim().isNotEmpty()) {
            SelectTextDialog(activity, firstItem.body)
        }
    }

    private fun showMessageDetails() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        MessageDetailsDialog(activity, message)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = if (activity.config.useRecycleBin && !isRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        DeleteConfirmationDialog(activity, question, activity.config.useRecycleBin && !isRecycleBin) { skipRecycleBin ->
            ensureBackgroundThread {
                val messagesToRemove = getSelectedItems()
                if (messagesToRemove.isNotEmpty()) {
                    val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin && !isRecycleBin
                    deleteMessages(messagesToRemove.filterIsInstance<Message>(), toRecycleBin, false)
                }
            }
        }
    }

    private fun askConfirmRestore() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = R.string.restore_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                val messagesToRestore = getSelectedItems()
                if (messagesToRestore.isNotEmpty()) {
                    deleteMessages(messagesToRestore.filterIsInstance<Message>(), false, true)
                }
            }
        }
    }

    private fun forwardMessage() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        val attachment = message.attachment?.attachments?.firstOrNull()
        Intent(activity, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message.body)

            if (attachment != null) {
                putExtra(Intent.EXTRA_STREAM, attachment.getUri())
            }

            activity.startActivity(this)
        }
    }

    private fun getSelectedItems(): ArrayList<ThreadItem> {
        return currentList.filter {
            selectedKeys.contains((it as? Message)?.getSelectionKey() ?: 0)
        } as ArrayList<ThreadItem>
    }

    private fun isThreadDateTime(position: Int) = currentList.getOrNull(position) is ThreadDateTime

    fun updateMessages(
        newMessages: ArrayList<ThreadItem>,
        scrollPosition: Int = -1,
        smoothScroll: Boolean = false
    ) {
        recyclerView.recycledViewPool.clear()
        val latestMessages = newMessages.toMutableList()
        submitList(latestMessages) {
            if (scrollPosition != -1) {
                if (smoothScroll) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                } else {
                    recyclerView.scrollToPosition(scrollPosition)
                }
            }
        }
    }

    private fun setupView(holder: ViewHolder, view: View, message: Message) {
        ItemMessageBinding.bind(view).apply {
            threadMessageHolder.isSelected = selectedKeys.contains(message.getSelectionKey())
            threadMessageBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)

            threadMessageWrapper.setOnLongClickListener {
                (activity as? ThreadActivity)?.dismissReactionPicker()
                holder.viewLongClicked()
                true
            }

            threadMessageWrapper.setOnClickListener {
                if (selectedKeys.isEmpty() && message.isReceivedMessage()) {
                    (activity as? ThreadActivity)?.showReactionPicker(message, threadMessageBody)
                } else {
                    (activity as? ThreadActivity)?.dismissReactionPicker()
                }
                holder.viewClicked(message)
            }

            if (message.isReceivedMessage()) {
                setupReceivedMessageView(messageBinding = this, message = message)
            } else {
                setupSentMessageView(messageBinding = this, message = message)
            }

            if (message.attachment?.attachments?.isNotEmpty() == true) {
                threadMessageAttachmentsHolder.beVisible()
                threadMessageAttachmentsHolder.removeAllViews()
                for (attachment in message.attachment.attachments) {
                    val mimetype = attachment.mimetype
                    when {
                        mimetype.isImageMimeType() || mimetype.isVideoMimeType() -> setupImageView(holder, binding = this, message, attachment)
                        mimetype.isVCardMimeType() -> setupVCardView(holder, threadMessageAttachmentsHolder, message, attachment)
                        else -> setupFileView(holder, threadMessageAttachmentsHolder, message, attachment)
                    }

                    threadMessagePlayOutline.beVisibleIf(mimetype.startsWith("video/"))
                }
            } else {
                threadMessageAttachmentsHolder.beGone()
                threadMessagePlayOutline.beGone()
            }

            val payRequest = WalletTokenParser.parseFedimintPaymentRequest(message.body)
            val payResponse = WalletTokenParser.parseFedimintPaymentInvoiceResponse(message.body)
            val payDenied = WalletTokenParser.parseFedimintPaymentDeniedResponse(message.body)
            val ecashToken = WalletTokenParser.parseFedimintEcashToken(message.body)
            val walletAction = WalletTokenParser.findActionToken(message.body)
            val isWalletMessage = payRequest != null || payResponse != null || payDenied != null || ecashToken != null || walletAction != null

            if (isWalletMessage && selectedKeys.isEmpty() && message.isReceivedMessage()) {
                maybeAutoHandleWalletProtocolMessage(message)
            }

            val showRawWalletText = isWalletMessage && revealedWalletMessages.contains(message.id)
            threadMessageBody.apply {
                text = message.body
                beVisibleIf(message.body.isNotEmpty() && (!isWalletMessage || showRawWalletText))
            }

            val reactionAnchor = if (threadMessageBody.visibility == View.VISIBLE) {
                threadMessageBody.id
            } else {
                threadMessageAttachmentsHolder.id
            }
            threadMessageReaction.updateLayoutParams<RelativeLayout.LayoutParams> {
                removeRule(RelativeLayout.ALIGN_BOTTOM)
                removeRule(RelativeLayout.BELOW)
                addRule(RelativeLayout.BELOW, reactionAnchor)
                topMargin = resources.getDimensionPixelSize(R.dimen.tiny_margin)
            }

            val reactionText = reactionsByMessageId[message.id].orEmpty()
            threadMessageReaction.text = reactionText
            threadMessageReaction.beVisibleIf(reactionText.isNotBlank())

            threadMessageRequestCard.beVisibleIf(isWalletMessage)
            threadMessageWalletActions.beVisibleIf(isWalletMessage)

            if (isWalletMessage) {
                val primary = activity.getProperPrimaryColor()
                val contrast = primary.getContrastColor()
                threadMessageRequestCard.setCardBackgroundColor(primary.adjustAlpha(0.08f))
                threadMessageRequestCard.strokeColor = primary.adjustAlpha(0.25f)
                threadMessageRequestCard.strokeWidth = 1
                threadMessageRequestAmount.setTextColor(textColor)
                threadMessageRequestFederation.setTextColor(textColor.adjustAlpha(0.88f))
                threadMessageRequestId.setTextColor(textColor.adjustAlpha(0.72f))

                var titleText = ""
                var detailsText = ""
                var trailingText = ""

                when {
                    payRequest != null -> {
                        val sats = NumberFormat.getIntegerInstance().format(payRequest.amountSats)
                        titleText = if (message.isReceivedMessage()) {
                            activity.getString(R.string.wallet_card_request_title)
                        } else {
                            activity.getString(R.string.wallet_card_request_sent)
                        }
                        detailsText = activity.getString(R.string.wallet_request_amount, sats)
                        trailingText = activity.getString(R.string.wallet_request_federation, payRequest.federationId)
                    }

                    payResponse != null -> {
                        val sats = NumberFormat.getIntegerInstance().format(payResponse.amountSats)
                        titleText = activity.getString(R.string.wallet_card_request_approved, sats)
                        detailsText = activity.getString(R.string.wallet_request_federation, payResponse.federationId)
                        trailingText = activity.getString(R.string.wallet_request_id, payResponse.requestId)
                    }

                    payDenied != null -> {
                        titleText = activity.getString(R.string.wallet_card_request_denied)
                        detailsText = activity.getString(R.string.wallet_request_federation, payDenied.federationId)
                        trailingText = activity.getString(R.string.wallet_request_id, payDenied.requestId)
                    }

                    ecashToken != null -> {
                        val sats = NumberFormat.getIntegerInstance().format(ecashToken.amountSats)
                        titleText = activity.getString(R.string.wallet_card_token_received, sats)
                        detailsText = activity.getString(R.string.wallet_request_federation, ecashToken.federationId)
                        val expText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(ecashToken.expiresAtEpochSec * 1000L))
                        trailingText = activity.getString(R.string.wallet_card_token_expires, expText)
                    }

                    walletAction?.action == WalletTokenParser.WalletAction.PAY -> {
                        val token = walletAction.token.trim()
                        titleText = when {
                            token.startsWith("ln", ignoreCase = true) -> activity.getString(R.string.wallet_card_lightning_invoice)
                            token.startsWith("bc1", ignoreCase = true) ||
                                token.startsWith("tb1", ignoreCase = true) ||
                                token.startsWith("bcrt1", ignoreCase = true) -> activity.getString(R.string.wallet_card_onchain_address)
                            else -> activity.getString(R.string.wallet_card_payment_payload)
                        }
                        detailsText = walletAction.federationIdHint
                            ?.takeIf { it.isNotBlank() }
                            ?.let { activity.getString(R.string.wallet_request_federation, it) }
                            .orEmpty()
                        trailingText = activity.getString(R.string.wallet_card_destination, compactWalletValue(token))
                    }

                    else -> {
                        titleText = activity.getString(R.string.wallet_card_payment_payload)
                    }
                }

                threadMessageRequestAmount.text = titleText
                threadMessageRequestFederation.text = detailsText
                threadMessageRequestFederation.beVisibleIf(detailsText.isNotBlank())
                threadMessageRequestId.text = trailingText
                threadMessageRequestId.beVisibleIf(trailingText.isNotBlank())

                val requestForActions = payRequest?.takeIf {
                    message.isReceivedMessage() && selectedKeys.isEmpty()
                }
                threadMessageRequestActionsRow.beVisibleIf(requestForActions != null)
                if (requestForActions != null) {
                    val req = requestForActions
                    threadMessageRequestApprove.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                    threadMessageRequestApprove.setTextColor(contrast)
                    threadMessageRequestApprove.rippleColor = android.content.res.ColorStateList.valueOf(contrast.adjustAlpha(0.2f))
                    threadMessageRequestDeny.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                    threadMessageRequestDeny.strokeColor = android.content.res.ColorStateList.valueOf(primary.adjustAlpha(0.55f))
                    threadMessageRequestDeny.setTextColor(primary)
                    threadMessageRequestDeny.rippleColor = android.content.res.ColorStateList.valueOf(primary.adjustAlpha(0.2f))

                    threadMessageRequestApprove.setOnClickListener {
                        (activity as? ThreadActivity)?.launchWalletRespondToPaymentRequest(req.raw, WALLET_REQUEST_ACTION_APPROVE)
                    }
                    threadMessageRequestDeny.setOnClickListener {
                        (activity as? ThreadActivity)?.launchWalletRespondToPaymentRequest(req.raw, WALLET_REQUEST_ACTION_DENY)
                    }
                } else {
                    threadMessageRequestApprove.setOnClickListener(null)
                    threadMessageRequestDeny.setOnClickListener(null)
                }

                val actionForButton = walletAction?.takeIf {
                    message.isReceivedMessage() && selectedKeys.isEmpty() && payRequest == null
                }
                threadMessagePay.beVisibleIf(actionForButton != null)
                if (actionForButton != null) {
                    threadMessagePay.text = when (actionForButton.action) {
                        WalletTokenParser.WalletAction.REDEEM -> activity.getString(R.string.wallet_redeem)
                        WalletTokenParser.WalletAction.PAY -> activity.getString(R.string.wallet_pay)
                        WalletTokenParser.WalletAction.PAY_REQUEST -> activity.getString(R.string.wallet_review_request)
                    }
                    threadMessagePay.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                    threadMessagePay.setTextColor(contrast)
                    threadMessagePay.rippleColor = android.content.res.ColorStateList.valueOf(contrast.adjustAlpha(0.2f))
                    threadMessagePay.setOnClickListener {
                        val secure = LxmfAddress.isMeshThreadId(message.threadId) ||
                            (E2eManager.hasSharedSecret(activity, message.threadId) &&
                                E2eManager.isThreadEncrypted(activity, message.threadId))
                        val act = actionForButton
                        val intent = when (act.action) {
                            WalletTokenParser.WalletAction.REDEEM -> Intent().apply {
                                setClassName(activity, "org.fossify.phone.activities.WalletRedeemTokenActivity")
                                putExtra(EXTRA_WALLET_TOKEN_TEXT, act.token)
                            }

                            WalletTokenParser.WalletAction.PAY_REQUEST -> {
                                (activity as? ThreadActivity)?.launchWalletRespondToPaymentRequest(act.token)
                                null
                            }

                            WalletTokenParser.WalletAction.PAY -> Intent().apply {
                                setClassName(activity, "org.fossify.phone.activities.WalletPayActivity")
                                putExtra(EXTRA_WALLET_DESTINATION, act.token)
                                putExtra(EXTRA_WALLET_FEDERATION_ID_HINT, act.federationIdHint)
                                putExtra(EXTRA_WALLET_SECURE_CHANNEL, secure)
                                act.requestId?.takeIf { it.isNotBlank() }?.let {
                                    putExtra(EXTRA_WALLET_REQUEST_ID, it)
                                    putExtra(EXTRA_WALLET_REQUEST_THREAD_ID, message.threadId)
                                }
                                act.amountSats?.takeIf { it > 0L }?.let {
                                    putExtra(EXTRA_WALLET_REQUEST_AMOUNT_SATS, it)
                                }
                            }
                        }
                        if (intent != null) {
                            runCatching {
                                activity.startActivity(intent)
                            }.onFailure { t ->
                                activity.showErrorToast(t.message ?: t.toString())
                            }
                        }
                    }
                } else {
                    threadMessagePay.setOnClickListener(null)
                }

                threadMessageWalletToggle.beVisible()
                threadMessageWalletToggle.text = activity.getString(
                    if (showRawWalletText) R.string.wallet_hide_full_message else R.string.wallet_show_full_message
                )
                threadMessageWalletToggle.strokeColor = android.content.res.ColorStateList.valueOf(primary.adjustAlpha(0.55f))
                threadMessageWalletToggle.setTextColor(primary)
                threadMessageWalletToggle.rippleColor = android.content.res.ColorStateList.valueOf(primary.adjustAlpha(0.2f))
                threadMessageWalletToggle.setOnClickListener {
                    if (revealedWalletMessages.contains(message.id)) {
                        revealedWalletMessages.remove(message.id)
                    } else {
                        revealedWalletMessages.add(message.id)
                    }
                    val bindingPos = holder.bindingAdapterPosition
                    if (bindingPos >= 0) {
                        notifyItemChanged(bindingPos)
                    } else {
                        notifyDataSetChanged()
                    }
                }
                threadMessageWalletActions.updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.BELOW)
                    addRule(RelativeLayout.BELOW, threadMessageRequestCard.id)
                    topMargin = resources.getDimensionPixelSize(R.dimen.tiny_margin)
                }
            } else {
                threadMessageRequestApprove.setOnClickListener(null)
                threadMessageRequestDeny.setOnClickListener(null)
                threadMessagePay.setOnClickListener(null)
                threadMessageWalletToggle.setOnClickListener(null)
            }
        }
    }

    private fun maybeAutoHandleWalletProtocolMessage(message: Message) {
        val denied = WalletTokenParser.parseFedimintPaymentDeniedResponse(message.body)
        if (denied != null) {
            if (WalletPaymentRequestStateManager.hasHandledResponseMessage(activity, message.id)) {
                return
            }
            WalletPaymentRequestStateManager.markResponseMessageHandled(activity, message.id)
            val pending = WalletPaymentRequestStateManager.getPendingRequest(activity, denied.requestId)
            if (pending != null && pending.threadId == message.threadId) {
                WalletPaymentRequestStateManager.markRequestDenied(activity, denied.requestId)
                activity.toast(R.string.wallet_payment_request_denied)
            }
            return
        }

        val response = WalletTokenParser.parseFedimintPaymentInvoiceResponse(message.body) ?: return
        if (WalletPaymentRequestStateManager.hasHandledResponseMessage(activity, message.id)) {
            return
        }

        val validation = WalletPaymentRequestStateManager.validateAndReserveInvoiceResponse(
            context = activity,
            requestId = response.requestId,
            threadId = message.threadId,
            federationId = response.federationId,
            amountSats = response.amountSats,
            invoice = response.invoice,
        )
        WalletPaymentRequestStateManager.markResponseMessageHandled(activity, message.id)
        if (!validation.isValid) {
            // Avoid noisy toasts for old/history messages that do not belong to active pending requests.
            if (!validation.error.isNullOrBlank() &&
                !validation.error.contains("unknown or expired", ignoreCase = true)
            ) {
                activity.toast(validation.error)
            }
            return
        }

        val secure = LxmfAddress.isMeshThreadId(message.threadId) ||
            (E2eManager.hasSharedSecret(activity, message.threadId) &&
                E2eManager.isThreadEncrypted(activity, message.threadId))
        val intent = Intent().apply {
            setClassName(activity, "org.fossify.phone.activities.WalletPayActivity")
            putExtra(EXTRA_WALLET_DESTINATION, response.invoice)
            putExtra(EXTRA_WALLET_FEDERATION_ID_HINT, response.federationId)
            putExtra(EXTRA_WALLET_SECURE_CHANNEL, secure)
            putExtra(EXTRA_WALLET_AUTO_PAY, true)
            putExtra(EXTRA_WALLET_REQUEST_ID, response.requestId)
            putExtra(EXTRA_WALLET_REQUEST_AMOUNT_SATS, response.amountSats)
            putExtra(EXTRA_WALLET_REQUEST_THREAD_ID, message.threadId)
        }
        runCatching {
            activity.startActivity(intent)
        }.onFailure { t ->
            activity.showErrorToast(t.message ?: t.toString())
        }
    }

    private fun compactWalletValue(value: String, prefix: Int = 16, suffix: Int = 10): String {
        val clean = value.trim()
        if (clean.length <= prefix + suffix + 3) return clean
        return "${clean.take(prefix)}...${clean.takeLast(suffix)}"
    }

    private fun setupReceivedMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.END)
                connect(threadMessageWrapper.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                applyTo(threadMessageHolder)
            }

            threadMessageSenderPhoto.beVisible()
            threadMessageSenderPhoto.setOnClickListener {
                val contact = message.getSender()!!
                activity.getContactFromAddress(contact.phoneNumbers.first().normalizedNumber) {
                    if (it != null) {
                        activity.startContactDetailsIntent(it)
                    }
                }
            }

            threadMessageBody.apply {
                background = AppCompatResources.getDrawable(activity, R.drawable.item_received_background)
                setTextColor(textColor)
                setLinkTextColor(activity.getProperPrimaryColor())
            }

            threadMessageReaction.updateLayoutParams<RelativeLayout.LayoutParams> {
                removeRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.ALIGN_END, threadMessageBody.id)
            }
            threadMessageReaction.background =
                AppCompatResources.getDrawable(activity, R.drawable.item_reaction_background)

            if (!activity.isFinishing && !activity.isDestroyed) {
                val contactLetterIcon = SimpleContactsHelper(activity).getContactLetterIcon(message.senderName)
                val placeholder = contactLetterIcon.toDrawable(activity.resources)

                val options = RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .error(placeholder)
                    .centerCrop()

                Glide.with(activity)
                    .load(message.senderPhotoUri)
                    .placeholder(placeholder)
                    .apply(options)
                    .apply(RequestOptions.circleCropTransform())
                    .into(threadMessageSenderPhoto)
            }
        }
    }

    private fun setupSentMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.START)
                connect(threadMessageWrapper.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                applyTo(threadMessageHolder)
            }

            val primaryColor = activity.getProperPrimaryColor()
            val contrastColor = primaryColor.getContrastColor()

            threadMessageBody.apply {
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }

                background = AppCompatResources.getDrawable(activity, R.drawable.item_sent_background)
                background.applyColorFilter(primaryColor)
                setTextColor(contrastColor)
                setLinkTextColor(contrastColor)

                if (message.isScheduled) {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    val scheduledDrawable = AppCompatResources.getDrawable(activity, org.fossify.commons.R.drawable.ic_clock_vector)?.apply {
                        applyColorFilter(contrastColor)
                        val size = lineHeight
                        setBounds(0, 0, size, size)
                    }

                    setCompoundDrawables(null, null, scheduledDrawable, null)
                } else {
                    typeface = Typeface.DEFAULT
                    setCompoundDrawables(null, null, null, null)
                }
            }

            threadMessageReaction.updateLayoutParams<RelativeLayout.LayoutParams> {
                removeRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.ALIGN_START, threadMessageBody.id)
            }
            threadMessageReaction.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun setupImageView(holder: ViewHolder, binding: ItemMessageBinding, message: Message, attachment: Attachment) = binding.apply {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()

        val imageView = ItemAttachmentImageBinding.inflate(layoutInflater)
        threadMessageAttachmentsHolder.addView(imageView.root)
        val glide = Glide.with(imageView.attachmentImage)

        val placeholderDrawable = Color.TRANSPARENT.toDrawable()
        val baseOptions = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(placeholderDrawable)
            .error(R.drawable.ic_image_vector)

        // Important: do not apply Bitmap transformations for image attachments here.
        // Bitmap-only transforms (FitCenter/CenterCrop/downsample) will force animated media (GIF/animated WebP)
        // into a static Bitmap on some devices/attachments. Rely on ImageView scaleType instead.
        imageView.attachmentImage.scaleType = ImageView.ScaleType.FIT_CENTER
        glide.load(uri)
            .apply(baseOptions)
            .override(maxChatBubbleWidth, maxChatBubbleWidth * MAX_MEDIA_HEIGHT_RATIO)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    // Avoid mutating RecyclerView/Glide-bound UI directly inside Glide callbacks.
                    // Glide forbids starting/clearing loads from these callbacks and may throw.
                    imageView.attachmentImage.post {
                        threadMessagePlayOutline.beGone()
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // Ensure animated drawables (GIF, animated WebP) start playing inline.
                    imageView.attachmentImage.post {
                        try {
                            (resource as? GifDrawable)?.setLoopCount(GifDrawable.LOOP_FOREVER)
                            (resource as? Animatable)?.start()
                        } catch (_: Exception) {
                        }
                    }
                    return false
                }
            })
            .into(imageView.attachmentImage)

        imageView.attachmentImage.updateLayoutParams<ViewGroup.LayoutParams> {
            width = maxChatBubbleWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        imageView.attachmentImage.setOnClickListener {
            if (actModeCallback.isSelectable) {
                holder.viewClicked(message)
            } else {
                activity.launchViewIntent(uri, mimetype, attachment.filename)
            }
        }
        imageView.root.setOnLongClickListener {
            holder.viewLongClicked()
            true
        }
    }

    private fun setupVCardView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val uri = attachment.getUri()
        val vCardView = ItemAttachmentVcardBinding.inflate(layoutInflater).apply {
            setupVCardPreview(
                activity = activity,
                uri = uri,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        val intent = Intent(activity, VCardViewerActivity::class.java).also {
                            it.putExtra(EXTRA_VCARD_URI, uri)
                        }
                        activity.startActivity(intent)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(vCardView)
    }

    private fun setupFileView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()
        val attachmentView = ItemAttachmentDocumentBinding.inflate(layoutInflater).apply {
            setupDocumentPreview(
                uri = uri,
                title = attachment.filename,
                mimeType = attachment.mimetype,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        activity.launchViewIntent(uri, mimetype, attachment.filename)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(attachmentView)
    }

    private fun setupDateTime(view: View, dateTime: ThreadDateTime) {
        ItemThreadDateTimeBinding.bind(view).apply {
            threadDateTime.apply {
                text = (dateTime.date * 1000L).formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = false,
                    showCurrentYear = false
                )
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            }
            threadDateTime.setTextColor(textColor)

            threadSimIcon.beVisibleIf(hasMultipleSIMCards)
            threadSimNumber.beVisibleIf(hasMultipleSIMCards)
            if (hasMultipleSIMCards) {
                threadSimNumber.text = dateTime.simID
                threadSimNumber.setTextColor(textColor.getContrastColor())
                threadSimIcon.applyColorFilter(textColor)
            }
        }
    }

    private fun setupThreadSuccess(view: View, isDelivered: Boolean) {
        ItemThreadSuccessBinding.bind(view).apply {
            threadSuccess.setImageResource(if (isDelivered) R.drawable.ic_check_double_vector else org.fossify.commons.R.drawable.ic_check_vector)
            threadSuccess.applyColorFilter(textColor)
        }
    }

    private fun setupThreadError(view: View) {
        val binding = ItemThreadErrorBinding.bind(view)
        binding.threadError.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize - 4)
    }

    private fun setupThreadSending(view: View) {
        ItemThreadSendingBinding.bind(view).threadSending.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            setTextColor(textColor)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = (holder as ThreadViewHolder).binding
            if (binding is ItemMessageBinding) {
                Glide.with(activity).clear(binding.threadMessageSenderPhoto)
            }
        }
    }

    inner class ThreadViewHolder(val binding: ViewBinding) : ViewHolder(binding.root)
}

private class ThreadItemDiffCallback : DiffUtil.ItemCallback<ThreadItem>() {

    override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadError -> oldItem.messageId == (newItem as ThreadError).messageId
            is ThreadSent -> oldItem.messageId == (newItem as ThreadSent).messageId
            is ThreadSending -> oldItem.messageId == (newItem as ThreadSending).messageId
            is Message -> Message.areItemsTheSame(oldItem, newItem as Message)
            is ThreadDateTime -> {
                val new = newItem as ThreadDateTime
                oldItem.date == new.date && oldItem.simID == new.simID
            }
        }
    }

    override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadSending -> true
            is ThreadDateTime -> oldItem.simID == (newItem as ThreadDateTime).simID
            is ThreadError -> oldItem.messageText == (newItem as ThreadError).messageText
            is ThreadSent -> oldItem.delivered == (newItem as ThreadSent).delivered
            is Message -> Message.areContentsTheSame(oldItem, newItem as Message)
        }
    }
}
