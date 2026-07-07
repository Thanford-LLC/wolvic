/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import com.igalia.wolvic.R
import com.igalia.wolvic.VRBrowserApplication
import com.igalia.wolvic.utils.SystemUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.log.logger.Logger
import java.util.concurrent.CompletableFuture

const val DESKTOP_ROOT = "fake_desktop_root"

class BookmarksStore constructor(val context: Context) {

    private val LOGTAG = SystemUtils.createLogtag(BookmarksStore::class.java)

    companion object {
        private val coreRoots = listOf(
                DESKTOP_ROOT,
                BookmarkRoot.Mobile.id,
                BookmarkRoot.Unfiled.id,
                BookmarkRoot.Toolbar.id,
                BookmarkRoot.Menu.id
        )

        const val COMBO_BOOKMARKS_TITLE = "Combo Bookmarks"

        @JvmStatic
        fun allowDeletion(guid: String): Boolean {
            return coreRoots.contains(guid)
        }

        /**
         * User-friendly titles for various internal bookmark folders.
         */
        fun rootTitles(context: Context): Map<String, String> {
            return mapOf(
                // "Virtual" desktop folder.
                DESKTOP_ROOT to context.getString(R.string.bookmarks_desktop_folder_title),
                // Our main root, in actuality the "mobile" root:
                BookmarkRoot.Mobile.id to context.getString(R.string.bookmarks_mobile_folder_title),
                // What we consider the "desktop" roots:
                BookmarkRoot.Menu.id to context.getString(R.string.bookmarks_desktop_menu_title),
                BookmarkRoot.Toolbar.id to context.getString(R.string.bookmarks_desktop_toolbar_title),
                BookmarkRoot.Unfiled.id to context.getString(R.string.bookmarks_desktop_unfiled_title)
            )
        }
    }

    private val listeners = ArrayList<BookmarkListener>()
    private var storage = (context.applicationContext as VRBrowserApplication).places.bookmarks
    private var titles = rootTitles(context)
    private val accountManager = (context.applicationContext as VRBrowserApplication).services.accountManager

    // Bookmarks might have changed during sync, so notify our listeners.
    private val syncStatusObserver = object : SyncStatusObserver {
        override fun onStarted() {}

        override fun onIdle() {
            Logger(LOGTAG).debug("Detected that sync is finished, notifying listeners")
            notifyListeners()
        }

        override fun onError(error: Exception?) {}
    }

    init {
        accountManager.registerForSyncEvents(
            syncStatusObserver, ProcessLifecycleOwner.get(), false
        )
    }

    // Update the folder strings after a language update
    fun onConfigurationChanged() {
        titles = rootTitles(context)
    }

    interface BookmarkListener {
        fun onBookmarksUpdated()
        fun onBookmarkAdded()
    }

    fun addListener(aListener: BookmarkListener) {
        if (!listeners.contains(aListener)) {
            listeners.add(aListener)
        }
    }

    fun removeListener(aListener: BookmarkListener) {
        listeners.remove(aListener)
    }

    fun removeAllListeners() {
        listeners.clear()
    }

