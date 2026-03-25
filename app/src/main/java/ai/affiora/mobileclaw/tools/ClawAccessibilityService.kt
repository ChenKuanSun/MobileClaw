package ai.affiora.mobileclaw.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Core AccessibilityService that enables MobileClaw to read and interact
 * with ANY app's UI. This is the foundation of UI automation.
 */
class ClawAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClawAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    /** Flat indexed list of nodes from the last getScreenContent() call. */
    private var lastNodeList: List<AccessibilityNodeInfo> = emptyList()

    /** Last accessibility event for context. */
    @Volatile
    var lastEvent: AccessibilityEvent? = null
        private set

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEvent = event
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Core Methods ──────────────────────────────────────────────────

    /**
     * Walk the accessibility node tree and return a structured text
     * representation of all visible UI elements. Stores nodes in a flat
     * indexed list so the AI can reference them by index.
     *
     * Output format:
     * [0] Button "Sign In" (clickable)
     * [1] EditText "Email" {com.app:id/email} (editable, focused)
     */
    fun getScreenContent(): String {
        val root = rootInActiveWindow ?: return "No active window available."
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()

        walkTree(root, nodes, sb, depth = 0)
        lastNodeList = nodes

        return if (sb.isEmpty()) "Screen is empty or unreadable." else sb.toString()
    }

    private fun walkTree(
        node: AccessibilityNodeInfo,
        nodes: MutableList<AccessibilityNodeInfo>,
        sb: StringBuilder,
        depth: Int,
    ) {
        val index = nodes.size
        nodes.add(node)

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        val label = text ?: contentDesc

        val attrs = mutableListOf<String>()
        if (node.isClickable) attrs.add("clickable")
        if (node.isEditable) attrs.add("editable")
        if (node.isScrollable) attrs.add("scrollable")
        if (node.isCheckable) attrs.add(if (node.isChecked) "checked" else "unchecked")
        if (node.isFocused) attrs.add("focused")
        if (node.isSelected) attrs.add("selected")

        // Only emit nodes that carry meaningful info
        val hasInfo = label != null || viewId != null || attrs.isNotEmpty()
        if (hasInfo) {
            sb.append(indent)
            sb.append("[$index] $className")
            if (label != null) {
                val truncated = if (label.length > 80) label.take(80) + "..." else label
                sb.append(" \"$truncated\"")
            }
            if (viewId != null) {
                sb.append(" {$viewId}")
            }
            if (attrs.isNotEmpty()) {
                sb.append(" (${attrs.joinToString(", ")})")
            }
            sb.append('\n')
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkTree(child, nodes, sb, if (hasInfo) depth + 1 else depth)
        }
    }

    /** Click the node at the given index from the last getScreenContent() call. */
    fun clickNode(index: Int): Boolean {
        val node = lastNodeList.getOrNull(index) ?: return false
        return performClickOn(node)
    }

    /** Find and click the first node containing the given text. */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return false
        return performClickOn(nodes[0])
    }

    /** Find and click a node by its resource ID. */
    fun clickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNullOrEmpty()) return false
        return performClickOn(nodes[0])
    }

    private fun performClickOn(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node directly, then walk up to find a clickable parent
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        // Last resort: click the original node even if not marked clickable
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Type text into the currently focused editable field. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    /** Scroll down on the first scrollable node. */
    fun scrollDown(): Boolean {
        val scrollable = findScrollable() ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** Scroll up on the first scrollable node. */
    fun scrollUp(): Boolean {
        val scrollable = findScrollable() ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFirstScrollable(root)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }

    // ── Global Actions ────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun takeScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
}
