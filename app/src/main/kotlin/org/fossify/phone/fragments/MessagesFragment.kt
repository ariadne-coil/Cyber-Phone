package org.fossify.phone.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.provider.Telephony
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.fadeIn
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.PERMISSION_SEND_SMS
import org.fossify.commons.helpers.SHORT_ANIMATION_DURATION
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.Release
import org.fossify.commons.views.MyRecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.phone.databinding.FragmentMessagesBinding
import org.fossify.phone.databinding.FragmentMessagesContentBinding
import org.fossify.messages.R
import org.fossify.messages.adapters.ConversationsAdapter
import org.fossify.messages.adapters.SearchResultsAdapter
import org.fossify.messages.extensions.checkAndDeleteOldRecycleBinMessages
import org.fossify.messages.extensions.clearAllMessagesIfNeeded
import org.fossify.messages.extensions.clearExpiredScheduledMessages
import org.fossify.messages.extensions.config as messagesConfig
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.SEARCHED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.models.Message
import org.fossify.messages.models.SearchResult
import org.fossify.mesh.lxmf.LxmfAddress
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MessagesFragment(
    context: Context,
    attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.InnerBinding>(context, attributeSet) {
    companion object {
        const val REQUEST_CODE_SET_DEFAULT_SMS = 1002
    }

    private lateinit var binding: FragmentMessagesContentBinding

    private var storedTextColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null
    private var hasInitializedLoad = false
    private var hasMenuBasePadding = false
    private var menuBasePaddingLeft = 0
    private var menuBasePaddingTop = 0
    private var menuBasePaddingRight = 0
    private var menuBasePaddingBottom = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        val fragmentBinding = FragmentMessagesBinding.bind(this)
        binding = fragmentBinding.messagesFragmentContent
        innerBinding = object : InnerBinding {
            override val fragmentList: MyRecyclerView = binding.conversationsList
            override val recentsList: MyRecyclerView? = null
        }
    }

    override fun setupFragment() {
        setupOptionsMenu()
        refreshMenuItems()
        activity?.setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.conversationsList))
        applyMenuInsets()
        activity?.let { E2eManager.ensureKeyPair(it) }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        refreshItems()
    }

    override fun onSearchClosed() {
        binding.mainMenu.closeSearch()
    }

    override fun onSearchQueryChanged(text: String) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            bus?.unregister(this)
        } catch (_: Exception) {
        }
        bus = null
    }

    fun onTabSelected() {
        val host = activity ?: return
        if (host.checkAppSideloading()) {
            return
        }

        applyMenuInsets()
        if (!hasInitializedLoad) {
            hasInitializedLoad = true
            host.appLaunched(host.packageName)
            host.checkAndDeleteOldRecycleBinMessages()
            host.clearAllMessagesIfNeeded { loadMessages() }
        } else {
            if (bus == null) {
                loadMessages()
            } else {
                refreshItems()
            }
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_CODE_SET_DEFAULT_SMS) {
            return
        }

        if (resultCode == Activity.RESULT_OK) {
            askPermissions()
        }
    }

    fun refreshItems() {
        val host = activity ?: return
        updateMenuColors()
        refreshMenuItems()

        val currentTextColor = host.getProperTextColor()
        val currentFontSize = host.messagesConfig.fontSize
        getOrCreateConversationsAdapter()?.apply {
            if (storedTextColor != currentTextColor) {
                updateTextColor(currentTextColor)
                storedTextColor = currentTextColor
            }

            if (storedFontSize != currentFontSize) {
                updateFontSize()
                storedFontSize = currentFontSize
            }

            updateDrafts()
        }

        host.updateTextColors(binding.mainCoordinator)
        binding.searchHolder.setBackgroundColor(host.getProperBackgroundColor())

        val properPrimaryColor = host.getProperPrimaryColor()
        binding.noConversationsPlaceholder2.setTextColor(properPrimaryColor)
        binding.noConversationsPlaceholder2.underlineText()
        binding.conversationsFastscroller.updateColors(properPrimaryColor)
        binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu_main)
        binding.mainMenu.toggleHideOnScroll(true)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchClosedListener = {
            fadeOutSearch()
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            if (text.isNotEmpty()) {
                if (binding.searchHolder.alpha < 1f) {
                    binding.searchHolder.fadeIn()
                }
            } else {
                fadeOutSearch()
            }
            searchTextChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.show_recycle_bin -> launchRecycleBin()
                R.id.show_archived -> launchArchivedConversations()
                R.id.manage_e2e_keys -> launchManageE2eKeys()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun launchManageE2eKeys() {
        val host = activity ?: return
        host.startActivity(Intent(host, org.fossify.messages.activities.ManageE2eKeysActivity::class.java))
    }

    private fun applyMenuInsets() {
        if (!hasMenuBasePadding) {
            menuBasePaddingLeft = binding.mainMenu.paddingLeft
            menuBasePaddingTop = binding.mainMenu.paddingTop
            menuBasePaddingRight = binding.mainMenu.paddingRight
            menuBasePaddingBottom = binding.mainMenu.paddingBottom
            hasMenuBasePadding = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainMenu) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                menuBasePaddingLeft + systemBars.left,
                menuBasePaddingTop + systemBars.top,
                menuBasePaddingRight + systemBars.right,
                menuBasePaddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.mainMenu)
    }

    private fun refreshMenuItems() {
        val config = activity?.messagesConfig ?: return
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.show_recycle_bin).isVisible = config.useRecycleBin
            findItem(R.id.show_archived).isVisible = config.isArchiveAvailable
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun loadMessages() {
        val host = activity ?: return
        if (isQPlus()) {
            val roleManager = host.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    host.startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_SMS)
                }
            } else {
                host.toast(org.fossify.commons.R.string.unknown_error_occurred)
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(host) == host.packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, host.packageName)
                host.startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_SMS)
            }
        }
    }

    private fun askPermissions() {
        val host = activity ?: return
        host.handlePermission(PERMISSION_READ_SMS) { readSmsGranted ->
            if (!readSmsGranted) {
                return@handlePermission
            }

            host.handlePermission(PERMISSION_SEND_SMS) { sendSmsGranted ->
                if (!sendSmsGranted) {
                    return@handlePermission
                }

                host.handlePermission(PERMISSION_READ_CONTACTS) {
                    host.handleNotificationPermission { granted ->
                        if (!granted) {
                            PermissionRequiredDialog(
                                activity = host,
                                textId = org.fossify.commons.R.string.allow_notifications_incoming_messages,
                                positiveActionCallback = { host.openNotificationSettings() }
                            )
                        }
                    }

                    initMessenger()
                    bus = EventBus.getDefault()
                    try {
                        bus?.register(this)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun initMessenger() {
        checkWhatsNewDialog()
        storeStateVariables()
        getCachedConversations()
        binding.noConversationsPlaceholder2.setOnClickListener {
            launchNewConversation()
        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
    }

    private fun storeStateVariables() {
        val host = activity ?: return
        storedTextColor = host.getProperTextColor()
        storedFontSize = host.messagesConfig.fontSize
    }

    private fun getCachedConversations() {
        val host = activity ?: return
        ensureBackgroundThread {
            val conversations = try {
                host.conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }

            val archived = try {
                host.conversationsDB.getAllArchived()
            } catch (_: Exception) {
                listOf()
            }

            host.runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations((conversations + archived).toMutableList() as ArrayList<Conversation>)
            }
            conversations.forEach {
                host.clearExpiredScheduledMessages(it.threadId)
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        val host = activity ?: return
        val privateCursor = host.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(host, privateCursor)
            val conversations = host.getConversations(privateContacts = privateContacts)

            conversations.forEach { clonedConversation ->
                val threadIds = cachedConversations.map { it.threadId }
                if (!threadIds.contains(clonedConversation.threadId)) {
                    host.conversationsDB.insertOrUpdate(clonedConversation)
                    cachedConversations.add(clonedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId
                if (LxmfAddress.isMeshThreadId(threadId)) {
                    return@forEach
                }

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                if (isConversationDeleted && !isTemporaryThread) {
                    host.conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    host.conversationsDB.deleteThreadId(threadId)
                    host.messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            host.messagesDB.insertOrUpdate(message.copy(threadId = newConversation.threadId))
                        }
                    host.insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                if (LxmfAddress.isMeshThreadId(cachedConv.threadId)) {
                    return@forEach
                }
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv,
                        new = it
                    )
                }
                if (conv != null) {
                    host.insertOrUpdateConversation(conv)
                }
            }

            val allConversations = host.conversationsDB.getNonArchived() as ArrayList<Conversation>
            host.runOnUiThread {
                setupConversations(allConversations)
            }

            if (host.messagesConfig.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = host.getMessages(threadId, includeScheduledMessages = false)
                    messages.chunked(30).forEach { currentMessages ->
                        host.messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter? {
        val host = activity ?: return null
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            host.hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = host,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (host.areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun setupConversations(conversations: ArrayList<Conversation>, cached: Boolean = false) {
        val host = activity ?: return
        val sortedConversations = conversations
            .sortedWith(
                compareByDescending<Conversation> {
                    host.messagesConfig.pinnedConversations.contains(it.threadId.toString())
                }.thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>

        if (cached && host.messagesConfig.appRunCount == 1) {
            showOrHideProgress(conversations.isEmpty())
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter()?.apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        val host = activity ?: return
        if (show) {
            binding.conversationsProgressBar.show()
            binding.noConversationsPlaceholder.beVisible()
            binding.noConversationsPlaceholder.text = host.getString(R.string.loading_messages)
        } else {
            binding.conversationsProgressBar.hide()
            binding.noConversationsPlaceholder.beGone()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.text = context.getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter()?.notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        val host = activity ?: return
        Intent(host, org.fossify.messages.activities.ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            host.startActivity(this)
        }
    }

    private fun launchNewConversation() {
        val host = activity ?: return
        host.hideKeyboard()
        Intent(host, org.fossify.messages.activities.NewConversationActivity::class.java).apply {
            host.startActivity(this)
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcut() {
        val host = activity ?: return
        val appIconColor = host.messagesConfig.appIconColor
        if (host.messagesConfig.lastHandledShortcutColor != appIconColor) {
            val newConversation = getCreateNewContactShortcut(appIconColor)

            val manager = host.getSystemService(ShortcutManager::class.java)
            try {
                manager?.dynamicShortcuts = listOf(newConversation)
                host.messagesConfig.lastHandledShortcutColor = appIconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val host = activity ?: return ShortcutInfo.Builder(context, "new_conversation")
            .setShortLabel("")
            .setIntent(Intent())
            .build()

        val newEvent = host.getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(host, org.fossify.commons.R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(
            org.fossify.commons.R.id.shortcut_plus_background
        ).applyColorFilter(appIconColor)

        val bmp = drawable.convertToBitmap()

        val intent = Intent(host, org.fossify.messages.activities.NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(host, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        if (!binding.mainMenu.isSearchOpen && !forceUpdate) {
            return
        }

        val host = activity ?: return
        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.length >= 2)
        if (text.length >= 2) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = host.messagesDB.getMessagesWithText(searchQuery)
                val conversations = host.conversationsDB.getConversationsWithText(searchQuery)
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val host = activity ?: return
        val searchResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val date = (conversation.date * 1000L).formatDateOrTime(
                context = host,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                snippet = conversation.phoneNumber,
                date = date,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri
            )
            searchResults.add(searchResult)
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val date = (message.date * 1000L).formatDateOrTime(
                context = host,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                snippet = message.body,
                date = date,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri
            )
            searchResults.add(searchResult)
        }

        host.runOnUiThread {
            binding.searchResultsList.beVisibleIf(searchResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(searchResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(host, searchResults, binding.searchResultsList, searchedText) {
                    host.hideKeyboard()
                    Intent(host, org.fossify.messages.activities.ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        host.startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchResults, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        val host = activity ?: return
        host.hideKeyboard()
        host.startActivity(
            Intent(host, org.fossify.messages.activities.RecycleBinConversationsActivity::class.java)
        )
    }

    private fun launchArchivedConversations() {
        val host = activity ?: return
        host.hideKeyboard()
        host.startActivity(
            Intent(host, org.fossify.messages.activities.ArchivedConversationsActivity::class.java)
        )
    }

    private fun launchSettings() {
        val host = activity ?: return
        host.hideKeyboard()
        host.startActivity(Intent(host, org.fossify.phone.activities.SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val host = activity ?: return
        host.startActivity(Intent(host, org.fossify.phone.activities.CyberAboutActivity::class.java))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        initMessenger()
    }

    private fun checkWhatsNewDialog() {
        val host = activity ?: return
        arrayListOf<Release>().apply {
            val packageInfo = host.packageManager.getPackageInfo(host.packageName, 0)
            host.checkWhatsNew(this, packageInfo.longVersionCode.toInt())
        }
    }
}
