package org.fossify.phone.wallet

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.extensions.config
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import org.json.JSONObject

@Serializable
data class FederationDirectory(
    val version: Int = 1,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val federations: List<FederationEntry> = emptyList(),
    @SerialName("liquidity_providers")
    val liquidityProviders: List<LiquidityProviderEntry> = emptyList(),
)

@Serializable
data class FederationEntry(
    val id: String,
    val name: String,
    // Backend kind: "ldk" (default) or "fedimint".
    val kind: String = "ldk",
    val invite: String = "",
    val network: String? = null,
    val website: String? = null,
    val description: String? = null,
    @SerialName("esplora_url")
    val esploraUrl: String? = null,
    @SerialName("rgs_url")
    val rgsUrl: String? = null,
    // Optional LSP (LSPS1) bootstrap. If present, the wallet can create JIT invoices for instant receive.
    @SerialName("lsps1_node_id")
    val lsps1NodeId: String? = null,
    @SerialName("lsps1_address")
    val lsps1Address: String? = null,
    @SerialName("lsps1_token")
    val lsps1Token: String? = null,
    @SerialName("onchain_deposits_disabled")
    val onchainDepositsDisabled: Boolean? = null,
    @SerialName("vetted_gateways")
    val vettedGateways: List<String> = emptyList(),
    @SerialName("recurringd_api")
    val recurringdApi: String? = null,
)

@Serializable
data class LiquidityProviderEntry(
    val id: String,
    val name: String,
    val network: String? = null,
    @SerialName("node_id")
    val nodeId: String,
    val address: String,
    val token: String? = null,
    val priority: Int = 0,
    @SerialName("federation_ids")
    val federationIds: List<String> = emptyList(),
    @SerialName("source_federation_id")
    val sourceFederationId: String? = null,
)

object FederationDirectoryManager {
    // Use Fedi's public federation meta feed as the primary source.
    // This is the same source family used by Fedi to discover public federations.
    private val DIRECTORY_URLS = listOf(
        "https://meta.dev.fedibtc.com/meta.json",
    )

    private const val ASSET_PATH = "wallet/federations.json"
    private const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    private const val MAX_DIRECTORY_BYTES = 1024 * 1024
    private const val ROLLBACK_TOLERANCE_MS = 6L * 60L * 60L * 1000L
    private const val TRUSTED_DIRECTORY_HOST = "meta.dev.fedibtc.com"
    private const val LIQUIDITY_MODE_AUTO = "auto"
    private const val LIQUIDITY_MODE_MANUAL = "manual"
    private const val PROVIDER_ID_PREFIX_FEDERATION = "federation:"
    private const val CUSTOM_PROVIDER_ID = "custom-provider"

    private data class ProviderOutcomeStats(
        var successes: Int = 0,
        var failures: Int = 0,
        var lastSuccessMs: Long = 0L,
        var lastFailureMs: Long = 0L,
    )

    // Hard fallback entries shipped in code so we always expose at least one Fedimint federation,
    // even if remote + cached + asset data is stale/incomplete.
    private val builtInFallbackFederations = listOf(
        FederationEntry(
            id = "btc-mainnet",
            name = "Bitcoin Mainnet",
            kind = "ldk",
            network = "bitcoin",
            website = "https://blockstream.info",
            description = "Self-custodial on-chain + Lightning wallet (LDK Node).",
            esploraUrl = "https://blockstream.info/api",
            rgsUrl = "https://rapidsync.lightningdevkit.org/snapshot",
        ),
        FederationEntry(
            id = "btc-testnet",
            name = "Bitcoin Testnet",
            kind = "ldk",
            network = "testnet",
            website = "https://blockstream.info/testnet",
            description = "Testnet wallet for development/testing (LDK Node).",
            esploraUrl = "https://blockstream.info/testnet/api",
            rgsUrl = "https://rapidsync.lightningdevkit.org/testnet/snapshot",
        ),
        FederationEntry(
            id = "fedimint-mutinynet",
            name = "Fedimint Testnet (Mutinynet)",
            kind = "fedimint",
            network = "testnet",
            website = "https://sdk.fedimint.org/",
            description = "Fedimint federation on testnet for experimentation.",
            invite = "fed11qgqzc2nhwden5te0vejkg6tdd9h8gepwvejkg6tdd9h8garhduhx6at5d9h8jmn9wshxxmmd9uqqzgxg6s3evnr6m9zdxr6hxkdkukexpcs3mn7mj3g5pc5dfh63l4tj6g9zk4er",
        ),
        FederationEntry(
            id = "fedimint-e-cash-club",
            name = "E-Cash Club",
            kind = "fedimint",
            network = "bitcoin",
            website = "https://www.fedi.xyz/",
            description = "Welcome to E-cash Club",
            invite = "fed11qgqpv9rhwden5te0vekjucm5wf3zu6t09amhxtcpqys2ajnveq8lc5ct6t25kztgrahdhxjptsmzujhjlc74upqnwqr05ggd78dhm",
        ),
        FederationEntry(
            id = "fedimint-bitcoin-principles",
            name = "Bitcoin Principles",
            kind = "fedimint",
            network = "bitcoin",
            website = "https://meta.dev.fedibtc.com/meta.json",
            description = "Welcome to the Bitcoin Principles Federation!",
            invite = "fed11qgqzygrhwden5te0v9cxjtnzd96xxmmfdec8y6twvd5hqmr9wvhxuet59upqzg9jzp5vsn6mzt9ylhun70jy85aa0sn7sepdp4fw5tjdeehah0hfmufvlqem",
        ),
    )

