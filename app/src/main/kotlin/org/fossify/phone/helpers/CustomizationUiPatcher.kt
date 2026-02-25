package org.fossify.phone.helpers

import android.app.Activity
import android.view.View
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.applyFontToViewRecursively
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.models.RadioItem
import java.io.File

object CustomizationUiPatcher {
    private const val CUSTOMIZATION_ACTIVITY_CLASS = "org.fossify.commons.activities.CustomizationActivity"
    private const val FONT_TYPE_SYSTEM_DEFAULT = 0
    private const val FONT_TYPE_MONOSPACE = 1
    private const val FONT_TYPE_CUSTOM_FILE = 2
    private const val PICKER_SELECT_FILE_ID = 2
    private const val SYSTEM_FONT_ID_BASE = 100
    private const val MAX_SYSTEM_FONT_CHOICES = 8

    private data class SystemFontChoice(
        val id: Int,
        val label: String,
        val sourcePath: String,
        val fileName: String,
    )

    fun patch(activity: Activity) {
        if (activity.javaClass.name != CUSTOMIZATION_ACTIVITY_CLASS) return

        // This warning is tied to icon-color customization, which we intentionally hide.
        runCatching { activity.baseConfig.wasAppIconCustomizationWarningShown = true }

        hideAppIconColorOption(activity)
        patchFontPicker(activity)
    }

    private fun hideAppIconColorOption(activity: Activity) {
        activity.findViewById<View>(org.fossify.commons.R.id.customization_app_icon_color_holder)?.visibility = View.GONE
    }

    private fun patchFontPicker(activity: Activity) {
        val fontHolder = activity.findViewById<View>(org.fossify.commons.R.id.customization_font_holder) ?: return
        fontHolder.setOnClickListener { showExtendedFontPicker(activity) }
    }

    private fun showExtendedFontPicker(activity: Activity) {
        val systemFonts = detectSystemFonts().take(MAX_SYSTEM_FONT_CHOICES)
        val items = ArrayList<RadioItem>(3 + systemFonts.size)
        items.add(RadioItem(FONT_TYPE_SYSTEM_DEFAULT, activity.getString(org.fossify.commons.R.string.system_default)))
        items.add(RadioItem(FONT_TYPE_MONOSPACE, activity.getString(org.fossify.commons.R.string.font_monospace)))
        items.add(RadioItem(PICKER_SELECT_FILE_ID, activity.getString(org.fossify.commons.R.string.select_font_file)))
        items.addAll(systemFonts.map { RadioItem(it.id, it.label) })

        val checkedId = resolveCheckedFontId(activity, systemFonts)
        RadioGroupDialog(activity, items, checkedId) { selected ->
            when (val id = selected as Int) {
                FONT_TYPE_SYSTEM_DEFAULT -> setCustomizationFont(activity, FONT_TYPE_SYSTEM_DEFAULT, "")
                FONT_TYPE_MONOSPACE -> setCustomizationFont(activity, FONT_TYPE_MONOSPACE, "")
                PICKER_SELECT_FILE_ID -> invokePrivateNoArg(activity, "openFontFilePicker")
                else -> {
                    val match = systemFonts.firstOrNull { it.id == id } ?: return@RadioGroupDialog
                    applySystemFont(activity, match)
                }
            }
        }
    }

    private fun resolveCheckedFontId(activity: Activity, systemFonts: List<SystemFontChoice>): Int {
        val currentType = getPrivateIntField(activity, "curFontType") ?: activity.baseConfig.fontType
        val currentName = getPrivateStringField(activity, "curFontFileName") ?: activity.baseConfig.fontName

        return when (currentType) {
            FONT_TYPE_SYSTEM_DEFAULT -> FONT_TYPE_SYSTEM_DEFAULT
            FONT_TYPE_MONOSPACE -> FONT_TYPE_MONOSPACE
            FONT_TYPE_CUSTOM_FILE -> systemFonts.firstOrNull { it.fileName.equals(currentName, ignoreCase = true) }?.id
                ?: PICKER_SELECT_FILE_ID

            else -> FONT_TYPE_SYSTEM_DEFAULT
        }
    }

