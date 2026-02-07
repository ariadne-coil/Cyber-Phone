package org.fossify.messages.views

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import org.fossify.commons.views.MyEditText

/**
 * Enables keyboards (Gboard, etc.) to insert rich content (GIFs, images, videos) directly into the
 * message composer via commitContent(), instead of falling back to a share-sheet / app chooser.
 */
class RichContentEditText : MyEditText {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    /**
     * Called when the IME commits content (typically a GIF/image/video) into the editor.
     */
    var onCommitContent: ((uri: Uri, mimeType: String?) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null

        // Tell the IME we can accept rich content.
        EditorInfoCompat.setContentMimeTypes(
            outAttrs,
            arrayOf("image/*", "image/gif", "video/*", "application/octet-stream")
        )

        val listener = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            try {
                val needsPermission = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                if (needsPermission) {
                    try {
                        inputContentInfo.requestPermission()
                    } catch (_: Exception) {
                    }
                }

                val uri = inputContentInfo.contentUri
                val mime = try {
                    inputContentInfo.description?.getMimeType(0)
                } catch (_: Exception) {
                    null
                }
                onCommitContent?.invoke(uri, mime)
                true
            } catch (_: Exception) {
                false
            }
        }

        return InputConnectionCompat.createWrapper(ic, outAttrs, listener)
    }
}
