package org.fossify.phone.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatWithDeprecatedBadge
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES
import org.fossify.commons.helpers.TAB_LAST_USED
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivitySettingsBinding
import org.fossify.phone.dialogs.ExportCallHistoryDialog
import org.fossify.phone.dialogs.ManageVisibleTabsDialog
import org.fossify.phone.extensions.canLaunchAccountsConfiguration
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.TAB_MESSAGES
import org.fossify.phone.extensions.launchAccountsConfiguration
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.models.RecentCall
import org.fossify.messages.activities.ManageBlockedKeywordsActivity
import org.fossify.messages.dialogs.ExportMessagesDialog
import org.fossify.messages.extensions.config as messagesConfig
import org.fossify.messages.extensions.emptyMessagesRecycleBin
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.FILE_SIZE_100_KB
import org.fossify.messages.helpers.FILE_SIZE_1_MB
import org.fossify.messages.helpers.FILE_SIZE_200_KB
import org.fossify.messages.helpers.FILE_SIZE_2_MB
import org.fossify.messages.helpers.FILE_SIZE_300_KB
import org.fossify.messages.helpers.FILE_SIZE_600_KB
import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.fossify.messages.helpers.LOCK_SCREEN_NOTHING
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER_MESSAGE
import org.fossify.messages.helpers.MessagesImporter
import org.fossify.messages.helpers.SPAM_REPUTATION_AGGRESSIVE
import org.fossify.messages.helpers.SPAM_REPUTATION_BALANCED
import org.fossify.messages.helpers.SPAM_REPUTATION_CONSERVATIVE
import org.fossify.messages.helpers.SPAM_REPUTATION_VERY_CONSERVATIVE
import org.fossify.messages.helpers.AiSpamModelManager
import org.fossify.messages.helpers.refreshConversations
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshMode
import org.fossify.mesh.rns.RnsNode
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val CALL_HISTORY_FILE_TYPE = "application/json"
        private val IMPORT_CALL_HISTORY_FILE_TYPES = buildList {
            add("application/json")
            if (!isQPlus()) {
                // Workaround for https://github.com/FossifyOrg/Messages/issues/88
                add("application/octet-stream")
            }
        }
    }

    private data class AiSpamModelOption(val id: Int, val nameRes: Int, val url: String)

    private val aiSpamModelOptions = listOf(
        AiSpamModelOption(
            id = 0,
            nameRes = R.string.ai_spam_model_mediapipe_average_word,
            url = "https://storage.googleapis.com/mediapipe-models/text_classifier/average_word_classifier/float32/latest/average_word_classifier.tflite"
        ),
        AiSpamModelOption(
            id = 1,
            nameRes = R.string.ai_spam_model_mediapipe_bert,
            url = "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_classifier/float32/latest/bert_classifier.tflite"
        )
    )

    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast(R.string.importing)
                importCallHistory(uri)
            }
        }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(CALL_HISTORY_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            RecentsHelper(this).getRecentCalls(queryLimit = Int.MAX_VALUE) { recents ->
                exportCallHistory(recents, uri)
            }
        }
    }

    private var exportMessagesDialog: ExportMessagesDialog? = null
    private val getMessagesContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private val saveMessagesDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(org.fossify.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))
            setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeNotifications()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageBlockedKeywords()
        setupSpamReputationThreshold()
        setupAiSpamSettings()
        setupManageSpeedDial()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupManageShownTabs()
        setupDefaultTab()
        setupDialPadOpen()
        setupGroupSubsequentCalls()
        setupBlockNegativeRatings()
        setupStartNameWithSurname()
        setupFormatPhoneNumbers()
        setupDialpadVibrations()
        setupDialpadNumbers()
        setupDialpadBeeps()
        setupShowCallConfirmation()
        setupDisableProximitySensor()
        setupDisableSwipeToAnswer()
        setupAlwaysShowFullscreen()
        setupMeshMode()
        setupMeshRouting()
        setupMeshStatus()
        setupShowCharacterCounter()
        setupUseSimpleCharacters()
        setupSendOnEnter()
        setupEnableDeliveryReports()
        setupSendLongMessageAsMMS()
        setupGroupMessageAsMMS()
        setupLockScreenVisibility()
        setupMMSFileSizeLimit()
        setupKeepConversationsArchived()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        setupAppPasswordProtection()
        setupShowBlockedCallNotifications()
        setupShowCallRatingNotifications()
        setupCallsExport()
        setupCallsImport()
        setupMessagesExport()
        setupMessagesImport()
        updateTextColors(binding.settingsHolder)

        if (blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()) {
            refreshConversations()
        }

        binding.apply {
            arrayOf(
                settingsColorCustomizationSectionLabel,
                settingsGeneralSettingsLabel,
                settingsStartupLabel,
                settingsCallsLabel,
                settingsMeshLabel,
                settingsDialpadSectionLabel,
                settingsNotificationsLabel,
                settingsOutgoingMessagesLabel,
                settingsArchivedMessagesLabel,
                settingsRecycleBinLabel,
                settingsSecurityLabel,
                settingsMessagesMigratingLabel,
                settingsMigrationSectionLabel
            ).forEach {
                it.setTextColor(getProperPrimaryColor())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.calling_accounts -> launchAccountsConfiguration()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.calling_accounts).isVisible = canLaunchAccountsConfiguration()
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        binding.apply {
            settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
            settingsUseEnglish.isChecked = config.useEnglish
            settingsUseEnglishHolder.setOnClickListener {
                settingsUseEnglish.toggle()
                config.useEnglish = settingsUseEnglish.isChecked
                exitProcess(0)
            }
        }
    }

    private fun setupLanguage() {
        binding.apply {
            settingsLanguage.text = Locale.getDefault().displayLanguage
            settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        }
    }

    private fun setupManageBlockedNumbers() {
        binding.apply {
            settingsManageBlockedNumbersLabel.text = getString(R.string.manage_blocked_numbers)
            settingsManageBlockedNumbersHolder.beVisibleIf(isNougatPlus())
            settingsManageBlockedNumbersHolder.setOnClickListener {
                Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                    startActivity(this)
                }
            }
        }
    }

    private fun setupManageBlockedKeywords() = binding.apply {
        settingsManageBlockedKeywords.text = getString(R.string.manage_blocked_keywords)
        settingsManageBlockedKeywordsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedKeywordsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupSpamReputationThreshold() = binding.apply {
        settingsSpamReputationValue.text = getSpamReputationThresholdText()
        settingsSpamReputationHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(SPAM_REPUTATION_AGGRESSIVE, getString(R.string.spam_reputation_aggressive)),
                RadioItem(SPAM_REPUTATION_BALANCED, getString(R.string.spam_reputation_balanced)),
                RadioItem(SPAM_REPUTATION_CONSERVATIVE, getString(R.string.spam_reputation_conservative)),
                RadioItem(
                    SPAM_REPUTATION_VERY_CONSERVATIVE,
                    getString(R.string.spam_reputation_very_conservative)
                ),
            )

            RadioGroupDialog(this@SettingsActivity, items, messagesConfig.spamReputationThreshold) {
                messagesConfig.spamReputationThreshold = it as Int
                settingsSpamReputationValue.text = getSpamReputationThresholdText()
            }
        }
    }

    private fun setupAiSpamSettings() = binding.apply {
        settingsAiSpamEnabled.isChecked = messagesConfig.aiSpamEnabled
        settingsAiSpamEnabledHolder.setOnClickListener {
            settingsAiSpamEnabled.toggle()
            messagesConfig.aiSpamEnabled = settingsAiSpamEnabled.isChecked
            updateAiSpamSettingsUi()
        }
        settingsAiSpamModelSourceHolder.setOnClickListener {
            showAiSpamModelSourceDialog()
        }
        settingsAiSpamModelUpdateHolder.setOnClickListener {
            AiSpamModelManager.requestModelUpdate(this@SettingsActivity) { updated ->
                if (updated) {
                    toast(R.string.ai_spam_model_updated)
                } else {
                    toast(R.string.ai_spam_model_update_failed)
                }
                updateAiSpamSettingsUi()
            }
        }
        updateAiSpamSettingsUi()
    }

    private fun updateAiSpamSettingsUi() = binding.apply {
        settingsAiSpamModelSourceValue.text = getAiSpamModelSourceText()
        settingsAiSpamModelUpdateValue.text = AiSpamModelManager.getModelStatusText(this@SettingsActivity)
        val enabled = messagesConfig.aiSpamEnabled
        settingsAiSpamModelSourceHolder.isEnabled = enabled
        settingsAiSpamModelUpdateHolder.isEnabled = enabled
        settingsAiSpamModelSourceValue.alpha = if (enabled) 1f else 0.4f
        settingsAiSpamModelUpdateValue.alpha = if (enabled) 1f else 0.4f
    }

    private fun getAiSpamModelSourceText(): String {
        val url = messagesConfig.aiSpamModelUrl.trim()
        return if (url.isEmpty()) {
            getString(R.string.ai_spam_model_not_set)
        } else {
            aiSpamModelOptions.firstOrNull { it.url == url }?.let { getString(it.nameRes) } ?: url
        }
    }

    private fun showAiSpamModelSourceDialog() {
        val items = aiSpamModelOptions.map {
            RadioItem(it.id, getString(it.nameRes))
        }
        val selectedId = aiSpamModelOptions.firstOrNull { it.url == messagesConfig.aiSpamModelUrl }?.id ?: -1
        RadioGroupDialog(this, ArrayList(items), selectedId) { selected ->
            val option = aiSpamModelOptions.firstOrNull { it.id == selected }
            if (option != null && option.url != messagesConfig.aiSpamModelUrl) {
                messagesConfig.aiSpamModelUrl = option.url
                AiSpamModelManager.resetModel(this)
                updateAiSpamSettingsUi()
            }
        }
    }

    private fun getSpamReputationThresholdText() = getString(
        when (messagesConfig.spamReputationThreshold) {
            SPAM_REPUTATION_AGGRESSIVE -> R.string.spam_reputation_aggressive
            SPAM_REPUTATION_CONSERVATIVE -> R.string.spam_reputation_conservative
            SPAM_REPUTATION_VERY_CONSERVATIVE -> R.string.spam_reputation_very_conservative
            else -> R.string.spam_reputation_balanced
        }
    )

    private fun setupCustomizeNotifications() = binding.apply {
        settingsCustomizeNotificationsHolder.setOnClickListener {
            launchCustomizeNotificationsIntent()
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = messagesConfig.showCharacterCounter
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
            messagesConfig.showCharacterCounter = settingsShowCharacterCounter.isChecked
        }
    }

    private fun setupUseSimpleCharacters() = binding.apply {
        settingsUseSimpleCharacters.isChecked = messagesConfig.useSimpleCharacters
        settingsUseSimpleCharactersHolder.setOnClickListener {
            settingsUseSimpleCharacters.toggle()
            messagesConfig.useSimpleCharacters = settingsUseSimpleCharacters.isChecked
        }
    }

    private fun setupSendOnEnter() = binding.apply {
        settingsSendOnEnter.isChecked = messagesConfig.sendOnEnter
        settingsSendOnEnterHolder.setOnClickListener {
            settingsSendOnEnter.toggle()
            messagesConfig.sendOnEnter = settingsSendOnEnter.isChecked
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = messagesConfig.enableDeliveryReports
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
            messagesConfig.enableDeliveryReports = settingsEnableDeliveryReports.isChecked
        }
    }

    private fun setupSendLongMessageAsMMS() = binding.apply {
        settingsSendLongMessageMms.isChecked = messagesConfig.sendLongMessageMMS
        settingsSendLongMessageMmsHolder.setOnClickListener {
            settingsSendLongMessageMms.toggle()
            messagesConfig.sendLongMessageMMS = settingsSendLongMessageMms.isChecked
        }
    }

    private fun setupGroupMessageAsMMS() = binding.apply {
        settingsSendGroupMessageMms.isChecked = messagesConfig.sendGroupMessageMMS
        settingsSendGroupMessageMmsHolder.setOnClickListener {
            settingsSendGroupMessageMms.toggle()
            messagesConfig.sendGroupMessageMMS = settingsSendGroupMessageMms.isChecked
        }
    }

    private fun setupKeepConversationsArchived() = binding.apply {
        settingsKeepConversationsArchived.isChecked = messagesConfig.keepConversationsArchived
        settingsKeepConversationsArchivedHolder.setOnClickListener {
            settingsKeepConversationsArchived.toggle()
            messagesConfig.keepConversationsArchived = settingsKeepConversationsArchived.isChecked
        }
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(org.fossify.commons.R.string.nothing)),
            )

            RadioGroupDialog(this@SettingsActivity, items, messagesConfig.lockScreenVisibilitySetting) {
                messagesConfig.lockScreenVisibilitySetting = it as Int
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (messagesConfig.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> org.fossify.commons.R.string.nothing
        }
    )

    private fun setupMMSFileSizeLimit() = binding.apply {
        settingsMmsFileSizeLimit.text = getMmsFileLimitText()
        settingsMmsFileSizeLimitHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(7, getString(R.string.mms_file_size_limit_none), FILE_SIZE_NONE),
                RadioItem(6, getString(R.string.mms_file_size_limit_2mb), FILE_SIZE_2_MB),
                RadioItem(5, getString(R.string.mms_file_size_limit_1mb), FILE_SIZE_1_MB),
                RadioItem(4, getString(R.string.mms_file_size_limit_600kb), FILE_SIZE_600_KB),
                RadioItem(3, getString(R.string.mms_file_size_limit_300kb), FILE_SIZE_300_KB),
                RadioItem(2, getString(R.string.mms_file_size_limit_200kb), FILE_SIZE_200_KB),
                RadioItem(1, getString(R.string.mms_file_size_limit_100kb), FILE_SIZE_100_KB),
            )

            val checkedItemId = items.find { it.value == messagesConfig.mmsFileSizeLimit }?.id ?: 7
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                messagesConfig.mmsFileSizeLimit = it as Long
                settingsMmsFileSizeLimit.text = getMmsFileLimitText()
            }
        }
    }

    private fun setupUseRecycleBin() = binding.apply {
        updateRecycleBinButtons()
        settingsUseRecycleBin.isChecked = messagesConfig.useRecycleBin
        settingsUseRecycleBin.text = formatWithDeprecatedBadge(
            labelRes = org.fossify.commons.R.string.move_items_into_recycle_bin
        )
        settingsUseRecycleBinHolder.setOnClickListener {
            settingsUseRecycleBin.toggle()
            messagesConfig.useRecycleBin = settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() = binding.apply {
        settingsEmptyRecycleBinHolder.beVisibleIf(messagesConfig.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() = binding.apply {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread {
                settingsEmptyRecycleBinSize.text =
                    resources.getQuantityString(
                        R.plurals.delete_messages,
                        recycleBinMessages,
                        recycleBinMessages
                    )
            }
        }

        settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinMessages == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this@SettingsActivity,
                    message = "",
                    messageId = R.string.empty_recycle_bin_messages_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                    }
                    recycleBinMessages = 0
                    settingsEmptyRecycleBinSize.text =
                        resources.getQuantityString(
                            R.plurals.delete_messages,
                            recycleBinMessages,
                            recycleBinMessages
                        )
                }
            }
        }
    }

    private fun setupAppPasswordProtection() = binding.apply {
        settingsAppPasswordProtection.isChecked = messagesConfig.isAppPasswordProtectionOn
        settingsAppPasswordProtectionHolder.setOnClickListener {
            val tabToShow = if (messagesConfig.isAppPasswordProtectionOn) {
                messagesConfig.appProtectionType
            } else {
                SHOW_ALL_TABS
            }

            SecurityDialog(
                activity = this@SettingsActivity,
                requiredHash = messagesConfig.appPasswordHash,
                showTabIndex = tabToShow
            ) { hash, type, success ->
                if (success) {
                    val hasPasswordProtection = messagesConfig.isAppPasswordProtectionOn
                    settingsAppPasswordProtection.isChecked = !hasPasswordProtection
                    messagesConfig.isAppPasswordProtectionOn = !hasPasswordProtection
                    messagesConfig.appPasswordHash = if (hasPasswordProtection) "" else hash
                    messagesConfig.appProtectionType = type

                    if (messagesConfig.isAppPasswordProtectionOn) {
                        val confirmationTextId =
                            if (messagesConfig.appProtectionType == PROTECTION_FINGERPRINT) {
                                org.fossify.commons.R.string.fingerprint_setup_successfully
                            } else {
                                org.fossify.commons.R.string.protection_setup_successfully
                            }

                        ConfirmationDialog(
                            activity = this@SettingsActivity,
                            message = "",
                            messageId = confirmationTextId,
                            positive = org.fossify.commons.R.string.ok,
                            negative = 0
                        ) { }
                    }
                }
            }
        }
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            exportMessagesDialog = ExportMessagesDialog(this) { fileName ->
                saveMessagesDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getMessagesContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    private fun getMmsFileLimitText() = getString(
        when (messagesConfig.mmsFileSizeLimit) {
            FILE_SIZE_100_KB -> R.string.mms_file_size_limit_100kb
            FILE_SIZE_200_KB -> R.string.mms_file_size_limit_200kb
            FILE_SIZE_300_KB -> R.string.mms_file_size_limit_300kb
            FILE_SIZE_600_KB -> R.string.mms_file_size_limit_600kb
            FILE_SIZE_1_MB -> R.string.mms_file_size_limit_1mb
            FILE_SIZE_2_MB -> R.string.mms_file_size_limit_2mb
            else -> R.string.mms_file_size_limit_none
        }
    )

    private fun setupManageSpeedDial() {
        binding.settingsManageSpeedDialHolder.setOnClickListener {
            Intent(this, ManageSpeedDialActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupFontSize() {
        binding.settingsFontSize.text = getFontSizeText()
        binding.settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(R.string.extra_large))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                binding.settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupManageShownTabs() {
        binding.settingsManageTabsHolder.setOnClickListener {
            ManageVisibleTabsDialog(this)
        }
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_CONTACTS, getString(R.string.contacts_tab)),
                RadioItem(TAB_FAVORITES, getString(R.string.favorites_tab)),
                RadioItem(TAB_CALL_HISTORY, getString(R.string.call_history_tab)),
                RadioItem(TAB_MESSAGES, getString(R.string.messages_tab)),
                RadioItem(TAB_LAST_USED, getString(R.string.last_used_tab))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.defaultTab) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            TAB_MESSAGES -> R.string.messages_tab
            else -> R.string.last_used_tab
        }
    )

    private fun setupDialPadOpen() {
        binding.apply {
            settingsOpenDialpadAtLaunch.isChecked = config.openDialPadAtLaunch
            settingsOpenDialpadAtLaunchHolder.setOnClickListener {
                settingsOpenDialpadAtLaunch.toggle()
                config.openDialPadAtLaunch = settingsOpenDialpadAtLaunch.isChecked
            }
        }
    }

    private fun setupGroupSubsequentCalls() {
        binding.apply {
            settingsGroupSubsequentCalls.isChecked = config.groupSubsequentCalls
            settingsGroupSubsequentCallsHolder.setOnClickListener {
                settingsGroupSubsequentCalls.toggle()
                config.groupSubsequentCalls = settingsGroupSubsequentCalls.isChecked
            }
        }
    }

    private fun setupBlockNegativeRatings() = binding.apply {
        settingsBlockNegativeRatings.isChecked = config.blockNegativeRatings
        settingsBlockNegativeRatingsHolder.setOnClickListener {
            settingsBlockNegativeRatings.toggle()
            config.blockNegativeRatings = settingsBlockNegativeRatings.isChecked
        }
    }

    private fun setupStartNameWithSurname() {
        binding.apply {
            settingsStartNameWithSurname.isChecked = config.startNameWithSurname
            settingsStartNameWithSurnameHolder.setOnClickListener {
                settingsStartNameWithSurname.toggle()
                config.startNameWithSurname = settingsStartNameWithSurname.isChecked
            }
        }
    }

    private fun setupFormatPhoneNumbers() {
        binding.settingsFormatPhoneNumbers.isChecked = config.formatPhoneNumbers
        binding.settingsFormatPhoneNumbersHolder.setOnClickListener {
            binding.settingsFormatPhoneNumbers.toggle()
            config.formatPhoneNumbers = binding.settingsFormatPhoneNumbers.isChecked
        }
    }

    private fun setupDialpadVibrations() {
        binding.apply {
            settingsDialpadVibration.isChecked = config.dialpadVibration
            settingsDialpadVibrationHolder.setOnClickListener {
                settingsDialpadVibration.toggle()
                config.dialpadVibration = settingsDialpadVibration.isChecked
            }
        }
    }

    private fun setupDialpadNumbers() {
        binding.apply {
            settingsHideDialpadNumbers.isChecked = config.hideDialpadNumbers
            settingsHideDialpadNumbersHolder.setOnClickListener {
                settingsHideDialpadNumbers.toggle()
                config.hideDialpadNumbers = settingsHideDialpadNumbers.isChecked
            }
        }
    }

    private fun setupDialpadBeeps() {
        binding.apply {
            settingsDialpadBeeps.isChecked = config.dialpadBeeps
            settingsDialpadBeepsHolder.setOnClickListener {
                settingsDialpadBeeps.toggle()
                config.dialpadBeeps = settingsDialpadBeeps.isChecked
            }
        }
    }

    private fun setupShowCallConfirmation() {
        binding.apply {
            settingsShowCallConfirmation.isChecked = config.showCallConfirmation
            settingsShowCallConfirmationHolder.setOnClickListener {
                settingsShowCallConfirmation.toggle()
                config.showCallConfirmation = settingsShowCallConfirmation.isChecked
            }
        }
    }

    private fun setupDisableProximitySensor() {
        binding.apply {
            settingsDisableProximitySensor.isChecked = config.disableProximitySensor
            settingsDisableProximitySensorHolder.setOnClickListener {
                settingsDisableProximitySensor.toggle()
                config.disableProximitySensor = settingsDisableProximitySensor.isChecked
            }
        }
    }

    private fun setupDisableSwipeToAnswer() {
        binding.apply {
            settingsDisableSwipeToAnswer.isChecked = config.disableSwipeToAnswer
            settingsDisableSwipeToAnswerHolder.setOnClickListener {
                settingsDisableSwipeToAnswer.toggle()
                config.disableSwipeToAnswer = settingsDisableSwipeToAnswer.isChecked
            }
        }
    }

    private fun setupAlwaysShowFullscreen() {
        binding.apply {
            settingsAlwaysShowFullscreen.isChecked = config.alwaysShowFullscreen
            settingsAlwaysShowFullscreenHolder.setOnClickListener {
                settingsAlwaysShowFullscreen.toggle()
                config.alwaysShowFullscreen = settingsAlwaysShowFullscreen.isChecked
            }
        }
    }

    private fun setupMeshMode() = binding.apply {
        val meshConfig = MeshConfig.newInstance(this@SettingsActivity)
        settingsMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
        settingsMeshModeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(MeshMode.STANDARD_ONLY.id, getString(R.string.mesh_mode_standard)),
                RadioItem(MeshMode.MESH_WITH_FALLBACK.id, getString(R.string.mesh_mode_fallback)),
                RadioItem(MeshMode.MESH_ONLY.id, getString(R.string.mesh_mode_mesh_only))
            )

            RadioGroupDialog(this@SettingsActivity, items, meshConfig.meshMode) {
                meshConfig.meshMode = it as Int
                settingsMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
                updateMeshRoutingUi(meshConfig)
                updateMeshStatus(meshConfig)
                MeshManager.sync(this@SettingsActivity)
            }
        }

        updateMeshRoutingUi(meshConfig)
        updateMeshStatus(meshConfig)
    }

    private fun setupMeshRouting() = binding.apply {
        val meshConfig = MeshConfig.newInstance(this@SettingsActivity)
        settingsMeshRouting.isChecked = meshConfig.meshRoutingEnabled
        settingsMeshRoutingHolder.setOnClickListener {
            settingsMeshRouting.toggle()
            val routingEnabled = settingsMeshRouting.isChecked
            meshConfig.meshRoutingEnabled = routingEnabled
            if (routingEnabled && meshConfig.getMeshMode() == MeshMode.STANDARD_ONLY) {
                meshConfig.meshMode = MeshMode.MESH_WITH_FALLBACK.id
                settingsMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
            }
            updateMeshRoutingUi(meshConfig)
            updateMeshStatus(meshConfig)
            MeshManager.sync(this@SettingsActivity)
        }
    }

    private fun updateMeshRoutingUi(meshConfig: MeshConfig) = binding.apply {
        val isMeshEnabled = meshConfig.getMeshMode() != MeshMode.STANDARD_ONLY
        settingsMeshRouting.isEnabled = isMeshEnabled
        settingsMeshRoutingHolder.isEnabled = isMeshEnabled
        if (!isMeshEnabled && meshConfig.meshRoutingEnabled) {
            meshConfig.meshRoutingEnabled = false
            settingsMeshRouting.isChecked = false
        }
    }

    private fun setupMeshStatus() {
        updateMeshStatus(MeshConfig.newInstance(this))
    }

    private fun updateMeshStatus(meshConfig: MeshConfig) = binding.apply {
        val neighbors = RnsNode.getDirectNeighborCount()
        val routingStatus = if (meshConfig.meshRoutingEnabled && RnsNode.hasRecentRoutingActivity()) {
            getString(R.string.mesh_routing_in_use)
        } else {
            getString(R.string.mesh_routing_idle)
        }
        settingsMeshStatusValue.text = getString(R.string.mesh_service_status, neighbors, routingStatus)
    }

    private fun getMeshModeLabel(mode: MeshMode): String {
        return when (mode) {
            MeshMode.STANDARD_ONLY -> getString(R.string.mesh_mode_standard)
            MeshMode.MESH_WITH_FALLBACK -> getString(R.string.mesh_mode_fallback)
            MeshMode.MESH_ONLY -> getString(R.string.mesh_mode_mesh_only)
        }
    }

    private fun setupShowBlockedCallNotifications() = binding.apply {
        settingsShowBlockedCallNotifications.isChecked = config.showBlockedCallNotifications
        settingsShowBlockedCallNotificationsHolder.setOnClickListener {
            settingsShowBlockedCallNotifications.toggle()
            config.showBlockedCallNotifications = settingsShowBlockedCallNotifications.isChecked
        }
    }

    private fun setupShowCallRatingNotifications() = binding.apply {
        settingsShowCallRatingNotifications.isChecked = config.showCallRatingNotifications
        settingsShowCallRatingNotificationsHolder.setOnClickListener {
            settingsShowCallRatingNotifications.toggle()
            config.showCallRatingNotifications = settingsShowCallRatingNotifications.isChecked
        }
    }

    private fun setupCallsExport() {
        binding.settingsExportCallsHolder.setOnClickListener {
            ExportCallHistoryDialog(this) { filename ->
                saveDocument.launch("$filename.json")
            }
        }
    }

    private fun setupCallsImport() {
        binding.settingsImportCallsHolder.setOnClickListener {
            getContent.launch(IMPORT_CALL_HISTORY_FILE_TYPES.toTypedArray())
        }
    }

    private fun importCallHistory(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)!!.use { inputStream ->
                inputStream.bufferedReader().readText()
            }

            val objects = Json.decodeFromString<List<RecentCall>>(jsonString)

            if (objects.isEmpty()) {
                toast(R.string.no_entries_for_importing)
                return
            }

            RecentsHelper(this).restoreRecentCalls(this, objects) {
                toast(R.string.importing_successful)
            }
        } catch (_: SerializationException) {
            toast(R.string.invalid_file_format)
        } catch (_: IllegalArgumentException) {
            toast(R.string.invalid_file_format)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun exportCallHistory(recents: List<RecentCall>, uri: Uri) {
        if (recents.isEmpty()) {
            toast(R.string.no_entries_for_exporting)
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(uri)!!

                val jsonString = Json.encodeToString(recents)
                outputStream.use {
                    it.write(jsonString.toByteArray())
                }
                toast(R.string.exporting_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}