    internal fun updateStorage() {
        storage = (context.applicationContext as VRBrowserApplication).places.bookmarks
        notifyListeners()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun getBookmarks(guid: String): CompletableFuture<List<BookmarkNode>?> = GlobalScope.future {
        when (guid) {
            BookmarkRoot.Mobile.id -> {
                // Construct a "virtual" desktop folder as the first bookmark item in the list.
                val withDesktopFolder = mutableListOf(
                    BookmarkNode(
                        BookmarkNodeType.FOLDER,
                        DESKTOP_ROOT,
                        BookmarkRoot.Mobile.id,
                        title = titles[DESKTOP_ROOT],
                        children = emptyList(),
                        position = null,
                        url = null,
                        dateAdded = java.util.Date().time,
                        lastModified = 0L
                    )
                )
                // Append all of the bookmarks in the mobile root.
                storage.getTree(BookmarkRoot.Mobile.id)?.children?.let { withDesktopFolder.addAll(it) }
                withDesktopFolder
            }
            DESKTOP_ROOT -> {
                val root = storage.getTree(BookmarkRoot.Root.id)
                root?.children
                    ?.filter { it.guid != BookmarkRoot.Mobile.id }
                    ?.map {
                        it.copy(title = titles[it.guid])
                    }
                }
            else -> {
                storage.getTree(guid)?.children?.toList()
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun addBookmark(aURL: String, aTitle: String) = GlobalScope.future {
        storage.addItem(BookmarkRoot.Mobile.id, aURL, aTitle, null)
        notifyAddedListeners()
    }

    fun deleteBookmarkByURL(aURL: String) = GlobalScope.future {
        val bookmark = getBookmarkByUrl(aURL)
        if (bookmark != null) {
            storage.deleteNode(bookmark.guid)
        }
        notifyListeners()
    }

    fun deleteBookmarkById(aId: String) = GlobalScope.future {
        storage.deleteNode(aId)
        notifyListeners()
    }

    fun isBookmarked(aURL: String): CompletableFuture<Boolean> = GlobalScope.future {
        getBookmarkByUrl(aURL) != null
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun getTree(guid: String, recursive: Boolean): CompletableFuture<List<BookmarkNode>?> = GlobalScope.future {
        storage.getTree(guid, recursive)?.children
                ?.map { it.copy(title = titles[it.guid]) }
    }

    fun searchBookmarks(query: String, limit: Int): CompletableFuture<List<BookmarkNode>> = GlobalScope.future {
        storage.searchBookmarks(query, limit)
    }

    // ── Phase 8b: Combo Bookmarks ─────────────────────────────────────────────

    /**
     * Returns the GUID of the Combo Bookmarks subfolder under Mobile root,
     * creating it at position 0 if it does not yet exist. Idempotent.
     */
    fun ensureComboBookmarksFolder(): CompletableFuture<String> = GlobalScope.future {
        val mobile = storage.getTree(BookmarkRoot.Mobile.id, recursive = false)
        val existing = mobile?.children?.firstOrNull {
            it.type == BookmarkNodeType.FOLDER && it.title == COMBO_BOOKMARKS_TITLE
        }
        existing?.guid ?: storage.addFolder(BookmarkRoot.Mobile.id, COMBO_BOOKMARKS_TITLE, 0u)
    }

    /**
     * Adds a bookmark under [parentGuid] and returns its GUID. Unlike [addBookmark],
     * this does not discard the GUID — callers need it to persist as [Binding.param].
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun addBookmarkReturningGuid(parentGuid: String, url: String, title: String): CompletableFuture<String> = GlobalScope.future {
        val guid = storage.addItem(parentGuid, url, title, null)
        notifyAddedListeners()
        guid
    }

    /** Returns the [BookmarkNode] for [guid], or null if it has been deleted. */
    fun getBookmarkByGuid(guid: String): CompletableFuture<BookmarkNode?> = GlobalScope.future {
        storage.getBookmark(guid)
    }

    /**
     * Deletes the bookmark with [url] from the Combo Bookmarks folder only. Plain bookmarks
     * elsewhere with the same URL are left untouched. Used by the combo-unbind inverse
     * cascade so removing a combo never deletes an unrelated bookmark the user kept.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun deleteComboBookmarkByURL(url: String) = GlobalScope.future {
        val mobile = storage.getTree(BookmarkRoot.Mobile.id, recursive = true)
        val comboFolder = mobile?.children?.firstOrNull {
            it.type == BookmarkNodeType.FOLDER && it.title == COMBO_BOOKMARKS_TITLE
        }
        val match = comboFolder?.children?.firstOrNull {
            it.type == BookmarkNodeType.ITEM && it.url == url
        }
        if (match != null) {
            storage.deleteNode(match.guid)
            notifyListeners()
        }
    }

    private suspend fun getBookmarkByUrl(aURL: String): BookmarkNode? {
        val bookmarks: List<BookmarkNode>? = storage.getBookmarksWithUrl(aURL)
        if (bookmarks == null || bookmarks.isEmpty()) {
            return null
        }

        for (bookmark in bookmarks) {
            if (bookmark.url.equals(aURL)) {
                return bookmark
            }
        }

        return null
    }

    private fun notifyListeners() {
        if (listeners.size > 0) {
            val listenersCopy = ArrayList(listeners)
            Handler(Looper.getMainLooper()).post {
                for (listener in listenersCopy) {
                    listener.onBookmarksUpdated()
                }
            }
        }
    }

    private fun notifyAddedListeners() {
        if (listeners.size > 0) {
            val listenersCopy = ArrayList(listeners)
            Handler(Looper.getMainLooper()).post {
                for (listener in listenersCopy) {
                    listener.onBookmarkAdded()
                }
            }
        }
    }
}
