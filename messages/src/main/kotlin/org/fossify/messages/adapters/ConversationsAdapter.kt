package org.fossify.messages.adapters

import android.content.Intent
import android.text.TextUtils
import android.view.Menu
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.mesh.MeshContactHelper
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.dialogs.RenameConversationDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.extensions.dialNumber
import org.fossify.messages.extensions.launchConversationDetails
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.extensions.markThreadMessagesUnread
import org.fossify.messages.extensions.renameConversation
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.extensions.messageCategoryCacheDB
import org.fossify.messages.extensions.removeBlockedNumberCompat
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.MessageCategorizer
import org.fossify.messages.messaging.isShortCodeWithLetters
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.MessageCategoryCache

class ConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {
    override fun getActionMenuId() = R.menu.cab_conversations

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        val isSingleSelection = isOneItemSelected()
        val selectedConversation = selectedItems.firstOrNull() ?: return
        val isGroupConversation = selectedConversation.isGroupConversation
        val archiveAvailable = activity.config.isArchiveAvailable

        menu.apply {
            findItem(R.id.cab_block_number).title =
                activity.getString(R.string.mark_as_spam_block)
            findItem(R.id.cab_block_number).isVisible = false
            findItem(R.id.cab_mark_as_not_spam).isVisible = false
            findItem(R.id.cab_add_number_to_contact).isVisible =
                isSingleSelection && !isGroupConversation
            findItem(R.id.cab_dial_number).isVisible =
                isSingleSelection && !isGroupConversation &&
                        !isShortCodeWithLetters(selectedConversation.phoneNumber)
            findItem(R.id.cab_copy_number).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_rename_conversation).isVisible =
                isSingleSelection && isGroupConversation
            findItem(R.id.cab_conversation_details).isVisible = isSingleSelection
            findItem(R.id.cab_mark_as_read).isVisible = selectedItems.any { !it.read }
            findItem(R.id.cab_mark_as_unread).isVisible = selectedItems.any { it.read }
            findItem(R.id.cab_archive).isVisible = archiveAvailable
            checkPinBtnVisibility(this)
        }
        updateSpamActionVisibility(menu)
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_add_number_to_contact -> addNumberToContact()
            R.id.cab_block_number -> tryBlocking()
            R.id.cab_mark_as_not_spam -> markAsNotSpam()
            R.id.cab_dial_number -> dialNumber()
            R.id.cab_copy_number -> copyNumberToClipboard()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_archive -> askConfirmArchive()
            R.id.cab_rename_conversation -> renameConversation(getSelectedItems().first())
            R.id.cab_conversation_details ->
                activity.launchConversationDetails(getSelectedItems().first().threadId)

            R.id.cab_mark_as_read -> markAsRead()
            R.id.cab_mark_as_unread -> markAsUnread()
            R.id.cab_pin_conversation -> pinConversation(true)
            R.id.cab_unpin_conversation -> pinConversation(false)
            R.id.cab_select_all -> selectAll()
        }
    }

    private fun tryBlocking() {
        askConfirmBlock()
    }

    private fun askConfirmBlock() {
        val numbers = getSelectedItems().distinctBy { it.phoneNumber }.map { it.phoneNumber }
        val numbersString = TextUtils.join(", ", numbers)
        val question = String.format(
            resources.getString(org.fossify.commons.R.string.block_confirmation),
            numbersString
        )

        ConfirmationDialog(activity, question) {
            blockNumbers()
        }
    }

    private fun blockNumbers() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val numbersToBlock = getSelectedItems()
        val newList = currentList.toMutableList().apply { removeAll(numbersToBlock) }

        ensureBackgroundThread {
            numbersToBlock.map { it.phoneNumber }.forEach { number ->
                val normalized = number.normalizePhoneNumber().trim()
                if (normalized.isNotEmpty()) {
                    activity.config.removeSafeNumber(normalized)
                    if (!activity.config.spamRatedNumbers.contains(normalized)) {
                        MessageCategorizer.submitCommunityRating(activity, normalized, positive = false)
                        activity.config.addSpamRatedNumber(normalized)
                    }
                }
                activity.addBlockedNumber(number)
            }
            numbersToBlock.forEach { conversation ->
                activity.messageCategoryCacheDB.insert(
                    MessageCategoryCache(
                        threadId = conversation.threadId,
                        category = 2,
                        isBlocked = 1,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            activity.runOnUiThread {
                submitList(newList)
                finishActMode()
            }
        }
    }

    private fun updateSpamActionVisibility(menu: Menu) {
        if (selectedKeys.isEmpty()) {
            return
        }
        val selected = getSelectedItems()
        val threadIds = selected.map { it.threadId }
        ensureBackgroundThread {
            val cached = try {
                activity.messageCategoryCacheDB.getByThreadIds(threadIds)
            } catch (_: Exception) {
                emptyList()
            }
            val spamThreadIds = cached.filter { it.category == 2 && it.isBlocked == 1 }.map { it.threadId }.toSet()
            val hasSpam = selected.any { spamThreadIds.contains(it.threadId) }
            val hasNonSpam = selected.any { !spamThreadIds.contains(it.threadId) }
            activity.runOnUiThread {
                menu.findItem(R.id.cab_block_number)?.isVisible = hasNonSpam
                menu.findItem(R.id.cab_mark_as_not_spam)?.isVisible = hasSpam
            }
        }
    }

    private fun markAsNotSpam() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val selected = getSelectedItems()
        val newList = currentList.toMutableList().apply { removeAll(selected) }
        ensureBackgroundThread {
            selected.map { it.phoneNumber }.forEach { number ->
                val normalized = number.normalizePhoneNumber().trim()
                if (normalized.isNotEmpty()) {
                    activity.config.addSafeNumber(normalized)
                    if (!activity.config.notSpamRatedNumbers.contains(normalized)) {
                        MessageCategorizer.submitCommunityRating(activity, normalized, positive = true)
                        activity.config.addNotSpamRatedNumber(normalized)
                    }
                }
                activity.removeBlockedNumberCompat(number)
            }
            selected.forEach { conversation ->
                activity.messageCategoryCacheDB.insert(
                    MessageCategoryCache(
                        threadId = conversation.threadId,
                        category = 0,
                        isBlocked = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            activity.runOnUiThread {
                submitList(newList)
                finishActMode()
                refreshConversations()
            }
        }
    }

    private fun dialNumber() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.dialNumber(conversation.phoneNumber) {
            finishActMode()
        }
    }

    private fun copyNumberToClipboard() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(conversation.phoneNumber)
        finishActMode()
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = org.fossify.commons.R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                deleteConversations()
            }
        }
    }

    private fun askConfirmArchive() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = R.string.archive_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                archiveConversations()
            }
        }
    }

    private fun archiveConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsToRemove =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        conversationsToRemove.forEach {
            activity.updateConversationArchivedStatus(it.threadId, true)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (ignored: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }

    private fun deleteConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsToRemove =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        conversationsToRemove.forEach {
            activity.deleteConversation(it.threadId)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (ignored: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }

    private fun renameConversation(conversation: Conversation) {
        RenameConversationDialog(activity, conversation) {
            ensureBackgroundThread {
                val updatedConv = activity.renameConversation(conversation, newTitle = it)
                activity.runOnUiThread {
                    finishActMode()
                    currentList.toMutableList().apply {
                        set(indexOf(conversation), updatedConv)
                        updateConversations(this as ArrayList<Conversation>)
                    }
                }
            }
        }
    }

    private fun markAsRead() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsRead =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsRead.filter { conversation -> !conversation.read }.forEach {
                activity.markThreadMessagesRead(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun markAsUnread() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsUnread =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsUnread.filter { conversation -> conversation.read }.forEach {
                activity.markThreadMessagesUnread(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun addNumberToContact() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, conversation.phoneNumber)
            MeshContactHelper.addMeshPhoneInsertExtras(this)
            activity.launchActivityIntent(this)
        }
    }

    private fun pinConversation(pin: Boolean) {
        val conversations = getSelectedItems()
        if (conversations.isEmpty()) {
            return
        }

        if (pin) {
            activity.config.addPinnedConversations(conversations)
        } else {
            activity.config.removePinnedConversations(conversations)
        }

        getSelectedItemPositions().forEach {
            notifyItemChanged(it)
        }
        refreshConversationsAndFinishActMode()
    }

    private fun checkPinBtnVisibility(menu: Menu) {
        val pinnedConversations = activity.config.pinnedConversations
        val selectedConversations = getSelectedItems()
        menu.findItem(R.id.cab_pin_conversation).isVisible =
            selectedConversations.any { !pinnedConversations.contains(it.threadId.toString()) }
        menu.findItem(R.id.cab_unpin_conversation).isVisible =
            selectedConversations.all { pinnedConversations.contains(it.threadId.toString()) }
    }

    private fun refreshConversationsAndFinishActMode() {
        activity.runOnUiThread {
            refreshConversations()
            finishActMode()
        }
    }
}
