package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
import me.grantland.widget.AutofitHelper
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.adapters.ViewPagerAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.dialogs.ChangeSortingDialog
import org.fossify.phone.dialogs.FilterContactSourcesDialog
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.handleFullScreenNotificationsPermission
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.fragments.ContactsFragment
import org.fossify.phone.fragments.FavoritesFragment
import org.fossify.phone.fragments.MessagesFragment
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.fragments.RecentsFragment
import org.fossify.phone.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.TAB_MESSAGES
import org.fossify.phone.helpers.TAB_WALLET
import org.fossify.phone.helpers.tabsList
import org.fossify.phone.models.Events
import org.fossify.mesh.MeshManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false
    private var mainHolderBehavior: CoordinatorLayout.Behavior<*>? = null
    private var fragmentsInitialized = false
    private var defaultPhonePromptAttempted = false
    private var defaultSmsPromptAttempted = false
    var cachedContacts = ArrayList<Contact>()
    private val setDefaultSmsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            getMessagesFragment()?.handleActivityResult(
                MessagesFragment.REQUEST_CODE_SET_DEFAULT_SMS,
                result.resultCode
            )
        }
    private val walletContactPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            getWalletFragment()?.handleWalletContactPickerResult(result.resultCode, result.data)
        }
    private val walletBackupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            getWalletFragment()?.handleWalletBackupCreateResult(result.resultCode, result.data)
        }
    private val walletBackupRestoreLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            getWalletFragment()?.handleWalletBackupRestoreResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        Contact.sorting = config.sorting
        requestDefaultPhoneRoleThenSensitivePermissions(
            defaultPromptAlreadyShown = intent.getBooleanExtra(SplashActivity.EXTRA_DEFAULT_DIALER_PROMPT_SHOWN, false)
        )
    }

    override fun onResume() {
        super.onResume()
        // Only start mesh if needed. Do not stop it here, as stopping Wi‑Fi Direct / sharing
        // components on resume can trigger disruptive OEM/system dialogs.
        MeshManager.ensureRunning(this)
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateMenuColors()
        val properPrimaryColor = getProperPrimaryColor()
        val dialpadIcon = resources.getColoredDrawableWithColor(R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        binding.mainDialpadButton.setImageDrawable(dialpadIcon)

        updateTextColors(binding.mainHolder)
        setupTabColors()
        updateTabUi(getVisibleTabs().getOrNull(binding.viewPager.currentItem) ?: TAB_CONTACTS)
        if (getVisibleTabs().getOrNull(binding.viewPager.currentItem) == TAB_MESSAGES) {
            getMessagesFragment()?.onTabSelected()
        }

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        if (!binding.mainMenu.isSearchOpen) {
            refreshItems(true)
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        checkShortcuts()
        Handler(Looper.getMainLooper()).postDelayed({
            getRecentsFragment()?.refreshItems()
        }, 2000)

        Handler(Looper.getMainLooper()).postDelayed({
            continueDefaultHandlerSetupAfterRoleReturn()
        }, 500)
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MessagesFragment.REQUEST_CODE_SET_DEFAULT_SMS) {
            getMessagesFragment()?.handleActivityResult(requestCode, resultCode)
            return
        }
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            requestDefaultPhoneRoleThenSensitivePermissions(defaultPromptAlreadyShown = true)
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment()
            findItem(R.id.sort).isVisible = currentFragment != getRecentsFragment()
            findItem(R.id.filter).isVisible = currentFragment != getRecentsFragment()
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment()
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment() && config.viewType == VIEW_TYPE_GRID
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
            setupMenu()

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.onSearchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.onSearchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.clear_call_history -> clearCallHistory()
                    R.id.create_new_contact -> launchCreateNewContactIntent()
                    R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                    R.id.filter -> showFilterDialog()
                    R.id.manage_e2e_keys -> launchManageE2eKeys()
                    R.id.settings -> launchSettings()
                    R.id.change_view_type -> changeViewType()
                    R.id.column_count -> changeColumnCount()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        RadioGroupDialog(this, ArrayList(items), currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this) {
            refreshMenuItems()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun selectTab(tabType: Int) {
        val position = getVisibleTabs().indexOf(tabType)
        if (position != -1) {
            binding.mainTabsHolder.getTabAt(position)?.select()
        }
    }

    private fun checkContactPermissions() {
        handlePermissionAfterDefaultHandlerRoles(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun requestDefaultPhoneRoleThenSensitivePermissions(defaultPromptAlreadyShown: Boolean = false) {
        if (isDefaultPhoneRoleHeld()) {
            requestDefaultSmsRoleThenSensitivePermissions()
            return
        }

        if (defaultPromptAlreadyShown) {
            handleDefaultPhoneSetupIncomplete()
            return
        }

        defaultPhonePromptAttempted = true
        requestDefaultDialerRoleIfNeeded { isDefault ->
            if (isDefault) {
                requestDefaultSmsRoleThenSensitivePermissions()
            } else {
                handleDefaultPhoneSetupIncomplete()
            }
        }
    }

    private fun requestDefaultSmsRoleThenSensitivePermissions(defaultPromptAlreadyShown: Boolean = false) {
        if (isDefaultSmsRoleHeld()) {
            requestSensitiveRuntimePermissionsForDefaultHandlers {
                handleDefaultHandlersSetupComplete()
            }
            return
        }

        if (defaultPromptAlreadyShown) {
            handleDefaultSmsSetupIncomplete()
            return
        }

        defaultSmsPromptAttempted = true
        requestDefaultSmsRoleIfNeeded { isDefault ->
            if (isDefault) {
                requestSensitiveRuntimePermissionsForDefaultHandlers {
                    handleDefaultHandlersSetupComplete()
                }
            } else {
                handleDefaultSmsSetupIncomplete()
            }
        }
    }

    private fun continueDefaultHandlerSetupAfterRoleReturn() {
        if (isFinishing || isDestroyed || fragmentsInitialized) {
            return
        }

        if (isDefaultPhoneRoleHeld() && !isDefaultSmsRoleHeld() && !defaultSmsPromptAttempted) {
            requestDefaultSmsRoleThenSensitivePermissions()
        } else if (isDefaultPhoneRoleHeld() && isDefaultSmsRoleHeld()) {
            requestSensitiveRuntimePermissionsForDefaultHandlers {
                handleDefaultHandlersSetupComplete()
            }
        } else if (!isDefaultPhoneRoleHeld() && !defaultPhonePromptAttempted) {
            requestDefaultPhoneRoleThenSensitivePermissions()
        }
    }

    private fun requestSensitiveRuntimePermissionsForDefaultHandlers(onComplete: () -> Unit) {
        requestCallLogRuntimePermissionsForDefaultDialer {
            requestSmsRuntimePermissionsForDefaultSms {
                onComplete()
            }
        }
    }

    private fun requestCallLogRuntimePermissionsForDefaultDialer(onComplete: () -> Unit) {
        handlePermissionAfterDefaultHandlerRoles(PERMISSION_READ_CALL_LOG) {
            handlePermissionAfterDefaultHandlerRoles(PERMISSION_WRITE_CALL_LOG) {
                onComplete()
            }
        }
    }

    private fun requestSmsRuntimePermissionsForDefaultSms(onComplete: () -> Unit) {
        handlePermissionAfterDefaultHandlerRoles(PERMISSION_READ_SMS) {
            handlePermissionAfterDefaultHandlerRoles(PERMISSION_SEND_SMS) {
                onComplete()
            }
        }
    }

    private fun handleDefaultHandlersSetupComplete() {
        checkContactPermissions()
        showOverlayPermissionSnackbarIfNeeded()
        handleFullScreenNotificationsPermission { granted ->
            if (!granted) {
                toast(org.fossify.commons.R.string.notifications_disabled)
            }
        }
    }

    private fun handleDefaultPhoneSetupIncomplete() {
        initFragments()
        showDefaultPhoneSnackbar()
    }

    private fun handleDefaultSmsSetupIncomplete() {
        initFragments()
        showDefaultSmsSnackbar()
    }

    private fun showDefaultPhoneSnackbar() {
        Snackbar.make(
            binding.mainHolder,
            R.string.default_phone_app_prompt,
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.ok) {
            requestDefaultPhoneRoleThenSensitivePermissions()
        }.apply {
            setBackgroundTint(getProperBackgroundColor().darkenColor())
            setTextColor(getProperTextColor())
            setActionTextColor(getProperTextColor())
            show()
        }
    }

    private fun showDefaultSmsSnackbar() {
        Snackbar.make(
            binding.mainHolder,
            R.string.default_sms_app_prompt,
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.ok) {
            requestDefaultSmsRoleThenSensitivePermissions()
        }.apply {
            setBackgroundTint(getProperBackgroundColor().darkenColor())
            setTextColor(getProperTextColor())
            setActionTextColor(getProperTextColor())
            show()
        }
    }

    private fun showOverlayPermissionSnackbarIfNeeded() {
        if (config.wasOverlaySnackbarConfirmed || Settings.canDrawOverlays(this)) {
            return
        }

        Snackbar.make(
            binding.mainHolder,
            R.string.allow_displaying_over_other_apps,
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.ok) {
            config.wasOverlaySnackbarConfirmed = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }.apply {
            setBackgroundTint(getProperBackgroundColor().darkenColor())
            setTextColor(getProperTextColor())
            setActionTextColor(getProperTextColor())
            show()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    getRecentsFragment()?.refreshItems(invalidate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = ContextCompat.getDrawable(this, R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[binding.viewPager.currentItem])

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): List<Int> {
        return getVisibleTabs().map { getTabIconRes(it, selected = true) }
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {
        return ArrayList(getVisibleTabs().map { getTabIconRes(it, selected = false) })
    }

    private fun initFragments() {
        if (fragmentsInitialized) {
            return
        }
        fragmentsInitialized = true

        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                updateTabUi(getVisibleTabs().getOrNull(position) ?: TAB_CONTACTS)
                refreshMenuItems()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        binding.mainTabsHolder.onGlobalLayout {
            Handler(Looper.getMainLooper()).postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = binding.mainTabsHolder.tabCount - 1
                }

                binding.mainTabsHolder.getTabAt(wantedTab)?.select()
                refreshMenuItems()
            }, 100L)
        }

        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        binding.mainTabsHolder.removeAllTabs()
        var visibleIndex = 0
        tabsList.forEach { value ->
            if (config.showTabs and value != 0) {
                binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                    customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(visibleIndex))
                    customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(visibleIndex)
                    AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                    binding.mainTabsHolder.addTab(this)
                }
                visibleIndex++
            }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
            },
            tabSelectedAction = {
                val tabType = getVisibleTabs().getOrNull(it.position)
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])

                val lastPosition = binding.mainTabsHolder.tabCount - 1
                if (it.position == lastPosition && config.showTabs and TAB_CALL_HISTORY > 0) {
                    clearMissedCalls()
                }
                updateTabUi(tabType ?: TAB_CONTACTS)
                if (tabType == TAB_MESSAGES) {
                    getMessagesFragment()?.onTabSelected()
                } else {
                    if (binding.mainMenu.isSearchOpen) {
                        getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                    } else {
                        getCurrentFragment()?.onSearchQueryChanged("")
                    }
                    if (tabType == TAB_CONTACTS) {
                        getContactsFragment()?.refreshItems()
                    }
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    fun launchDefaultSmsRoleIntent(intent: Intent) {
        setDefaultSmsLauncher.launch(intent)
    }

    fun launchWalletContactPickerIntent(intent: Intent) {
        walletContactPickerLauncher.launch(intent)
    }

    fun launchWalletBackupCreateIntent(intent: Intent) {
        walletBackupCreateLauncher.launch(intent)
    }

    fun launchWalletBackupRestoreIntent(intent: Intent) {
        walletBackupRestoreLauncher.launch(intent)
    }

    private fun getTabIcon(position: Int): Drawable {
        val tabType = getVisibleTabs().getOrNull(position) ?: TAB_CONTACTS
        val drawableId = getTabIconRes(tabType, selected = true)
        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {
        val tabType = getVisibleTabs().getOrNull(position) ?: TAB_CONTACTS
        val stringId = when (tabType) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            TAB_MESSAGES -> R.string.messages_tab
            TAB_WALLET -> R.string.wallet_tab
            else -> R.string.contacts_tab
        }
        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                }
            } else {
                refreshFragments()
            }
        }
    }

    private fun launchDialpad() {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    fun refreshFragments() {
        cacheContacts()
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
        getMessagesFragment()?.refreshItems()
        getWalletFragment()?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>?>()

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(getContactsFragment())
        }

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(getFavoritesFragment())
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(getRecentsFragment())
        }

        if (showTabs and TAB_MESSAGES > 0) {
            fragments.add(getMessagesFragment())
        }

        if (showTabs and TAB_WALLET > 0) {
            fragments.add(getWalletFragment())
        }

        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getMessagesFragment(): MessagesFragment? = findViewById(R.id.messages_fragment)

    private fun getWalletFragment(): org.fossify.phone.fragments.WalletFragment? = findViewById(R.id.wallet_fragment)

    private fun getVisibleTabs(): List<Int> = tabsList.filter { it and config.showTabs != 0 }

    private fun updateTabUi(tabType: Int) {
        val isMessagesTab = tabType == TAB_MESSAGES
        val hideDialerButton = isMessagesTab || tabType == TAB_WALLET
        binding.mainMenu.beGoneIf(isMessagesTab)
        binding.mainDialpadButton.beGoneIf(hideDialerButton)
        updateMainHolderBehavior(isMessagesTab)
        if (isMessagesTab) {
            binding.mainMenu.closeSearch()
        }
    }

    private fun updateMainHolderBehavior(isMessagesTab: Boolean) {
        val params = binding.mainHolder.layoutParams as CoordinatorLayout.LayoutParams
        if (mainHolderBehavior == null) {
            mainHolderBehavior = params.behavior
        }

        params.behavior = if (isMessagesTab) null else mainHolderBehavior
        binding.mainHolder.layoutParams = params
    }

    private fun getTabIconRes(tabType: Int, selected: Boolean): Int {
        return when (tabType) {
            TAB_CONTACTS -> if (selected) R.drawable.ic_person_vector else R.drawable.ic_person_outline_vector
            TAB_FAVORITES -> if (selected) R.drawable.ic_star_vector else R.drawable.ic_star_outline_vector
            TAB_CALL_HISTORY -> if (selected) R.drawable.ic_clock_filled_vector else R.drawable.ic_clock_vector
            TAB_MESSAGES -> R.drawable.ic_sms_vector
            TAB_WALLET -> if (selected) R.drawable.ic_wallet_vector else R.drawable.ic_wallet_outline_vector
            else -> R.drawable.ic_person_vector
        }
    }

    private fun getDefaultTab(): Int {
        val visibleTabs = getVisibleTabs()
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < binding.mainTabsHolder.tabCount) config.lastUsedViewPagerPage else 0
            else -> visibleTabs.indexOf(config.defaultTab).takeIf { it >= 0 } ?: 0
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        startActivity(Intent(this, CyberAboutActivity::class.java))
    }

    private fun launchManageE2eKeys() {
        startActivity(Intent(this, org.fossify.messages.activities.ManageE2eKeysActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getRecentsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    fun cacheContacts() {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(this).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
            } catch (ignored: Exception) {
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        getRecentsFragment()?.refreshItems()
    }
}
