package buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import java.io.File
import java.io.Serializable

class StripUrlsInCompiledResourcesAction(
    private val mergedResBaseDir: File
) : Action<Task>, Serializable {

    private val blockedUrls = listOf(
        "https://play.google.com/store/apps/details?id=org.fossify.thankyou",
        "https://github.com/FossifyOrg/General-Discussion#how-can-i-suggest-an-edit-to-a-file",
        "https://github.com/FossifyOrg/general-Discussion#how-can-i-suggest-an-edit-to-a-file"
    )

    override fun execute(task: Task) {
        val variant = task.name
            .removePrefix("merge")
            .removeSuffix("Resources")
            .replaceFirstChar { it.lowercaseChar() }
        val variantCapitalized = variant.replaceFirstChar { it.uppercaseChar() }
        val mergedDir = File(mergedResBaseDir, "$variant/merge${variantCapitalized}Resources")
        if (!mergedDir.exists()) return

        mergedDir.walkTopDown()
            .filter { it.isFile && it.extension == "flat" && it.name.endsWith(".arsc.flat") }
            .forEach(::stripUrlsInFlatFile)
    }

    private fun stripUrlsInFlatFile(file: File) {
        val original = file.readBytes()
        var text = original.toString(Charsets.ISO_8859_1)
        var changed = false

        blockedUrls.forEach { url ->
            if (text.contains(url)) {
                changed = true
                text = text.replace(url, "x".repeat(url.length))
            }
        }

        if (changed) {
            file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        }
    }
}
