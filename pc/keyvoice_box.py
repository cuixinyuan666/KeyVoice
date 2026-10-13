#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · Windows 10 接收端（状态小窗版）

- 没有可编辑输入框：只是一个常驻右上角、一直置顶的「状态 + 最近收到」小窗。
- 手机语音文字送达后：写入剪贴板 → 还原你正在用的窗口焦点 → Ctrl+V 直接上屏到
  任意有光标的输入框（记事本、浏览器、微信…）；没有光标则只留存剪贴板并弹窗提示。
- 局域网自动发现：监听 UDP 17891，手机点「搜索电脑」时回包，自动连上。
- adb 模式：`adb reverse tcp:17890 tcp:17890` 后手机发到 127.0.0.1 即可。

用法：  python keyvoice_box.py [端口]      默认 17890
依赖：仅 Python 标准库 + tkinter（无需 pip 安装任何东西）
"""

import ctypes
import json
import os
import socket
import sys
import threading
import time
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890

u32 = ctypes.WinDLL("user32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)

# ---------------------------------------------------------------- 结构体/原型

class GUITHREADINFO(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.c_ulong), ("flags", ctypes.c_ulong),
                ("hwndActive", ctypes.c_void_p), ("hwndFocus", ctypes.c_void_p),
                ("hwndCapture", ctypes.c_void_p), ("hwndMenuOwner", ctypes.c_void_p),
                ("hwndMoveSize", ctypes.c_void_p), ("hwndCaret", ctypes.c_void_p),
                ("rcCaret", ctypes.c_long * 4)]

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort),
                ("dwFlags", ctypes.c_ulong), ("time", ctypes.c_ulong),
                ("dwExtra", ctypes.c_ulonglong)]

class INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ulong), ("ki", KEYBDINPUT), ("pad", ctypes.c_ubyte * 8)]

u32.GetGUIThreadInfo.argtypes = [ctypes.c_ulong, ctypes.POINTER(GUITHREADINFO)]
u32.SendInput.argtypes = [ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int]
u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
u32.SetClipboardData.restype = ctypes.c_void_p
u32.GetClipboardData.argtypes = [ctypes.c_uint]
u32.GetClipboardData.restype = ctypes.c_void_p
u32.OpenClipboard.argtypes = [ctypes.c_void_p]
u32.OpenClipboard.restype = ctypes.c_int
u32.EmptyClipboard.restype = ctypes.c_int
u32.CloseClipboard.restype = ctypes.c_int
k32.GlobalAlloc.argtypes = [ctypes.c_size_t, ctypes.c_size_t]
k32.GlobalAlloc.restype = ctypes.c_void_p
k32.GlobalLock.argtypes = [ctypes.c_void_p]
k32.GlobalLock.restype = ctypes.c_void_p
k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
k32.GlobalUnlock.restype = ctypes.c_int
u32.GetForegroundWindow.restype = ctypes.c_void_p
u32.GetForegroundWindow.argtypes = []
u32.SetForegroundWindow.argtypes = [ctypes.c_void_p]
u32.SetForegroundWindow.restype = ctypes.c_int
u32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
u32.GetWindowThreadProcessId.restype = ctypes.c_ulong
k32.GetCurrentThreadId.argtypes = []
k32.GetCurrentThreadId.restype = ctypes.c_ulong
u32.AttachThreadInput.argtypes = [ctypes.c_ulong, ctypes.c_ulong, ctypes.c_int]
u32.AttachThreadInput.restype = ctypes.c_int

GUI_CARETBLINKING = 0x0001
CF_UNICODETEXT = 13
KEYEVENTF_KEYUP = 0x0002

# 少数软件不注册系统 caret，靠类名兜底
EDITABLE_CLASS = ("edit", "richedit", "scintilla", "chrome_widgetwin",
                  "chrome_renderwidgethosthwnd", "windows.ui.core.corewindow",
                  "_wwg", "qwidget", "sunawt", "terracontrol", "mintty", "vanilla",
                  "windowsforms10", "richedit20", "editcontrol")


# ---------------------------------------------------------------- 光标/窗口

def has_caret():
    g = GUITHREADINFO()
    g.cbSize = ctypes.sizeof(GUITHREADINFO)
    if not u32.GetGUIThreadInfo(0, ctypes.byref(g)):
        return False
    if g.flags & GUI_CARETBLINKING or g.hwndCaret:
        return True
    hwnd = g.hwndFocus or g.hwndActive
    if hwnd:
        buf = ctypes.create_unicode_buffer(256)
        u32.GetClassNameW(hwnd, buf, 256)
        return buf.value.lower().startswith(EDITABLE_CLASS)
    return False


def focus_external(hwnd):
    """把前台焦点还给指定的外部窗口（让 Ctrl+V 打到它身上）"""
    if not hwnd:
        return False
    try:
        pid = ctypes.c_ulong()
        tgt = u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        cur = k32.GetCurrentThreadId()
        u32.AttachThreadInput(cur, tgt, True)
        u32.SetForegroundWindow(hwnd)
        u32.AttachThreadInput(cur, tgt, False)
        return True
    except Exception:
        return False


# ------------------------------------------------------------------ 剪贴板

def set_clipboard(text):
    data = text.encode("utf-16-le") + b"\x00\x00"
    if not u32.OpenClipboard(None):
        return False
    try:
        u32.EmptyClipboard()
        h = k32.GlobalAlloc(0x0002, len(data))
        p = k32.GlobalLock(h)
        ctypes.memmove(p, data, len(data))
        k32.GlobalUnlock(h)
        return bool(u32.SetClipboardData(CF_UNICODETEXT, h))
    finally:
        u32.CloseClipboard()


def press_ctrl_v():
    def k(vk, up=False):
        return INPUT(1, KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, 0))
    seq = (INPUT * 4)(k(0x11), k(0x56), k(0x56, True), k(0x11, True))
    ptr = ctypes.cast(ctypes.byref(seq), ctypes.POINTER(INPUT))
    u32.SendInput(4, ptr, ctypes.sizeof(INPUT))


# -------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    app = None

    def do_GET(self):
        self.reply({"ok": True, "service": "keyvoice",
                    "connected": bool(Handler.app and Handler.app.count > 0)})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        text = ""
        try:
            text = json.loads(raw.decode("utf-8")).get("text", "")
        except Exception:
            pass
        if text and Handler.app:
            Handler.app.receive(text)
        self.reply({"ok": True})

    def reply(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def serve(port, app):
    Handler.app = app
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


# -------------------------------------------------------- 局域网自动发现（回应）

def udp_listen(port):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", 17891))
        s.settimeout(0.5)
        while True:
            try:
                data, addr = s.recvfrom(256)
                if b"KEYVOICE_DISCOVER" in data:
                    s.sendto(("KEYVOICE_HELLO:%d" % port).encode("utf-8"), addr)
            except socket.timeout:
                continue
            except Exception:
                break
    except Exception:
        pass


# -------------------------------------------------------------------- 界面

class Box:
    def __init__(self, root, port):
        self.root = root
        self.port = port
        self.count = 0
        self.last = ""
        self.last_external = None

        root.title("KeyVoice")
        root.attributes("-topmost", True)
        root.resizable(False, False)
        root.geometry("360x96+%d+40" % (root.winfo_screenwidth() - 380))
        root.configure(bg="#1F1F1F")
        try:
            root.wm_attributes("-toolwindow", True)   # 不在任务栏占大块
        except Exception:
            pass

        self.var_status = tk.StringVar(value="监听中（端口 %d）· 等待连接" % port)
        self.var_auto = tk.BooleanVar(value=True)

        tk.Label(root, textvariable=self.var_status, bg="#1F1F1F", fg="#90CAF9",
                 font=("Microsoft YaHei", 11), anchor="w", padx=8).pack(fill="x", pady=(8, 2))

        self.var_last = tk.StringVar(value="（收到语音文字会显示在这里并直接上屏）")
        tk.Label(root, textvariable=self.var_last, bg="#1F1F1F", fg="#E0E0E0",
                 font=("Microsoft YaHei", 10), anchor="w", padx=8, wraplength=340,
                 justify="left").pack(fill="x")

        bar = tk.Frame(root, bg="#1F1F1F")
        bar.pack(fill="x", padx=8, pady=(4, 6))
        tk.Button(bar, text="复制最近", width=8, font=("Microsoft YaHei", 9),
                  command=self.copy_last).pack(side="left")
        tk.Checkbutton(bar, text="收到即上屏", variable=self.var_auto, bg="#1F1F1F",
                       fg="#CCCCCC", font=("Microsoft YaHei", 8), selectcolor="#333333").pack(side="left", padx=4)
        tk.Button(bar, text="关闭", width=6, font=("Microsoft YaHei", 9),
                  command=self.close).pack(side="right")

        root.protocol("WM_DELETE_WINDOW", self.close)
        self._start_focus_tracker()
        self.update_status()

    # 跟踪"当前正在用的窗口"，上屏时把焦点还回去。
    # 注意：必须按进程 ID 排除自己的窗口 —— tkinter winfo_id() 与 GetForegroundWindow
    # 返回的不是同一个句柄，按句柄对比会把自己的窗口误当"外部窗口"。
    def _foreign(self, hwnd):
        if not hwnd:
            return False
        try:
            pid = ctypes.c_ulong()
            u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            return pid.value != os.getpid()
        except Exception:
            return False

    def _start_focus_tracker(self):
        def run():
            while True:
                try:
                    hwnd = u32.GetForegroundWindow()
                    if hwnd and self._foreign(hwnd):
                        self.last_external = hwnd
                except Exception:
                    pass
                time.sleep(0.2)
        threading.Thread(target=run, daemon=True).start()

    def receive(self, text):
        self.root.after(0, self._append, text)

    def _append(self, text):
        self.count += 1
        self.last = text
        # 状态：从"等待连接"切到"已连接"
        self.var_status.set("已连接 · 收到 %d 条 · 端口 %d" % (self.count, self.port))
        self.var_last.set("最近收到：%s" % text)

        set_clipboard(text)
        # 先上屏、后弹提示（弹窗会抢焦点，顺序不能反）
        if self.var_auto.get() and self._paste_to_foreign():
            self.toast("已上屏到当前输入框")
        else:
            self.toast("已存剪贴板（未上屏，点一下目标输入框再发即可 Ctrl+V）")
        self.flash_popup(text)

    def _paste_to_foreign(self):
        """上屏到外部应用：优先当前前台窗口，其次还原到最近使用的外部窗口"""
        fg = u32.GetForegroundWindow()
        if self._foreign(fg):
            time.sleep(0.03)
            press_ctrl_v()
            return True
        tgt = self.last_external
        if tgt and self._foreign(tgt):
            focus_external(tgt)
            time.sleep(0.06)
            if self._foreign(u32.GetForegroundWindow()):
                press_ctrl_v()
                return True
        return False

    # 收到文字时的小弹窗提示（2.5 秒后自动消失，不抢焦点）
    def flash_popup(self, text):
        try:
            tip = tk.Toplevel(self.root)
            tip.wm_overrideredirect(True)
            tip.attributes("-topmost", True)
            tip.configure(bg="#222222")
            tk.Label(tip, text="收到语音文字：", bg="#222222", fg="#90CAF9",
                     font=("Microsoft YaHei", 9)).pack(anchor="w", padx=10, pady=(6, 0))
            tk.Label(tip, text=text, bg="#222222", fg="#FFFFFF",
                     font=("Microsoft YaHei", 11), wraplength=360, justify="left").pack(
                anchor="w", padx=10, pady=(2, 8))
            x = self.root.winfo_screenwidth() - 380
            tip.geometry("+%d+150" % x)
            tip.after(2500, tip.destroy)
        except Exception:
            pass

    def toast(self, msg):
        self.var_status.set("已连接 · 收到 %d 条 · %s" % (self.count, msg))
        self.root.after(2500, self.update_status)

    def update_status(self):
        if self.count == 0:
            self.var_status.set("监听中（端口 %d）· 等待连接" % self.port)
        else:
            self.var_status.set("已连接 · 收到 %d 条 · 端口 %d" % (self.count, self.port))

    def copy_last(self):
        if self.last:
            set_clipboard(self.last)
            self.toast("已复制最近文字")

    def close(self):
        try:
            self.root.destroy()
        finally:
            raise SystemExit(0)


def main():
    try:  # pythonw（无控制台后台运行）下 stdout 为 None，print 会抛错
        print("KeyVoice 接收端  状态小窗 360x96 右上角置顶  端口 %d" % PORT)
        print("adb 模式：  adb reverse tcp:%d tcp:%d" % (PORT, PORT))
        print("局域网：手机点「搜索电脑」会自动连上本机")
    except Exception:
        pass
    threading.Thread(target=udp_listen, args=(PORT,), daemon=True).start()

    root = tk.Tk()
    app = Box(root, PORT)
    threading.Thread(target=serve, args=(PORT, app), daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
