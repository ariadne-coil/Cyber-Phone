package org.fossify.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val BLOCK_NEGATIVE_RATINGS = "block_negative_ratings"
const val SHOW_BLOCKED_CALL_NOTIFICATIONS = "show_blocked_call_notifications"
const val SHOW_CALL_RATING_NOTIFICATIONS = "show_call_rating_notifications"
const val WALLET_SELECTED_FEDERATION_ID = "wallet_selected_federation_id"
const val WALLET_DIRECTORY_JSON = "wallet_directory_json"
const val WALLET_DIRECTORY_LAST_SYNC_MS = "wallet_directory_last_sync_ms"
const val WALLET_DIRECTORY_LAST_HASH = "wallet_directory_last_hash"
const val WALLET_DIRECTORY_LAST_UPDATED_AT_MS = "wallet_directory_last_updated_at_ms"
const val WALLET_BTC_USD_RATE = "wallet_btc_usd_rate"
const val WALLET_BTC_USD_RATE_LAST_SYNC_MS = "wallet_btc_usd_rate_last_sync_ms"
const val WALLET_LAST_INVOICE = "wallet_last_invoice"
const val WALLET_LAST_INVOICE_CREATED_MS = "wallet_last_invoice_created_ms"
const val WALLET_LAST_LIGHTNING_ADDRESS = "wallet_last_lightning_address"
const val WALLET_RECURRING_ROOT_PRIVKEY = "wallet_recurring_root_privkey"
const val WALLET_FEDERATION_ZAP_CAPABLE = "wallet_federation_zap_capable"
const val WALLET_LAST_ONCHAIN_ADDRESS = "wallet_last_onchain_address"
const val WALLET_LAST_ONCHAIN_ADDRESS_CREATED_MS = "wallet_last_onchain_address_created_ms"
const val WALLET_LIQUIDITY_PROVIDER_MODE = "wallet_liquidity_provider_mode"
const val WALLET_LIQUIDITY_PROVIDER_ID = "wallet_liquidity_provider_id"
const val WALLET_LIQUIDITY_PROVIDER_STATS_JSON = "wallet_liquidity_provider_stats_json"
const val WALLET_LIQUIDITY_CUSTOM_NAME = "wallet_liquidity_custom_name"
const val WALLET_LIQUIDITY_CUSTOM_NETWORK = "wallet_liquidity_custom_network"
const val WALLET_LIQUIDITY_CUSTOM_NODE_ID = "wallet_liquidity_custom_node_id"
const val WALLET_LIQUIDITY_CUSTOM_ADDRESS = "wallet_liquidity_custom_address"
const val WALLET_LIQUIDITY_CUSTOM_TOKEN = "wallet_liquidity_custom_token"

const val TAB_MESSAGES = 8
const val TAB_WALLET = 16
const val ALL_TABS_MASK = TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY or TAB_MESSAGES or TAB_WALLET

val tabsList = arrayListOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY, TAB_MESSAGES, TAB_WALLET)

private const val PATH = "org.fossify.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds
