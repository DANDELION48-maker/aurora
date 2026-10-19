package com.aurora.music.desktop.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import com.aurora.music.util.AppLog
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.Canvas
import java.awt.Component
import java.awt.Container

// keeps the native frame (snap, shadow, resize borders, backdrop) and gives its caption to compose
class CaptionFrame private constructor(private val hwnd: HWND) {
    // client pixels: bar height, the caption buttons compose handles, and the maximize button windows handles
    class Bar(val height: Int = 0, val buttons: IntRange = IntRange.EMPTY, val maximize: IntRange = IntRange.EMPTY)

    @Volatile var bar = Bar()
    var maximizeHovered by mutableStateOf(false)
        private set
    var maximizePressed by mutableStateOf(false)
        private set

    @Volatile private var border = 0
    private val procs = mutableListOf<WinUser.WindowProc>()

    private fun subclass(target: HWND, handler: (Pointer, Int, WPARAM, LPARAM) -> LRESULT?) {
        val original = Pointer(User32.INSTANCE.GetWindowLongPtr(target, GWLP_WNDPROC).toLong())
        val proc = WinUser.WindowProc { h, msg, w, l ->
            runCatching { handler(original, msg, w, l) }.getOrNull() ?: User32.INSTANCE.CallWindowProc(original, h, msg, w, l)
        }
        procs += proc
        Api.user.SetWindowLongPtrW(target, GWLP_WNDPROC, proc) ?: error("subclass failed")
    }

    private fun onFrame(original: Pointer, msg: Int, w: WPARAM, l: LPARAM): LRESULT? = when (msg) {
        WM_NCCALCSIZE -> if (w.toInt() == 0) null else {
            val client = Pointer(l.toLong())
            val top = client.getInt(4)
            val bottom = client.getInt(12)
            val result = User32.INSTANCE.CallWindowProc(original, hwnd, msg, w, l)
            border = bottom - client.getInt(12)
            // a maximized window hangs its frame off the monitor, so only the top frame is trimmed
            client.setInt(4, if (Api.user.IsZoomed(hwnd)) top + border else top)
            result
        }
        WM_NCHITTEST -> {
            val native = User32.INSTANCE.CallWindowProc(original, hwnd, msg, w, l)
            if (native.toInt() != HTCLIENT) native else LRESULT(hit(l).toLong())
        }
        WM_NCMOUSEMOVE -> {
            val over = w.toInt() == HTMAXBUTTON
            if (maximizeHovered != over) maximizeHovered = over
            if (!over) maximizePressed = false
            if (over) Api.user.TrackMouseEvent(TrackMouse(hwnd))
            null
        }
        WM_NCMOUSELEAVE -> {
            maximizeHovered = false
            maximizePressed = false
            null
        }
        WM_NCLBUTTONDOWN, WM_NCLBUTTONDBLCLK -> if (w.toInt() != HTMAXBUTTON) null else {
            maximizePressed = true
            LRESULT(0)
        }
        WM_NCLBUTTONUP -> if (w.toInt() != HTMAXBUTTON) null else {
            if (maximizePressed) toggleMaximized()
            maximizePressed = false
            LRESULT(0)
        }
        else -> null
    }

    // compose's canvas covers the client area, so it passes caption hits through to the frame
    private fun onCanvas(msg: Int, l: LPARAM): LRESULT? =
        if (msg == WM_NCHITTEST && hit(l) != HTCLIENT) LRESULT(HTTRANSPARENT.toLong()) else null