    private fun applySystemFont(activity: Activity, option: SystemFontChoice) {
        val source = File(option.sourcePath)
        if (!source.isFile || !source.canRead()) {
            activity.toast(org.fossify.commons.R.string.invalid_font_file)
            return
        }

        val saved = runCatching {
            val bytes = source.readBytes()
            bytes.isNotEmpty() && FontHelper.saveFontData(activity, bytes, option.fileName)
        }.getOrElse { false }

        if (!saved) {
            activity.toast(org.fossify.commons.R.string.invalid_font_file)
            return
        }

        setCustomizationFont(activity, FONT_TYPE_CUSTOM_FILE, option.fileName)
    }

    private fun setCustomizationFont(activity: Activity, fontType: Int, fontFileName: String) {
        val reflected = setPrivateField(activity, "curFontType", fontType) &&
            setPrivateField(activity, "curFontFileName", fontFileName) &&
            invokePrivateNoArg(activity, "fontChanged")

        if (reflected) return

        // Fallback path in case private members ever change upstream.
        activity.baseConfig.fontType = fontType
        activity.baseConfig.fontName = fontFileName
        FontHelper.clearCache()
        val typeface = FontHelper.getTypeface(activity, fontType, fontFileName)
        activity.applyFontToViewRecursively(activity.window.decorView, typeface, true)
    }

    private fun detectSystemFonts(): List<SystemFontChoice> {
        val dirs = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts")
            .map(::File)
            .filter { it.exists() && it.isDirectory && it.canRead() }
        if (dirs.isEmpty()) return emptyList()

        val allFonts = dirs
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.canRead() }
            .filter { file ->
                val lower = file.name.lowercase()
                lower.endsWith(".ttf") || lower.endsWith(".otf")
            }
            .sortedBy { it.name.lowercase() }

        if (allFonts.isEmpty()) return emptyList()

        val preferredNames = listOf(
            "roboto-regular.ttf",
            "roboto condensed regular.ttf",
            "noto sans regular.ttf",
            "noto serif regular.ttf",
            "droidsans.ttf",
            "samsungone.ttf",
            "inter-regular.ttf",
            "google sans text regular.ttf",
        )

        val byNormalizedName = allFonts.associateBy { normalizeFontName(it.name) }
        val selected = LinkedHashMap<String, File>()

        preferredNames.forEach { preferred ->
            byNormalizedName[normalizeFontName(preferred)]?.let { selected[it.name.lowercase()] = it }
        }

        // Fill remaining slots with additional readable regular-style system fonts.
        allFonts.forEach { file ->
            if (selected.size >= MAX_SYSTEM_FONT_CHOICES) return@forEach
            val lower = file.name.lowercase()
            val isDisplayCandidate = "regular" in lower || "sans" in lower || "serif" in lower || "mono" in lower
            if (isDisplayCandidate) {
                selected.putIfAbsent(lower, file)
            }
        }

        if (selected.isEmpty()) {
            allFonts.take(MAX_SYSTEM_FONT_CHOICES).forEach { selected[it.name.lowercase()] = it }
        }

        return selected.values
            .take(MAX_SYSTEM_FONT_CHOICES)
            .mapIndexed { index, file ->
                SystemFontChoice(
                    id = SYSTEM_FONT_ID_BASE + index,
                    label = prettifyFontName(file.name),
                    sourcePath = file.absolutePath,
                    fileName = file.name,
                )
            }
    }

    private fun normalizeFontName(name: String): String {
        return name.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
    }

    private fun prettifyFontName(fileName: String): String {
        return fileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\bRegular\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { fileName }
    }

    private fun getPrivateIntField(target: Any, fieldName: String): Int? {
        return runCatching {
            target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(target)
        }.getOrNull()
    }

    private fun getPrivateStringField(target: Any, fieldName: String): String? {
        return runCatching {
            target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(target) as? String
        }.getOrNull()
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?): Boolean {
        return runCatching {
            target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.set(target, value)
            true
        }.getOrElse { false }
    }

    private fun invokePrivateNoArg(target: Any, methodName: String): Boolean {
        return runCatching {
            target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(target)
            true
        }.getOrElse { false }
    }
}
