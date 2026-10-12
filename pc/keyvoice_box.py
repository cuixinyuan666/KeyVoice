#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · Windows 10 悬浮接收框

一个一直置顶的小窗口，手机语音转文字通过 adb（USB）送过来后显示在这里：
    [复制]   复制框里全部文字
    [全部清除] 清空
    [关闭]    退出程序

用法：  python keyvoice_box.py [端口]      默认 17890
adb：  adb reverse tcp:17890 tcp:17890     （手机插 USB 时执行一次）
"""

import ctypes
import json
import socket
import sys
import threading
import time
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890

u32 = ctypes.WinDLL("user32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)


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
k32.GlobalAlloc.argtypes = [ctypes.c_size_t, ctypes.c_size_t]
k32.GlobalAlloc.restype = ctypes.c_void_p
k32.GlobalLock.argtypes = [ctypes.c_void_p]
k32.GlobalLock.restype = ctypes.c_void_p
k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
k32.GlobalUnlock.restype = ctypes.c_int

GUI_CARETBLINKING = 0x0001
CF_UNICODETEXT = 13
KEYEVENTF_KEYUP = 0x0002

# 少数软件（Chrome / Electron / VS Code 等）不注册系统 caret，靠类名兜底
EDITABLE_CLASS = ("edit", "richedit", "scintilla", "chrome_widgetwin",
                  "chrome_renderwidgethosthwnd", "windows.ui.core.corewindow",
                  "_wwg", "qwidget", "sunawt", "terracontrol", "mintty", "vanilla")


# ---------------------------------------------------------------- 光标检测

def has_caret():
    """当前是否有正在闪烁的输入光标"""
    g = GUITHREADINFO()
    g.cbSize = ctypes.sizeof(GUITHREADINFO)
    if not u32.GetGUIThreadInfo(0, ctypes.byref(g)):
        return False
    if g.flags & GUI_CARETBLINKING:
        return True
    if g.hwndCaret:
        return True
    hwnd = g.hwndFocus or g.hwndActive
    if hwnd:
        buf = ctypes.create_unicode_buffer(256)
        u32.GetClassNameW(hwnd, buf, 256)
        return buf.value.lower().startswith(EDITABLE_CLASS)
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
    u32.SendInput(4, ctypes.byref(seq), ctypes.sizeof(INPUT))


# -------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    app = None  # 由 main 注入

    def do_GET(self):
        self.reply({"ok": True, "service": "keyvoice"})

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


# -------------------------------------------------------------------- 界面

class Box:
    def __init__(self, root, port):
        self.root = root
        self.port = port
        self.count = 0

        root.title("KeyVoice · 接收框")
        root.attributes("-topmost", True)                 # 一直置顶
        root.geometry("420x300+%d+60" % (root.winfo_screenwidth() - 440))
        root.minsize(320, 220)
        root.configure(bg="#FAFAFA")

        # 顶部状态
        self.var_status = tk.StringVar(value="监听中…")
        tk.Label(root, textvariable=self.var_status, bg="#FAFAFA", fg="#555",
                 anchor="w", font=("Microsoft YaHei", 9)).pack(fill="x", padx=8, pady=(8, 2))

        # 文本区
        frame = tk.Frame(root)
        frame.pack(fill="both", expand=True, padx=8, pady=4)
        self.txt = tk.Text(frame, wrap="word", font=("Microsoft YaHei", 12),
                           bg="#FFFFFF", fg="#111111", relief="solid", bd=1)
        sb = tk.Scrollbar(frame, command=self.txt.yview)
        self.txt.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.txt.pack(side="left", fill="both", expand=True)

        # 选项
        self.var_auto = tk.BooleanVar(value=True)
        tk.Checkbutton(root, text="收到即上屏（有输入光标才上屏，没光标只存剪贴板）",
                       variable=self.var_auto, bg="#FAFAFA",
                       font=("Microsoft YaHei", 9)).pack(anchor="w", padx=8)

        # 按钮
        bar = tk.Frame(root, bg="#FAFAFA")
        bar.pack(fill="x", padx=8, pady=8)
        tk.Button(bar, text="复制", width=8, command=self.copy_all).pack(side="left")
        tk.Button(bar, text="全部清除", width=8, command=self.clear_all).pack(side="left", padx=6)
        tk.Button(bar, text="关闭", width=8, command=self.close).pack(side="right")

        root.protocol("WM_DELETE_WINDOW", self.close)
        self.update_status()

    def update_status(self):
        self.var_status.set("端口 %d · 已收 %d 条 · adb: adb reverse tcp:%d tcp:%d"
                            % (self.port, self.count, self.port, self.port))

    # 由 HTTP 线程调用，转回 UI 线程
    def receive(self, text):
        self.root.after(0, self._append, text)

    def _append(self, text):
        self.count += 1
        if self.count > 1:
            self.txt.insert("end", "\n")
        self.txt.insert("end", text)
        self.txt.see("end")
        self.update_status()

        set_clipboard(text)
        if self.var_auto.get():
            time.sleep(0.05)
            press_ctrl_v()     # 有光标就上屏，没光标这一步不会造成破坏

    def copy_all(self):
        data = self.txt.get("1.0", "end-1c")
        if data:
            set_clipboard(data)

    def clear_all(self):
        self.txt.delete("1.0", "end")
        self.count = 0
        self.update_status()

    def close(self):
        try:
            self.root.destroy()
        finally:
            raise SystemExit(0)


def main():
    ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        s.close()
    except OSError:
        pass
    print("KeyVoice 悬浮接收框  端口 %d" % PORT)
    print("adb 模式：  adb reverse tcp:%d tcp:%d" % (PORT, PORT))
    print("局域网模式：手机填  %s" % ip)

    root = tk.Tk()
    app = Box(root, PORT)
    threading.Thread(target=serve, args=(PORT, app), daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