    private fun hit(l: LPARAM): Int {
        val point = POINT(l.toInt().toShort().toInt(), (l.toInt() shr 16).toShort().toInt())
        if (!Api.user.ScreenToClient(hwnd, point)) return HTCLIENT
        val sizable = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WinUser.WS_THICKFRAME != 0
        if (sizable && !Api.user.IsZoomed(hwnd) && point.y in 0 until border) {
            val client = RECT().also { User32.INSTANCE.GetClientRect(hwnd, it) }
            return when {
                point.x < border -> HTTOPLEFT
                point.x >= client.right - border -> HTTOPRIGHT
                else -> HTTOP
            }
        }
        val bar = bar
        return when {
            point.y !in 0 until bar.height -> HTCLIENT
            point.x in bar.maximize -> HTMAXBUTTON
            point.x in bar.buttons -> HTCLIENT
            else -> HTCAPTION
        }
    }

    private fun toggleMaximized() {
        val command = if (Api.user.IsZoomed(hwnd)) SC_RESTORE else SC_MAXIMIZE
        User32.INSTANCE.PostMessage(hwnd, WinUser.WM_SYSCOMMAND, WPARAM(command.toLong()), LPARAM(0))
    }

    @Structure.FieldOrder("size", "flags", "window", "hoverTime")
    class TrackMouse(@JvmField var window: HWND? = null) : Structure() {
        @JvmField var size: Int = 0
        @JvmField var flags: Int = TME_LEAVE or TME_NONCLIENT
        @JvmField var hoverTime: Int = 0

        init { size = size() }
    }

    @Suppress("FunctionName")
    private interface UserApi : StdCallLibrary {
        fun SetWindowLongPtrW(hwnd: HWND, index: Int, proc: WinUser.WindowProc): Pointer?
        fun ScreenToClient(hwnd: HWND, point: POINT): Boolean
        fun IsZoomed(hwnd: HWND): Boolean
        fun TrackMouseEvent(track: TrackMouse): Boolean
    }

    private object Api {
        val user: UserApi = Native.load("user32", UserApi::class.java)
    }

    companion object {
        private const val TAG = "CaptionFrame"
        private const val GWLP_WNDPROC = -4
        private const val GA_ROOT = 2
        private const val WM_NCCALCSIZE = 0x0083
        private const val WM_NCHITTEST = 0x0084
        private const val WM_NCMOUSEMOVE = 0x00A0
        private const val WM_NCLBUTTONDOWN = 0x00A1
        private const val WM_NCLBUTTONUP = 0x00A2
        private const val WM_NCLBUTTONDBLCLK = 0x00A3
        private const val WM_NCMOUSELEAVE = 0x02A2
        private const val HTTRANSPARENT = -1
        private const val HTCLIENT = 1
        private const val HTCAPTION = 2
        private const val HTMAXBUTTON = 9
        private const val HTTOP = 12
        private const val HTTOPLEFT = 13
        private const val HTTOPRIGHT = 14
        private const val SC_MAXIMIZE = 0xF030
        private const val SC_RESTORE = 0xF120
        private const val TME_LEAVE = 0x2
        private const val TME_NONCLIENT = 0x10
        private const val SWP_FRAME = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020 or 0x0200

        // call before the window is shown so it never flashes the system caption
        fun install(window: ComposeWindow): CaptionFrame? {
            if (!HostPlatform.isWindows) return null
            return runCatching {
                if (!window.isDisplayable) window.addNotify()
                val handle = Pointer(window.windowHandle.takeIf { it != 0L } ?: error("no window handle"))
                val hwnd = HWND(User32.INSTANCE.GetAncestor(HWND(handle), GA_ROOT)?.pointer ?: handle)
                val frame = CaptionFrame(hwnd)
                frame.subclass(hwnd, frame::onFrame)
                window.canvases().forEach { canvas -> frame.subclass(HWND(Native.getComponentPointer(canvas))) { _, msg, _, l -> frame.onCanvas(msg, l) } }
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAME)
                frame
            }.onFailure { AppLog.w(TAG, "custom caption unavailable: ${it.message}") }.getOrNull()
        }

        private fun Component.canvases(): List<Canvas> =
            if (this is Canvas) listOf(this) else (this as? Container)?.components?.flatMap { it.canvases() }.orEmpty()
    }
}
