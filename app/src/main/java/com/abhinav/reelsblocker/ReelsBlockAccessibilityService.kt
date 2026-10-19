package com.abhinav.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

typealias InstagramBlockerService = ReelsBlockAccessibilityService

/**
 * Blocks Instagram's main Reels tab/feed with GLOBAL_ACTION_BACK.
 * Reels opened inside a Direct Message thread are left alone.
 * Instagram Stories are explicitly ignored and allowed to play.
 */
class ReelsBlockAccessibilityService : AccessibilityService() {

    private val preferences by lazy { BlockerPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val visibleBounds = Rect()

    private var lastBackAtMs = 0L
    private var lastDmThreadAtMs = 0L
    private var lastInstagramAtMs = 0L
    private var pollScheduled = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            if (!preferences.isBlockerEnabled) return
            val root = instagramRoot()
            if (root != null) {
                lastInstagramAtMs = SystemClock.uptimeMillis()
                evaluate(root)
            }
            if (SystemClock.uptimeMillis() - lastInstagramAtMs < IG_POLL_KEEPALIVE_MS) {
                schedulePoll()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
            packageNames = arrayOf(INSTAGRAM_PACKAGE)
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        schedulePoll()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !preferences.isBlockerEnabled) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != INSTAGRAM_PACKAGE) return

        val root = instagramRoot() ?: return
        evaluate(root)
        schedulePoll()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(pollRunnable)
        pollScheduled = false
        return super.onUnbind(intent)
    }

    private fun schedulePoll() {
        if (pollScheduled) return
        pollScheduled = true
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    /**
     * Samsung One UI often makes the nav bar / overlay the "active" window.
     * Walk application windows and pick Instagram's tree instead.
     */
    private fun instagramRoot(): AccessibilityNodeInfo? {
        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            if (root.packageName?.toString() == INSTAGRAM_PACKAGE) return root
        }
        val fallback = rootInActiveWindow
        return fallback.takeIf { it?.packageName?.toString() == INSTAGRAM_PACKAGE }
    }

    private fun evaluate(root: AccessibilityNodeInfo) {
        val snapshot = scan(root)
        val now = SystemClock.uptimeMillis()
        if (snapshot.inDmThread && !snapshot.isStory) lastDmThreadAtMs = now

        val recentlyInDm = now - lastDmThreadAtMs < DM_STICKY_MS
        val allowBecauseDm = snapshot.inDmThread || (recentlyInDm && !snapshot.hasMainChrome)

        if (allowBecauseDm) {
            Log.d(TAG, "allow DM reel/thread $snapshot")
            return
        }

        if (snapshot.isStory) {
            Log.d(TAG, "allow Story $snapshot")
            return
        }

        if (!snapshot.isMainReels) return

        if (now - lastBackAtMs < BACK_COOLDOWN_MS) return
        lastBackAtMs = now
        Log.i(TAG, "blocking main Reels $snapshot")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun scan(root: AccessibilityNodeInfo): Snapshot {
        var inDmThread = false
        var isStory = false
        var hasHome = false
        var hasSearch = false
        var hasProfile = false
        var reelsTabSelected = false
        var visibleClipsPager = false
        var visibleClipsRoot = false

        isStory = STORY_VIEW_ID_HINTS.any { hasVisibleViewId(root, it) }

        visibleClipsPager = hasVisibleViewId(root, "clips_viewer_view_pager")
        visibleClipsRoot = hasVisibleViewId(root, "root_clips_layout") ||
            hasVisibleViewId(root, "clips_action_bar") ||
            hasVisibleViewId(root, "clips_video_container")

        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val id = node.viewIdResourceName?.substringAfter("/").orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            val hint = node.hintText?.toString()?.lowercase().orEmpty()
            val combined = "$id $desc $text $hint"

            if (node.isOnScreen()) {
                if (id.startsWith("reel_viewer") ||
                    id.contains("segmented_progress_bar") ||
                    id.contains("story_progress_bar") ||
                    desc.startsWith("story by") ||
                    desc.contains("story by") ||
                    desc.contains("story of")
                ) {
                    isStory = true
                }
            }

            if (!isStory) {
                if (DM_THREAD_ID_HINTS.any { id.contains(it) } && node.isOnScreen()) {
                    inDmThread = true
                }
                if (DM_THREAD_TEXT_HINTS.any { combined.contains(it) } && node.isOnScreen()) {
                    inDmThread = true
                }
            }

            if (node.isOnScreen()) {
                when {
                    desc == "home" || desc.contains("home tab") -> hasHome = true
                    desc.contains("search and explore") || desc == "search" ||
                        desc.contains("explore tab") -> hasSearch = true
                    desc == "profile" || desc.contains("profile tab") -> hasProfile = true
                }
            }

            val reelsLabel = desc == "reels" || desc.contains("reels tab") ||
                desc.contains("reel tab") || text == "reels"
            if (reelsLabel && (node.isSelected || node.isChecked)) {
                reelsTabSelected = true
            }
        }

        return Snapshot(
            inDmThread = inDmThread,
            isStory = isStory,
            hasMainChrome = hasHome || hasSearch || hasProfile,
            reelsTabSelected = reelsTabSelected,
            visibleClipsSurface = visibleClipsPager || visibleClipsRoot,
        )
    }

    private fun hasVisibleViewId(root: AccessibilityNodeInfo, shortId: String): Boolean {
        val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
        val matches = root.findAccessibilityNodeInfosByViewId(fullId) ?: return false
        return matches.any { it.isOnScreen() }
    }

    private fun AccessibilityNodeInfo.isOnScreen(): Boolean {
        if (!isVisibleToUser) return false
        getBoundsInScreen(visibleBounds)
        return visibleBounds.width() > 8 && visibleBounds.height() > 8
    }

    private fun walk(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        visitor(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visitor)
        }
    }

    private data class Snapshot(
        val inDmThread: Boolean,
        val isStory: Boolean,
        val hasMainChrome: Boolean,
        val reelsTabSelected: Boolean,
        val visibleClipsSurface: Boolean,
    ) {
        val isMainReels: Boolean
            get() = !isStory && (visibleClipsSurface || reelsTabSelected)
    }

    private companion object {
        const val TAG = "ReelsBlocker"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val BACK_COOLDOWN_MS = 500L
        const val POLL_MS = 400L
        const val DM_STICKY_MS = 2_500L
        const val IG_POLL_KEEPALIVE_MS = 8_000L

        val STORY_VIEW_ID_HINTS = listOf(
            "reel_viewer_root",
            "reel_viewer_container",
            "reel_viewer_view_pager",
            "reel_viewer_image_view",
            "reel_item_progress_bar",
            "segmented_progress_bar",
            "reel_viewer_progress_bar",
            "reel_viewer_title",
            "reel_viewer_timestamp",
            "reel_viewer_header",
            "reel_viewer_media_container",
            "reel_viewer_tall_shadow",
            "story_progress_bar",
        )

        val DM_THREAD_ID_HINTS = listOf(
            "direct_thread",
            "row_thread_composer",
            "thread_composer",
            "direct_text_message",
            "message_composer",
            "direct_composer",
            "direct_visual_timeline",
            "direct_private_share",
            "permalink_message",
            "row_inbox_composer",
        )
        val DM_THREAD_TEXT_HINTS = listOf(
            "send message",
            "message…",
            "message...",
            "voice message",
        )
    }
}
