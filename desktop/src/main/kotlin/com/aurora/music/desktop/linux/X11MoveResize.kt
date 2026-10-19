package com.aurora.music.desktop.linux

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Window
import kotlin.math.roundToLong

// asks the window manager to move or resize an undecorated window (_NET_WM_MOVERESIZE), as gtk and qt do
object X11MoveResize {
    const val TOP_LEFT = 0
    const val TOP = 1
    const val TOP_RIGHT = 2
    const val RIGHT = 3
    const val BOTTOM_RIGHT = 4
    const val BOTTOM = 5
    const val BOTTOM_LEFT = 6
    const val LEFT = 7
    const val MOVE = 8

    @Volatile private var supported: Boolean? = null

    // false when this isn't x11 or the window manager can't do it; the caller then moves the window itself
    fun begin(window: Window, direction: Int): Boolean {
        if (supported == false) return false
        return runCatching {
            val toolkit = Class.forName("sun.awt.X11.XToolkit")
            val lock = Class.forName("sun.awt.SunToolkit")
            val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
            val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor").getMethod("getPeer", Component::class.java).invoke(accessor, window)
            val shell = Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").apply { isAccessible = true }.invoke(peer) as Long
            val display = X11.Display().apply { pointer = Pointer(toolkit.getMethod("getDisplay").invoke(null) as Long) }
            val scale = window.graphicsConfiguration.defaultTransform
            val at = MouseInfo.getPointerInfo().location
            lock.getMethod("awtLock").invoke(null)
            try {
                val x11 = X11.INSTANCE
                val root = x11.XDefaultRootWindow(display)
                val moveResize = x11.XInternAtom(display, "_NET_WM_MOVERESIZE", false)
                if (!advertised(x11, display, root, moveResize)) return false.also { supported = false }
                val event = X11.XEvent()
                event.type = X11.ClientMessage
                event.xclient.type = X11.ClientMessage
                event.xclient.send_event = 1
                event.xclient.display = display
                event.xclient.window = X11.Window(shell)
                event.xclient.message_type = moveResize
                event.xclient.format = 32
                event.xclient.data.setType(Array<NativeLong>::class.java)
                listOf((at.x * scale.scaleX).roundToLong(), (at.y * scale.scaleY).roundToLong(), direction.toLong(), 1L, 1L)
                    .forEachIndexed { i, value -> event.xclient.data.l[i] = NativeLong(value) }
                event.setType(X11.XClientMessageEvent::class.java)
                // the press holds an implicit grab that would stop the window manager taking the pointer
                Xlib.api.XUngrabPointer(display, NativeLong(0))
                val sent = x11.XSendEvent(display, root, 0, NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong()), event) != 0
                x11.XFlush(display)
                sent.also { supported = it }
            } finally {
                lock.getMethod("awtUnlock").invoke(null)
            }
        }.getOrElse { supported = false; false }
    }

    private fun advertised(x11: X11, display: X11.Display, root: X11.Window, atom: X11.Atom): Boolean {
        val items = NativeLongByReference()
        val data = PointerByReference()
        val status = x11.XGetWindowProperty(display, root, x11.XInternAtom(display, "_NET_SUPPORTED", false), NativeLong(0), NativeLong(4096),
            false, X11.XA_ATOM, X11.AtomByReference(), IntByReference(), items, NativeLongByReference(), data)
        val list = data.value ?: return false
        return try {
            status == 0 && (0 until items.value.toInt()).any { list.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() == atom.toLong() }
        } finally {
            x11.XFree(list)
        }
    }

    @Suppress("FunctionName")
    private interface XlibApi : Library {
        fun XUngrabPointer(display: X11.Display, time: NativeLong): Int
    }

    private object Xlib {
        val api: XlibApi = Native.load("X11", XlibApi::class.java)
    }
}
