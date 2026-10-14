package com.example.buddy

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.buddy.security.InjectionGuard
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * AccessibilityService named AutoService that enables Buddy to inspect and
 * interact with the current screen:
 * - clickByText(text)
 * - typeText(text)
 * - back()
 * - home()
 * - scrollForward() / scrollBackward()
 * - screenSnapshot() -> compact JSON of visible nodes
 */
class AutoService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event stream handled on-demand via rootInActiveWindow in tool calls
    }

    override fun onInterrupt() {
        // Required override; no ongoing gesture streams to cancel
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Finds a node matching [text] (by visible text or contentDescription, case-insensitive)
     * and clicks it or its nearest clickable ancestor.
     */
    fun clickByText(text: String): Boolean {
        val query = text.trim()
        if (query.isEmpty()) return false
        val root = rootInActiveWindow ?: return false

        // 1. Try framework search first
        val frameworkMatches = root.findAccessibilityNodeInfosByText(query)
        if (!frameworkMatches.isNullOrEmpty()) {
            for (node in frameworkMatches) {
                if (performClickOnNodeOrParent(node)) {
                    return true
                }
            }
        }

        // 2. Fallback: DFS traversal matching text or contentDescription
        val fallbackNode = findNodeByTextOrDescRecursive(root, query)
        return fallbackNode != null && performClickOnNodeOrParent(fallbackNode)
    }

    /**
     * Types [text] into the currently focused editable node, or searches the tree
     * for the first visible editable field, focuses it, and sets its text.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?: findFirstEditableNode(root)
            ?: return false

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Performs global Back navigation.
     */
    fun back(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Performs global Home navigation.
     */
    fun home(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Scrolls the first scrollable container forward.
     */
    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scrolls the first scrollable container backward.
     */
    fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /**
     * Produces a compact JSON representation of visible, meaningful UI nodes on screen.
     * Sanitizes all node labels through [InjectionGuard] to defend against prompt injection.
     */
    fun screenSnapshot(): String {
        val root = rootInActiveWindow
        if (root == null) {
            return JsonObject().apply {
                addProperty("active", false)
                add("nodes", JsonArray())
            }.toString()
        }

        val nodesArray = JsonArray()
        collectVisibleNodes(root, nodesArray, maxNodes = 65)

        val snapshot = JsonObject().apply {
            addProperty("package", root.packageName?.toString().orEmpty())
            add("nodes", nodesArray)
        }
        return snapshot.toString()
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findNodeByTextOrDescRecursive(
        node: AccessibilityNodeInfo?,
        query: String
    ): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val nodeText = node.text?.toString().orEmpty()
        val nodeDesc = node.contentDescription?.toString().orEmpty()
        if (nodeText.contains(query, ignoreCase = true) ||
            nodeDesc.contains(query, ignoreCase = true)
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findNodeByTextOrDescRecursive(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable && node.isEnabled) return node

        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val found = findFirstScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun collectVisibleNodes(
        node: AccessibilityNodeInfo?,
        out: JsonArray,
        maxNodes: Int
    ) {
        if (node == null || out.size() >= maxNodes) return
        if (!node.isVisibleToUser) return

        val cleanText = InjectionGuard.sanitizeNodeText(node.text)
        val cleanDesc = InjectionGuard.sanitizeNodeText(node.contentDescription)
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable

        if (cleanText.isNotEmpty() || cleanDesc.isNotEmpty() || isInteractive) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val item = JsonObject().apply {
                if (cleanText.isNotEmpty()) addProperty("t", cleanText)
                if (cleanDesc.isNotEmpty() && cleanDesc != cleanText) addProperty("d", cleanDesc)
                if (node.isClickable) addProperty("click", true)
                if (node.isEditable) addProperty("edit", true)
                if (node.isScrollable) addProperty("scroll", true)
                addProperty("b", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            }
            out.add(item)
        }

        for (i in 0 until node.childCount) {
            if (out.size() >= maxNodes) break
            collectVisibleNodes(node.getChild(i), out, maxNodes)
        }
    }

    companion object {
        @Volatile
        var instance: AutoService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