    private val builtInFallbackDirectory = FederationDirectory(
        version = 1,
        updatedAt = "2026-02-11",
        federations = builtInFallbackFederations,
    )

    private val directoryCertPinner = CertificatePinner.Builder()
        .add(TRUSTED_DIRECTORY_HOST, "sha256/trv1iOZSDXyCc/1A5xo174rtH5D7J285htkszwTqNa8=")
        .add(TRUSTED_DIRECTORY_HOST, "sha256/iFvwVyJSxnQdyaUvUERIf+8qk7gRze3612JMwoO3zdU=")
        .build()

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .certificatePinner(directoryCertPinner)
            .build()
    }
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    private fun decodeDirectory(raw: String): FederationDirectory? {
        val text = raw.trim()
        if (text.isBlank()) return null

        // Preferred schema.
        runCatching {
            return json.decodeFromString(FederationDirectory.serializer(), text)
        }

        // Legacy/simple schema: plain array of entries.
        runCatching {
            val entries = json.decodeFromString(ListSerializer(FederationEntry.serializer()), text)
            return FederationDirectory(federations = entries)
        }

        // Fedi meta.json schema: top-level object keyed by federation id.
        // We normalize it into our internal federation list.
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val entries = root.mapNotNull { (key, value) ->
                val obj = value.jsonObject
                val invite = normalizeInviteCode(obj["invite_code"]?.jsonPrimitive?.contentOrNull.orEmpty())
                if (invite.isBlank()) {
                    return@mapNotNull null
                }

                val name = obj["federation_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) {
                    return@mapNotNull null
                }

                val isPublic = obj["public"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.equals("true", ignoreCase = true) == true
                val previewMessage = obj["preview_message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val welcomeMessage = obj["welcome_message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val include = isPublic || previewMessage.isNotBlank() || welcomeMessage.isNotBlank()
                if (!include) {
                    return@mapNotNull null
                }

                val website = obj["meta_external_url"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: obj["tos_url"]?.jsonPrimitive?.contentOrNull?.trim()
                val description = previewMessage.ifBlank { welcomeMessage }
                val onchainDisabled = parseBooleanish(
                    obj["onchain_deposits_disabled"]?.jsonPrimitive?.contentOrNull
                )
                val vettedGateways = parseGatewayNodeIds(
                    obj["vetted_gateways"]?.jsonPrimitive?.contentOrNull
                )
                val recurringdApi = normalizeRecurringdApi(
                    obj["recurringd_api"]?.jsonPrimitive?.contentOrNull
                )

                FederationEntry(
                    id = key.trim().ifBlank { name.lowercase().replace(" ", "-") },
                    name = name,
                    kind = "fedimint",
                    invite = invite,
                    network = if (name.contains("testnet", ignoreCase = true) || name.contains("signet", ignoreCase = true)) "testnet" else "bitcoin",
                    website = website,
                    description = description.ifBlank { "Public Fedimint federation." },
                    onchainDepositsDisabled = onchainDisabled,
                    vettedGateways = vettedGateways,
                    recurringdApi = recurringdApi,
                )
            }.distinctBy { it.id }

            if (entries.isEmpty()) {
                return@runCatching
            }
            return FederationDirectory(
                version = 2,
                updatedAt = Instant.now().toString(),
                federations = entries,
            )
        }

        return null
    }

    private fun loadAssetDirectory(context: Context): FederationDirectory? {
        return try {
            val assetJson = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            decodeDirectory(assetJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeDirectories(primary: FederationDirectory?, fallback: FederationDirectory?): FederationDirectory? {
        if (primary == null && fallback == null) return null

        // Keep fallback defaults, then let primary override by id.
        val merged = linkedMapOf<String, FederationEntry>()
        fallback?.federations.orEmpty().forEach { entry ->
            val normalized = normalizeEntry(entry)
            val id = normalized.id.trim()
            if (id.isNotBlank()) {
                merged[id] = normalized
            }
        }
        primary?.federations.orEmpty().forEach { entry ->
            val normalized = normalizeEntry(entry)
            val id = normalized.id.trim()
            if (id.isNotBlank()) {
                merged[id] = mergeEntryKeepingValidFallback(merged[id], normalized)
            }
        }

        val mergedProviders = linkedMapOf<String, LiquidityProviderEntry>()
        fallback?.liquidityProviders.orEmpty().forEach { provider ->
            val normalized = normalizeLiquidityProvider(provider) ?: return@forEach
            mergedProviders[normalized.id] = normalized
        }
        primary?.liquidityProviders.orEmpty().forEach { provider ->
            val normalized = normalizeLiquidityProvider(provider) ?: return@forEach
            mergedProviders[normalized.id] = mergeLiquidityProviderKeepingValidFallback(
                mergedProviders[normalized.id],
                normalized,
            )
        }

        val mergedUpdatedAt = when {
            !primary?.updatedAt.isNullOrBlank() -> primary?.updatedAt
            else -> fallback?.updatedAt
        }

        return FederationDirectory(
            version = maxOf(primary?.version ?: 1, fallback?.version ?: 1),
            updatedAt = mergedUpdatedAt,
            federations = dedupeFederations(merged.values.toList()),
            liquidityProviders = dedupeLiquidityProviders(mergedProviders.values.toList()),
        )
    }

    private fun dedupeFederations(entries: List<FederationEntry>): List<FederationEntry> {
        if (entries.isEmpty()) return entries

        // Pass 1: keep latest by canonical key (invite/id).
        val byCanonical = dedupeKeepingLatest(entries.map { normalizeEntry(it) }) { canonicalFederationKey(it) }
        // Pass 2: keep latest by user-visible key (name+network) to collapse remote/fallback duplicates.
        return dedupeKeepingLatest(byCanonical) { displayFederationKey(it) }
    }

    private fun dedupeKeepingLatest(
        entries: List<FederationEntry>,
        keySelector: (FederationEntry) -> String,
    ): List<FederationEntry> {
        val seen = hashSetOf<String>()
        val dedupedReversed = arrayListOf<FederationEntry>()
        for (entry in entries.asReversed()) {
            val key = keySelector(entry)
            if (seen.add(key)) {
                dedupedReversed.add(entry)
            }
        }
        dedupedReversed.reverse()
        return dedupedReversed
    }

    private fun dedupeLiquidityProviders(entries: List<LiquidityProviderEntry>): List<LiquidityProviderEntry> {
        if (entries.isEmpty()) return entries

        // Pass 1: dedupe by id.
        val byId = linkedMapOf<String, LiquidityProviderEntry>()
        entries.forEach { entry ->
            byId[entry.id] = entry
        }

        // Pass 2: dedupe by canonical connection key (network + node + endpoint).
        val seenCanonical = hashSetOf<String>()
        val dedupedReversed = arrayListOf<LiquidityProviderEntry>()
        for (entry in byId.values.toList().asReversed()) {
            val key = canonicalLiquidityProviderKey(entry)
            if (seenCanonical.add(key)) {
                dedupedReversed.add(entry)
            }
        }
        dedupedReversed.reverse()
        return dedupedReversed
    }

    private fun canonicalLiquidityProviderKey(entry: LiquidityProviderEntry): String {
        return listOf(
            normalizeNetwork(entry.network),
            entry.nodeId.trim().lowercase(),
            entry.address.trim().lowercase(),
        ).joinToString(separator = "|")
    }

    private fun normalizeLiquidityProvider(provider: LiquidityProviderEntry): LiquidityProviderEntry? {
        val nodeId = provider.nodeId.trim()
        val address = provider.address.trim()
        if (nodeId.isBlank() || address.isBlank()) {
            return null
        }

        val generatedId = "lp-${sha256Hex("$nodeId|$address").take(12)}"
        val id = provider.id.trim().ifBlank { generatedId }
        val name = provider.name.trim().ifBlank { "Liquidity Provider" }
        val network = provider.network?.trim()?.ifBlank { null }?.let { normalizeNetwork(it) }
        val token = provider.token?.trim()?.ifBlank { null }
        val federationIds = provider.federationIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val sourceFederationId = provider.sourceFederationId?.trim()?.ifBlank { null }

        return provider.copy(
            id = id,
            name = name,
            network = network,
            nodeId = nodeId,
            address = address,
            token = token,
            federationIds = federationIds,
            sourceFederationId = sourceFederationId,
        )
    }

    private fun mergeLiquidityProviderKeepingValidFallback(
        fallback: LiquidityProviderEntry?,
        primary: LiquidityProviderEntry,
    ): LiquidityProviderEntry {
        if (fallback == null) return primary
        return primary.copy(
            name = primary.name.trim().ifBlank { fallback.name.trim().ifBlank { "Liquidity Provider" } },
            network = primary.network?.takeIf { it.isNotBlank() } ?: fallback.network,
            nodeId = primary.nodeId.trim().ifBlank { fallback.nodeId.trim() },
            address = primary.address.trim().ifBlank { fallback.address.trim() },
            token = primary.token?.takeIf { it.isNotBlank() } ?: fallback.token,
            priority = if (primary.priority != 0) primary.priority else fallback.priority,
            federationIds = (fallback.federationIds + primary.federationIds)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            sourceFederationId = primary.sourceFederationId?.takeIf { it.isNotBlank() } ?: fallback.sourceFederationId,
        )
    }

    private fun normalizeEntry(entry: FederationEntry): FederationEntry {
        val normalizedInvite = normalizeInviteCode(entry.invite)
        val normalizedKind = inferKind(entry, normalizedInvite)
        val normalizedGateways = entry.vettedGateways
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
        return entry.copy(
            kind = normalizedKind,
            invite = normalizedInvite,
            vettedGateways = normalizedGateways,
            recurringdApi = normalizeRecurringdApi(entry.recurringdApi),
        )
    }

    private fun inferKind(entry: FederationEntry, normalizedInvite: String): String {
        val kind = entry.kind.trim().lowercase()
        val fedimintLikeMetadata = hasFedimintLikeMetadata(entry)
        // Any federation-like invite should be treated as Fedimint, even if older cached entries
        // were missing/incorrectly defaulted kind metadata.
        if (normalizedInvite.startsWith("fed1", ignoreCase = true)) return "fedimint"
        if (kind == "fedimint") return "fedimint"
        if (fedimintLikeMetadata) return "fedimint"
        return when (kind) {
            "ldk" -> "ldk"
            else -> if (kind.isBlank()) "ldk" else kind
        }
    }

    private fun hasFedimintLikeMetadata(entry: FederationEntry): Boolean {
        val website = entry.website.orEmpty().trim().lowercase()
        val id = entry.id.trim().lowercase()
        val name = entry.name.trim().lowercase()

        val websiteLooksFedimint = website.contains("fedimint") || website.contains("fedibtc.com") || website.contains("fedi")
        val idLooksFedimint = id.startsWith("fedimint-")
        val nameLooksFedimint = name.contains("fedimint")
        val hasRecurringdApi = !normalizeRecurringdApi(entry.recurringdApi).isNullOrBlank()

        return websiteLooksFedimint || idLooksFedimint || nameLooksFedimint || hasRecurringdApi
    }

    private fun mergeEntryKeepingValidFallback(
        fallback: FederationEntry?,
        primary: FederationEntry,
    ): FederationEntry {
        if (fallback == null) return primary
        return primary.copy(
            kind = primary.kind.trim().ifBlank { fallback.kind.trim().ifBlank { "ldk" } },
            invite = normalizeInviteCode(primary.invite).ifBlank { normalizeInviteCode(fallback.invite) },
            network = primary.network?.takeIf { it.isNotBlank() } ?: fallback.network,
            website = primary.website?.takeIf { it.isNotBlank() } ?: fallback.website,
            description = primary.description?.takeIf { it.isNotBlank() } ?: fallback.description,
            esploraUrl = primary.esploraUrl?.takeIf { it.isNotBlank() } ?: fallback.esploraUrl,
            rgsUrl = primary.rgsUrl?.takeIf { it.isNotBlank() } ?: fallback.rgsUrl,
            lsps1NodeId = primary.lsps1NodeId?.takeIf { it.isNotBlank() } ?: fallback.lsps1NodeId,
            lsps1Address = primary.lsps1Address?.takeIf { it.isNotBlank() } ?: fallback.lsps1Address,
            lsps1Token = primary.lsps1Token?.takeIf { it.isNotBlank() } ?: fallback.lsps1Token,
            onchainDepositsDisabled = primary.onchainDepositsDisabled ?: fallback.onchainDepositsDisabled,
            recurringdApi = normalizeRecurringdApi(primary.recurringdApi)
                ?: normalizeRecurringdApi(fallback.recurringdApi),
            vettedGateways = (fallback.vettedGateways + primary.vettedGateways)
                .map { it.trim() }
                .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
                .distinct(),
        )
    }

    private fun normalizeRecurringdApi(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (trimmed.isBlank()) {
            return null
        }

        val withScheme = if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        val normalizedPath = url.encodedPath.trimEnd('/')
        val normalized = url.newBuilder()
            .encodedPath(if (normalizedPath.isBlank()) "/" else normalizedPath)
            .build()
            .toString()
        return normalized.removeSuffix("/")
    }

    private fun canonicalFederationKey(entry: FederationEntry): String {
        val kind = entry.kind.trim().lowercase()
        if (kind == "fedimint") {
            val invite = normalizeInviteCode(entry.invite).lowercase()
            if (invite.isNotBlank()) {
                return "fedimint|invite|$invite"
            }

            val normalizedName = entry.name.trim().lowercase()
            val normalizedNetwork = entry.network.orEmpty().trim().lowercase()
            if (normalizedName.isNotBlank()) {
                return "fedimint|name|$normalizedName|$normalizedNetwork"
            }
        }

        val id = entry.id.trim().lowercase()
        if (id.isNotBlank()) {
            return "$kind|id|$id"
        }

        return "$kind|name|${entry.name.trim().lowercase()}|${entry.network.orEmpty().trim().lowercase()}"
    }

    private fun displayFederationKey(entry: FederationEntry): String {
        val kind = entry.kind.trim().lowercase()
        val network = entry.network.orEmpty().trim().lowercase()
        val name = entry.name.trim().lowercase()
        if (kind == "fedimint" && name.isNotBlank()) {
            return "fedimint|name|$name|$network"
        }
        val id = entry.id.trim().lowercase()
        return if (id.isNotBlank()) "$kind|id|$id" else "$kind|name|$name|$network"
    }

    private fun normalizeInviteCode(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return ""

        // If this is a URL payload, prefer explicit invite code query parameters.
        Regex("(?i)(?:^|[?&])(invite|invite_code)=([^&#]+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { encoded ->
                val decoded = decodeUrlComponent(encoded).trim()
                if (decoded.isNotBlank()) {
                    text = decoded
                }
            }

        if (!text.startsWith("fed1", ignoreCase = true)) {
            // Some sources wrap invites in protocol prefixes.
            val wrapped = text.substringAfter("fedimint://", missingDelimiterValue = text)
                .substringAfter("fedi://", missingDelimiterValue = text)
            if (wrapped.startsWith("fed1", ignoreCase = true)) {
                text = wrapped
            }
        }

        // Keep only the invite token when text contains trailing labels/notes.
        val token = Regex("(?i)fed1[0-9a-z]+").find(text)?.value
        return token?.trim().orEmpty().ifBlank { text.trim() }
    }

    private fun decodeUrlComponent(value: String): String {
        return try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    private fun parseBooleanish(raw: String?): Boolean? {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return null
        return when (text) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
    }

    private fun parseGatewayNodeIds(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return emptyList()

        val fromJson = runCatching {
            val parsed = json.parseToJsonElement(text)
            if (parsed is JsonArray) {
                parsed.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val fallback = if (fromJson.isNotEmpty()) {
            fromJson
        } else {
            text.removePrefix("[").removeSuffix("]")
                .split(',')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
        }

        return fallback
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
    }

    private fun isAllowedDirectoryUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps &&
            parsed.host.equals(TRUSTED_DIRECTORY_HOST, ignoreCase = true) &&
            parsed.encodedPath == "/meta.json"
    }

    private fun parseUpdatedAtMs(updatedAt: String?): Long? {
        val text = updatedAt?.trim().orEmpty()
        if (text.isBlank()) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    private fun persistDirectory(context: Context, directory: FederationDirectory, syncedAtMs: Long) {
        val cfg = context.config
        val encoded = json.encodeToString(FederationDirectory.serializer(), directory)
        val hash = sha256Hex(encoded)
        val updatedAtMs = parseUpdatedAtMs(directory.updatedAt) ?: syncedAtMs

        cfg.walletDirectoryJson = encoded
        cfg.walletDirectoryLastSyncMs = syncedAtMs
        cfg.walletDirectoryLastHash = hash
        cfg.walletDirectoryLastUpdatedAtMs = updatedAtMs
    }

    fun upsertFederation(context: Context, entry: FederationEntry): Boolean {
        val normalized = normalizeEntry(entry)
        if (normalized.id.trim().isBlank() || normalized.name.trim().isBlank()) {
            return false
        }

        return try {
            val now = System.currentTimeMillis()
            val fallback = mergeDirectories(loadAssetDirectory(context), builtInFallbackDirectory)
            val current = loadDirectory(context)
            val merged = mergeDirectories(
                FederationDirectory(
                    version = 2,
                    updatedAt = Instant.ofEpochMilli(now).toString(),
                    federations = current?.federations.orEmpty() + normalized,
                    liquidityProviders = current?.liquidityProviders.orEmpty(),
                ),
                fallback
            ) ?: return false
            persistDirectory(context, merged, now)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getFederations(context: Context): List<FederationEntry> {
        return loadDirectory(context)?.federations.orEmpty()
    }

    fun getSelectedFederation(context: Context): FederationEntry? {
        val cfg = context.config
        val federations = getFederations(context)
        if (federations.isEmpty()) return null

        val selectedId = cfg.walletSelectedFederationId.trim()
        if (selectedId.isBlank()) return null

        // Never silently switch to another federation/network if the selected id disappears.
        // The UI should force an explicit user choice instead.
        return federations.firstOrNull { it.id == selectedId }
    }

    fun getLiquidityProviders(context: Context, network: String? = null): List<LiquidityProviderEntry> {
        val all = collectLiquidityProviders(context, loadDirectory(context))
        if (all.isEmpty()) return emptyList()
        val targetNetwork = normalizeNetwork(network)
        val filtered = if (targetNetwork.isBlank()) {
            all
        } else {
            all.filter { providerMatchesNetwork(it, targetNetwork) }
        }
        return filtered.ifEmpty { all }.sortedWith(
            compareByDescending<LiquidityProviderEntry> { it.priority }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id.lowercase() }
        )
    }

    fun resolveLiquidityProvider(context: Context, federation: FederationEntry): LiquidityProviderEntry? {
        val cfg = context.config
        val all = getLiquidityProviders(context, network = null)
        if (all.isEmpty()) return legacyLiquidityProviderForFederation(federation)

        val manualId = cfg.walletLiquidityProviderId.trim()
        val mode = cfg.walletLiquidityProviderMode.trim().lowercase()
        if (mode == LIQUIDITY_MODE_MANUAL && manualId.isNotBlank()) {
            all.firstOrNull { it.id == manualId }?.let { manual ->
                return manual
            }
        }

        val candidates = getLiquidityProviders(context, federation.network)
        if (candidates.isEmpty()) {
            return legacyLiquidityProviderForFederation(federation)
        }
        val stats = loadProviderOutcomeStats(cfg.walletLiquidityProviderStatsJson)
        return candidates.maxByOrNull { provider ->
            scoreLiquidityProvider(
                provider = provider,
                federation = federation,
                stats = stats[provider.id],
            )
        } ?: legacyLiquidityProviderForFederation(federation)
    }

    fun recordLiquidityProviderOutcome(context: Context, providerId: String?, success: Boolean) {
        val id = providerId?.trim().orEmpty()
        if (id.isBlank()) return

        val cfg = context.config
        val stats = loadProviderOutcomeStats(cfg.walletLiquidityProviderStatsJson)
        val item = stats[id] ?: ProviderOutcomeStats()
        val now = System.currentTimeMillis()
        if (success) {
            item.successes += 1
            item.lastSuccessMs = now
        } else {
            item.failures += 1
            item.lastFailureMs = now
        }
        stats[id] = item
        cfg.walletLiquidityProviderStatsJson = encodeProviderOutcomeStats(stats)
    }

    private fun collectLiquidityProviders(context: Context, directory: FederationDirectory?): List<LiquidityProviderEntry> {
        if (directory == null) return emptyList()
        val explicit = directory.liquidityProviders.mapNotNull { normalizeLiquidityProvider(it) }
        val explicitById = explicit.associateBy { it.id }
        val derived = directory.federations.mapNotNull { entry ->
            val lspsNodeId = entry.lsps1NodeId?.trim().orEmpty()
            val lspsAddress = entry.lsps1Address?.trim().orEmpty()
            if (lspsNodeId.isBlank() || lspsAddress.isBlank()) {
                return@mapNotNull null
            }
            val derivedId = "$PROVIDER_ID_PREFIX_FEDERATION${entry.id}"
            normalizeLiquidityProvider(
                LiquidityProviderEntry(
                    id = derivedId,
                    name = "${entry.name} liquidity",
                    network = entry.network,
                    nodeId = lspsNodeId,
                    address = lspsAddress,
                    token = entry.lsps1Token,
                    priority = 10,
                    federationIds = listOf(entry.id),
                    sourceFederationId = entry.id,
                )
            )
        }
        val custom = customLiquidityProviderFromConfig(context.config)
        val merged = explicit +
            derived.filterNot { explicitById.containsKey(it.id) } +
            listOfNotNull(custom)
        return dedupeLiquidityProviders(merged)
    }

    private fun customLiquidityProviderFromConfig(cfg: org.fossify.phone.helpers.Config): LiquidityProviderEntry? {
        val nodeId = cfg.walletLiquidityCustomNodeId.trim()
        val address = cfg.walletLiquidityCustomAddress.trim()
        if (nodeId.isBlank() || address.isBlank()) return null
        return normalizeLiquidityProvider(
            LiquidityProviderEntry(
                id = CUSTOM_PROVIDER_ID,
                name = cfg.walletLiquidityCustomName.trim().ifBlank { "Custom provider" },
                network = cfg.walletLiquidityCustomNetwork.trim().ifBlank { null },
                nodeId = nodeId,
                address = address,
                token = cfg.walletLiquidityCustomToken.trim().ifBlank { null },
                priority = 100,
            )
        )
    }

    private fun legacyLiquidityProviderForFederation(federation: FederationEntry): LiquidityProviderEntry? {
        val lspsNodeId = federation.lsps1NodeId?.trim().orEmpty()
        val lspsAddress = federation.lsps1Address?.trim().orEmpty()
        if (lspsNodeId.isBlank() || lspsAddress.isBlank()) return null
        return normalizeLiquidityProvider(
            LiquidityProviderEntry(
                id = "$PROVIDER_ID_PREFIX_FEDERATION${federation.id}",
                name = "${federation.name} liquidity",
                network = federation.network,
                nodeId = lspsNodeId,
                address = lspsAddress,
                token = federation.lsps1Token,
                priority = 5,
                federationIds = listOf(federation.id),
                sourceFederationId = federation.id,
            )
        )
    }

    private fun providerMatchesNetwork(provider: LiquidityProviderEntry, targetNetwork: String?): Boolean {
        val providerNetwork = normalizeNetwork(provider.network)
        val normalizedTarget = normalizeNetwork(targetNetwork)
        if (providerNetwork.isBlank() || normalizedTarget.isBlank()) return true
        return providerNetwork == normalizedTarget
    }

    private fun normalizeNetwork(network: String?): String {
        val normalized = network?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "bitcoin", "mainnet", "btc" -> "bitcoin"
            "testnet", "test" -> "testnet"
            "signet" -> "signet"
            "regtest" -> "regtest"
            else -> normalized
        }
    }

    private fun scoreLiquidityProvider(
        provider: LiquidityProviderEntry,
        federation: FederationEntry,
        stats: ProviderOutcomeStats?,
    ): Int {
        var score = provider.priority * 10

        val targetNetwork = normalizeNetwork(federation.network)
        val providerNetwork = normalizeNetwork(provider.network)
        score += when {
            providerNetwork.isBlank() -> 40
            targetNetwork.isBlank() -> 10
            providerNetwork == targetNetwork -> 120
            else -> -300
        }

        if (provider.sourceFederationId?.equals(federation.id, ignoreCase = true) == true) {
            score += 60
        }
        if (provider.federationIds.any { it.equals(federation.id, ignoreCase = true) }) {
            score += 60
        }

        if (stats != null) {
            score += stats.successes * 8
            score -= stats.failures * 12

            val now = System.currentTimeMillis()
            if (stats.lastSuccessMs > 0L && now - stats.lastSuccessMs < 7L * 24L * 60L * 60L * 1000L) {
                score += 20
            }
            if (stats.lastFailureMs > 0L && now - stats.lastFailureMs < 60L * 60L * 1000L) {
                score -= 35
            }
        }

        return score
    }

    private fun loadProviderOutcomeStats(raw: String): MutableMap<String, ProviderOutcomeStats> {
        val out = linkedMapOf<String, ProviderOutcomeStats>()
        val text = raw.trim()
        if (text.isBlank()) return out
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return out
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()?.trim().orEmpty()
            if (key.isBlank()) continue
            val obj = root.optJSONObject(key) ?: continue
            out[key] = ProviderOutcomeStats(
                successes = obj.optInt("successes", 0).coerceAtLeast(0),
                failures = obj.optInt("failures", 0).coerceAtLeast(0),
                lastSuccessMs = obj.optLong("last_success_ms", 0L).coerceAtLeast(0L),
                lastFailureMs = obj.optLong("last_failure_ms", 0L).coerceAtLeast(0L),
            )
        }
        return out
    }

    private fun encodeProviderOutcomeStats(stats: Map<String, ProviderOutcomeStats>): String {
        val root = JSONObject()
        stats.forEach { (providerId, item) ->
            if (providerId.isBlank()) return@forEach
            root.put(providerId, JSONObject().apply {
                put("successes", item.successes.coerceAtLeast(0))
                put("failures", item.failures.coerceAtLeast(0))
                put("last_success_ms", item.lastSuccessMs.coerceAtLeast(0L))
                put("last_failure_ms", item.lastFailureMs.coerceAtLeast(0L))
            })
        }
        return root.toString()
    }

    fun isFedimintFederation(entry: FederationEntry?): Boolean {
        if (entry == null) return false
        if (entry.kind.trim().equals("fedimint", ignoreCase = true)) return true
        if (normalizeInviteCode(entry.invite).startsWith("fed1", ignoreCase = true)) return true
        return hasFedimintLikeMetadata(entry)
    }

    fun shouldTryFedimintFallback(entry: FederationEntry?): Boolean {
        if (entry == null) return false
        if (isFedimintFederation(entry)) return false

        val id = entry.id.trim().lowercase()
        val isKnownLdkBuiltIn = id == "btc-mainnet" || id == "btc-testnet"
        if (isKnownLdkBuiltIn) return false

        val hasExplicitLdkConfig = !entry.esploraUrl.isNullOrBlank() || !entry.rgsUrl.isNullOrBlank()
        return !hasExplicitLdkConfig
    }

    fun refreshIfStale(
        context: Context,
        force: Boolean = false,
        callback: ((success: Boolean) -> Unit)? = null,
    ) {
        val cfg = context.config
        val now = System.currentTimeMillis()
        val isStale = now - cfg.walletDirectoryLastSyncMs > STALE_AFTER_MS
        if (!force && !isStale && cfg.walletDirectoryJson.isNotBlank()) {
            callback?.invoke(true)
            return
        }

        ensureBackgroundThread {
            val success = refreshBlocking(context)
            callback?.invoke(success)
        }
    }

    fun refreshBlocking(context: Context): Boolean {
        val cfg = context.config
        val now = System.currentTimeMillis()
        return try {
            var loadedRemote: FederationDirectory? = null
            for (url in DIRECTORY_URLS) {
                if (!isAllowedDirectoryUrl(url)) {
                    continue
                }
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!isAllowedDirectoryUrl(resp.request.url.toString())) return@use
                    if (!resp.isSuccessful) return@use
                    val bodyBytes = resp.body?.bytes() ?: return@use
                    if (bodyBytes.isEmpty() || bodyBytes.size > MAX_DIRECTORY_BYTES) return@use
                    val body = bodyBytes.toString(Charsets.UTF_8).trim()
                    if (body.isBlank()) return@use
                    val decoded = decodeDirectory(body) ?: return@use
                    loadedRemote = decoded
                    return@use
                }
                if (loadedRemote != null) {
                    break
                }
            }

            val remote = loadedRemote ?: return false
            // Parse remote then merge with bundled defaults so built-in federations
            // remain available even if a remote directory is temporarily incomplete.
            val merged = mergeDirectories(
                remote,
                mergeDirectories(loadAssetDirectory(context), builtInFallbackDirectory)
            ) ?: return false

            val encoded = json.encodeToString(FederationDirectory.serializer(), merged)
            val newHash = sha256Hex(encoded)
            val previousHash = cfg.walletDirectoryLastHash
            val previousUpdatedAtMs = cfg.walletDirectoryLastUpdatedAtMs
            var newUpdatedAtMs = parseUpdatedAtMs(merged.updatedAt) ?: now

            if (previousHash.isNotBlank() && previousUpdatedAtMs > 0L && newHash != previousHash) {
                val looksLikeRollback = newUpdatedAtMs + ROLLBACK_TOLERANCE_MS < previousUpdatedAtMs
                if (looksLikeRollback) {
                    return false
                }
            }

            if (newHash == previousHash && previousUpdatedAtMs > 0L) {
                newUpdatedAtMs = maxOf(newUpdatedAtMs, previousUpdatedAtMs)
            }

            cfg.walletDirectoryJson = encoded
            cfg.walletDirectoryLastSyncMs = now
            cfg.walletDirectoryLastHash = newHash
            cfg.walletDirectoryLastUpdatedAtMs = newUpdatedAtMs
            true
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun loadDirectory(context: Context): FederationDirectory? {
        val cfg = context.config
        val fallback = mergeDirectories(loadAssetDirectory(context), builtInFallbackDirectory)

        // 1) Cached JSON (from network or asset bootstrap).
        val cached = cfg.walletDirectoryJson.trim()
        if (cached.isNotBlank()) {
            return try {
                val parsedCached = decodeDirectory(cached)
                val dir = mergeDirectories(parsedCached, fallback) ?: return null
                val normalizedEncoded = json.encodeToString(FederationDirectory.serializer(), dir)
                if (normalizedEncoded != cached) {
                    // One-time migration path: persist normalized entries so stale cached metadata
                    // (e.g. missing/incorrect kind for invite-based federations) is repaired.
                    cfg.walletDirectoryJson = normalizedEncoded
                }

                // Migration guard: refresh soon if cache only has local LDK entries
                // and no usable Fedimint production federation.
                val fedimintWithInviteCount = dir.federations.count { entry ->
                    entry.kind.trim().equals("fedimint", ignoreCase = true) &&
                        entry.invite.trim().isNotBlank()
                }
                val hasUsableFedimint = dir.federations.any { entry ->
                    entry.kind.trim().equals("fedimint", ignoreCase = true) &&
                        entry.invite.trim().isNotBlank() &&
                        !entry.name.contains("testnet", ignoreCase = true) &&
                        !entry.network.orEmpty().contains("test", ignoreCase = true)
                }
                var forceStaleForMigration = false
                if (!hasUsableFedimint || fedimintWithInviteCount < 3) {
                    cfg.walletDirectoryLastSyncMs = 0L
                    forceStaleForMigration = true
                }

                if (forceStaleForMigration) {
                    cfg.walletDirectoryLastSyncMs = 0L
                }

                if (cfg.walletDirectoryLastHash.isBlank()) {
                    cfg.walletDirectoryLastHash = sha256Hex(normalizedEncoded)
                }
                if (cfg.walletDirectoryLastUpdatedAtMs <= 0L) {
                    cfg.walletDirectoryLastUpdatedAtMs = parseUpdatedAtMs(dir.updatedAt) ?: 0L
                }

                dir
            } catch (_: Exception) {
                mergeDirectories(null, fallback)
            }
        }

        // 2) Asset fallback.
        return try {
            val dir = mergeDirectories(fallback, null) ?: return null
            persistDirectory(context, dir, syncedAtMs = 0L)
            // Keep this stale so the app pulls the live federation directory on first run.
            // Asset data is only a baseline fallback.
            cfg.walletDirectoryLastSyncMs = 0L
            dir
        } catch (_: Exception) {
            null
        }
    }
}
