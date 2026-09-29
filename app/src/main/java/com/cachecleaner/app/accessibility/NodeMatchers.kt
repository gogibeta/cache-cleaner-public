package com.cachecleaner.app.accessibility

import java.util.Locale

/**
 * Pure, UI-tree-independent matching rules for the automation.
 * Kept separate from [CacheClearEngine] so they can be unit-tested on the JVM.
 */
object NodeMatchers {

    private val CONFIRM_TEXTS = setOf("ok", "yes", "confirm", "确定", "aceptar")

    /** True if the view id belongs to a dialog "OK" (positive) button. */
    fun isConfirmId(viewId: String?): Boolean {
        if (viewId.isNullOrEmpty()) return false
        val suffix = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
        // android:id/button1 is the AlertDialog positive button.
        return suffix == "button1"
    }

    /** True if the node's text looks like a dialog confirmation label. */
    fun isConfirmText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.trim().lowercase(Locale.ROOT) in CONFIRM_TEXTS
    }
}
