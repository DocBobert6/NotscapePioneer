package net.nspioneer.browser.engine

import android.app.Activity
import android.view.MenuItem
import org.mozilla.geckoview.BasicSelectionActionDelegate

/**
 * Selection translation (alpha.67, D-6a): the floating
 * text-selection toolbar, with one added action.
 *
 * GeckoView ships the standard toolbar as
 * BasicSelectionActionDelegate (Copy, Select all, Share
 * and friends) — Pioneer sets no selection delegate
 * today, so a long-press selection shows handles but no
 * action bar at all. Subclassing the standard delegate
 * adds a Translate item alongside the standard actions
 * instead of reimplementing the toolbar, and as a
 * consequence selection now shows the standard bar too
 * (copying text from pages works). The four overrides
 * are the extension points the base class offers for
 * exactly this: getAllActions lists the item,
 * isActionAvailable gates it on non-blank selected text
 * (the base class asks the selection whether an unknown
 * action is available, and it never is),
 * prepareAction titles it, and performAction handles it
 * before the base class can pass it to
 * Selection.execute.
 */
class SelectionTranslateDelegate(
    activity: Activity,
    private val onTranslate: (String) -> Unit,
) : BasicSelectionActionDelegate(activity, true) {

    companion object {
        const val ACTION_TRANSLATE_SELECTION =
            "net.nspioneer.translate_selection"
    }

    override fun getAllActions(): Array<String> =
        super.getAllActions() + ACTION_TRANSLATE_SELECTION

    override fun isActionAvailable(action: String): Boolean {
        if (action == ACTION_TRANSLATE_SELECTION) {
            return selection?.text?.isNotBlank() == true
        }
        return super.isActionAvailable(action)
    }

    override fun prepareAction(action: String, item: MenuItem) {
        if (action == ACTION_TRANSLATE_SELECTION) {
            item.title = "Translate"
            return
        }
        super.prepareAction(action, item)
    }

    override fun performAction(action: String, item: MenuItem): Boolean {
        if (action == ACTION_TRANSLATE_SELECTION) {
            val text = selection?.text?.takeIf { it.isNotBlank() }
                ?: return false
            onTranslate(text)
            // Match Copy's behaviour on the floating bar:
            // the selection clears once the action is taken.
            // The result card shows the original text, so
            // nothing is lost.
            clearSelection()
            return true
        }
        return super.performAction(action, item)
    }
}
