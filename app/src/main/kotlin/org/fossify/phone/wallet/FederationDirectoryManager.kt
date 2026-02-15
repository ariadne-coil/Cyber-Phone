package org.fossify.phone.wallet

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
data class FederationDirectory(
    val version: Int = 1,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val federations: List<FederationEntry> = emptyList(),
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

                FederationEntry(
                    id = key.trim().ifBlank { name.lowercase().replace(" ", "-") },
                    name = name,
                    kind = "fedimint",
                    invite = invite,
                    network = if (name.contains("testnet", ignoreCase = true) || name.contains("signet", ignoreCase = true)) "testnet" else "bitcoin",
                    website = website,
                    description = description.ifBlank { "Public Fedimint federation." },
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

        val mergedUpdatedAt = when {
            !primary?.updatedAt.isNullOrBlank() -> primary?.updatedAt
            else -> fallback?.updatedAt
        }

        return FederationDirectory(
            version = maxOf(primary?.version ?: 1, fallback?.version ?: 1),
            updatedAt = mergedUpdatedAt,
            federations = dedupeFederations(merged.values.toList()),
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

    private fun normalizeEntry(entry: FederationEntry): FederationEntry {
        val normalizedKind = entry.kind.trim().ifBlank { "ldk" }
        return entry.copy(
            kind = normalizedKind,
            invite = normalizeInviteCode(entry.invite),
        )
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
        )
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
                    val encoded = json.encodeToString(FederationDirectory.serializer(), dir)
                    cfg.walletDirectoryLastHash = sha256Hex(encoded)
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
