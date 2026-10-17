package com.aurora.music.desktop.platform

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser

// full screen without java's exclusive mode, which stalls the window once chromium's opengl shares the process
object BorderlessFullscreen {
    private class Saved(val style: Int, val placement: WinUser.WINDOWPLACEMENT)
    private val saved = HashMap<Long, Saved>()

    val supported: Boolean get() = HostPlatform.isWindows

    fun enter(handle: Long): Boolean {
        if (!supported || handle == 0L || handle in saved) return false
        val hwnd = HWND(Pointer(handle))
        val user = User32.INSTANCE
        val placement = WinUser.WINDOWPLACEMENT()
        if (!user.GetWindowPlacement(hwnd, placement).booleanValue()) return false
        val monitor = WinUser.MONITORINFO()
        if (!user.GetMonitorInfo(user.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor).booleanValue()) return false
        val style = user.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        saved[handle] = Saved(style, placement)
        user.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and FRAME.inv())
        val area = monitor.rcMonitor
        user.SetWindowPos(hwnd, null, area.left, area.top, area.right - area.left, area.bottom - area.top,
            WinUser.SWP_NOZORDER or SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED)
        return true
    }

    fun exit(handle: Long): Boolean {
        val previous = saved.remove(handle) ?: return false
        val hwnd = HWND(Pointer(handle))
        val user = User32.INSTANCE
        user.SetWindowLong(hwnd, WinUser.GWL_STYLE, previous.style)
        user.SetWindowPlacement(hwnd, previous.placement)
        user.SetWindowPos(hwnd, null, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED)
        return true
    }

    private const val FRAME = 0x00C00000 or 0x00040000
    private const val SWP_NOOWNERZORDER = 0x0200
}
