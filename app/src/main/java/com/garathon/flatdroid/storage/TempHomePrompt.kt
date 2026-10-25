package com.garathon.flatdroid.storage

import android.content.Context
import com.garathon.flatdroid.session.SessionController

/**
 * What happens to the temporary home after its app closes: an empty one is deleted; otherwise
 * Flatdroid asks in its window ([needsAsking]), the next time it is open, whether to export it (a
 * zip in Download) or delete it. Only an answer deletes it: the question can't be dismissed, and
 * there is no notification to swipe away by accident. A launch in Temporary Home mode asks first,
 * since it replaces the home.
 */
object TempHomePrompt {
    /** Answered this time Flatdroid runs; the export or deletion may still be under way. */
    @Volatile private var answered: String? = null

    /** After [appId]'s session: deletes the home if the app left nothing in it. */
    fun sessionEnded(context: Context, appId: String) {
        if (answered == appId) answered = null
        if (TempHomes.owner(context) == appId && !TempHomes.hasContent(TempHomes.home(context))) TempHomes.delete(context)
    }

    /** The user answered about [appId]'s home: it isn't asked about again while it is exported or deleted. */
    fun answer(appId: String) {
        answered = appId
    }

    /** Ask again (its export failed). */
    fun askAgain(appId: String) {
        if (answered == appId) answered = null
    }

    /**
     * The app whose temporary home is left with something in it and no answer yet, or null; an
     * empty or ownerless one is deleted. Not while its app runs or it is being exported.
     */
    fun needsAsking(context: Context): String? {
        if (TempHomes.orphaned(context)) TempHomes.delete(context)
        val appId = TempHomes.owner(context) ?: return null
        if (appId == answered || appId == SessionController.ui.value.state.appIdOrNull || StorageTasks.exportingTempHome(StorageTaskService.pendingTasks(context))) return null
        if (!TempHomes.hasContent(TempHomes.home(context))) {
            TempHomes.delete(context)
            return null
        }
        return appId
    }
}
