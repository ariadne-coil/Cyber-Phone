package org.fossify.messages.helpers

import android.content.Context
import org.tensorflow.lite.task.text.nlclassifier.NLClassifier
import java.io.File

object AiSpamClassifier {
    private const val SPAM_LABEL = "spam"
    private const val MIN_SPAM_SCORE = 0.6f
    private var classifier: NLClassifier? = null
    private var modelPath: String? = null

    fun isSpam(context: Context, text: String): Boolean? {
        val modelFile = AiSpamModelManager.getModelFile(context) ?: run {
            AiSpamModelManager.ensureModelAvailable(context)
            return null
        }
        if (text.isBlank()) {
            return null
        }
        val currentPath = modelFile.absolutePath
        val active = synchronized(this) {
            if (classifier == null || modelPath != currentPath) {
                classifier?.close()
                classifier = runCatching { NLClassifier.createFromFile(File(currentPath)) }.getOrNull()
                modelPath = currentPath
            }
            classifier
        } ?: return null

        return try {
            val results = active.classify(text)
            val spamScore = results.firstOrNull { it.label.equals(SPAM_LABEL, true) }?.score
            if (spamScore != null) {
                spamScore >= MIN_SPAM_SCORE
            } else {
                results.maxByOrNull { it.score }?.let { top ->
                    top.label.contains(SPAM_LABEL, true) && top.score >= MIN_SPAM_SCORE
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
