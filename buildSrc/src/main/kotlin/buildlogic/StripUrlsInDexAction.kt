package buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import java.io.File
import java.io.Serializable

class StripUrlsInDexAction(
    private val dexDir: File
) : Action<Task>, Serializable {

    // Keep operational URLs intact; strip ecosystem/store/community links from release dex.
    private val allowedPrefixes = listOf(
        "https://appassets.androidplatform.net/assets/fedimint/",
        "https://blockstream.info",
        "https://btcscan.org",
        "https://mempool.",
        "https://meta.dev.fedibtc.com/",
        "https://rapidsync.lightningdevkit.org/",
        "https://sdk.fedimint.org/",
        "https://storage.googleapis.com/mediapipe-models/",
        "https://gitlab.com/xynngh/YetAnotherCallBlocker_data/",
        "https://aapi.shouldianswer.net/",
        "https://www.shouldianswer.net",
        "https://cyberphone.local/",
        "http://127.0.0.1:3002/"
    )

    private val blockedFragments = listOf(
        "play.google.com/store/apps/",
        "play.google.com/store/apps/dev",
        "fossify.org/upgrade_to_pro",
        "www.fossify.org/policy/",
        "github.com/FossifyOrg",
        "github.com/sponsors/FossifyOrg",
        "github.com/google/gson/blob/main/Troubleshooting.md",
        "opencollective.com/fossify/",
        "www.patreon.com/naveen3singh",
        "paypal.me/naveen3singh",
        "liberapay.com/naveensingh",
        "t.me/Fossify",
        "www.reddit.com/r/Fossify",
        "developer.android.com/training/articles/direct-boot",
        "goo.gle/compose-feedback",
        "issuetracker.google.com/issues/new?component=413107&template=1096568",
        "youtrack.jetbrains.com/issue/KT-55980",
        "www.fedi.xyz/"
    )

    private val urlRegex = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    override fun execute(task: Task) {
        if (!dexDir.exists()) return

        dexDir.listFiles()
            ?.filter { it.isFile && it.extension == "dex" }
            ?.forEach(::stripUrlsInDex)
    }

    private fun stripUrlsInDex(file: File) {
        val originalBytes = file.readBytes()
        val text = originalBytes.toString(Charsets.ISO_8859_1)
        val mutable = text.toCharArray()
        var changed = false

        for (match in urlRegex.findAll(text)) {
            val url = match.value
            if (shouldStrip(url)) {
                changed = true
                val replacement = "x".repeat(url.length)
                replacement.forEachIndexed { index, ch ->
                    mutable[match.range.first + index] = ch
                }
            }
        }

        if (changed) {
            file.writeBytes(String(mutable).toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun shouldStrip(url: String): Boolean {
        if (allowedPrefixes.any { url.startsWith(it) }) return false
        return blockedFragments.any { url.contains(it) }
    }
}
