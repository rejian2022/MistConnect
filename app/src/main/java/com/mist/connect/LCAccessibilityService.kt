package com.mist.connect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LCAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LCAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun tap(x: Int, y: Int, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGestureAndWait(gesture)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 400L): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGestureAndWait(gesture)
    }

    fun pressKey(key: String): Boolean {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MistConnect输入", text))
        return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun tapText(text: String, exact: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text).orEmpty()
        val node = nodes.firstOrNull {
            if (!exact) true else {
                it.text?.toString() == text || it.contentDescription?.toString() == text
            }
        } ?: return false

        val clickable = findClickableNode(node)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) return tap(rect.centerX(), rect.centerY())
        return false
    }

    fun dumpUiTree(maxDepth: Int = 8, includeBounds: Boolean = true): JSONObject {
        val root = rootInActiveWindow
        return JSONObject().apply {
            put("available", root != null)
            if (root == null) {
                put("error", "rootInActiveWindow为空，请确认无障碍服务已开启且允许读取窗口内容")
            } else {
                put("root", nodeToJson(root, 0, maxDepth.coerceIn(1, 20), includeBounds))
            }
        }
    }

    fun getRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun takeScreenshotNow(callback: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()

                        if (bitmap != null) {
                            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()

                            val stream = ByteArrayOutputStream()
                            softBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                            softBitmap.recycle()

                            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            callback(base64)
                        } else {
                            callback(null)
                        }
                    } catch (_: Exception) {
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            })
    }

    private fun dispatchGestureAndWait(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                ok = false
                latch.countDown()
            }
        }, null)
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findEditableNode(it) }
            if (found != null) return found
        }
        return null
    }

    private fun findClickableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        includeBounds: Boolean
    ): JSONObject {
        val obj = JSONObject()
        obj.put("className", node.className?.toString() ?: "")
        obj.put("packageName", node.packageName?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("contentDescription", node.contentDescription?.toString() ?: "")
        obj.put("viewIdResourceName", node.viewIdResourceName ?: "")
        obj.put("clickable", node.isClickable)
        obj.put("editable", node.isEditable)
        obj.put("enabled", node.isEnabled)
        obj.put("focused", node.isFocused)
        obj.put("scrollable", node.isScrollable)
        obj.put("checked", node.isChecked)

        if (includeBounds) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            obj.put("bounds", JSONObject().apply {
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
                put("centerX", rect.centerX())
                put("centerY", rect.centerY())
            })
        }

        if (depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { children.put(nodeToJson(it, depth + 1, maxDepth, includeBounds)) }
            }
            obj.put("children", children)
        }
        return obj
    }
}
