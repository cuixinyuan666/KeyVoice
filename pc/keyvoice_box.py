#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · Windows 接收端（消息列表版，深色设计系统）

- 常驻置顶窗口，支持拖动调节大小；不操作时自动收起贴到屏幕顶部，收到消息时绿闪并展开。
- 所有收到的语音文字进入「消息列表」（可滚动），每条独立成行：
    · 复制：把这条文字写入剪贴板
    · 编辑：行内改文字（改完点保存）
    · 拖拽：按住这条文字拖到下方输入框松手，即把文字粘贴进输入框
- 收到即上屏（默认开）：若当前有外部输入框带光标，自动 Ctrl+V 上屏；
  用 has_caret() 做真实光标校验，避免打到没有焦点的窗口（修复“收到却没录入”的 bug）。
- 底部输入框：可手动编辑 / 粘贴，点「上屏」把内容发到当前光标处；「清空」清输入框；
  「清空列表」清掉所有历史消息。
- 局域网自动发现：监听 UDP 17891，手机点「搜索电脑」时回包，自动连上。
- adb 模式：adb reverse tcp:17890 tcp:17890 后手机发到 127.0.0.1 即可。

配色与 Android 端（res/values/colors.xml / Ui.java）共用同一套深色 token。
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

# ----------------------------------------------------------- 深色设计 token
BG = "#0E1116"          # 窗口背景
SURFACE = "#161A21"     # 顶栏 / 卡片
SURFACE_HI = "#1F2530"  # 按钮 / 输入框
BORDER = "#2A313C"      # 分割线
TEXT = "#E6EAF0"        # 主文字
TEXT2 = "#9AA4B2"       # 次文字
TEXT3 = "#6B7585"       # 弱文字
PRIMARY = "#4F8CFF"     # 品牌蓝
PRIMARY_PRESS = "#3B75E6"
ACCENT = "#22D3EE"      # 强调（活动指示）
SUCCESS = "#34D399"
WARNING = "#FBBF24"
ERROR = "#F87171"

FONT_TITLE = ("Microsoft YaHei", 12, "bold")
FONT_SM = ("Microsoft YaHei", 10)

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
u32.AllowSetForegroundWindow.argtypes = [ctypes.c_uint]
u32.AllowSetForegroundWindow.restype = ctypes.c_int
u32.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
u32.ShowWindow.restype = ctypes.c_int
u32.SetFocus.argtypes = [ctypes.c_void_p]
u32.SetFocus.restype = ctypes.c_void_p

GUI_CARETBLINKING = 0x0001
CF_UNICODETEXT = 13
KEYEVENTF_KEYUP = 0x0002

# 少数软件不注册系统 caret，靠类名兜底
EDITABLE_CLASS = ("edit", "richedit", "scintilla", "chrome_widgetwin",
                  "chrome_renderwidgethosthwnd", "windows.ui.core.corewindow",
                  "_wwg", "qwidget", "sunawt", "terracontrol", "mintty", "vanilla",
                  "windowsforms10", "richedit20", "editcontrol")

# 不操作多少毫秒后自动收起贴顶（默认 4 秒）
IDLE_MS = 4000
# 绿闪持续时间
FLASH_MS = 1000


# ---------------------------------------------------------------- 光标/窗口

def has_caret():
    """当前前台线程是否真的有可输入的文本框（真实 caret 或已知可编辑类名）。"""
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
    """把前台焦点切到指定的外部窗口（让 Ctrl+V 打到它身上）。

    现代 Windows 对 SetForegroundWindow 限制很严：后台进程直接调用常常被静默忽略，
    导致 Ctrl+V 打到了 Python 自己的窗口而不是目标程序。这里用三板斧强行拉前台：
    AllowSetForegroundWindow(ANY) + ShowWindow(SW_RESTORE) + AttachThreadInput + SetForegroundWindow + SetFocus。
    """
    if not hwnd:
        return False
    try:
        pid = ctypes.c_ulong()
        tgt = u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        cur = k32.GetCurrentThreadId()
        try:
            u32.AllowSetForegroundWindow(0)  # ASFW_ANY：放行任意进程设置前台
        except Exception:
            pass
        u32.ShowWindow(hwnd, 9)  # SW_RESTORE：先还原（最小化/隐藏的窗口也能拉起）
        u32.AttachThreadInput(cur, tgt, True)
        u32.SetForegroundWindow(hwnd)
        u32.SetFocus(hwnd)
        u32.AttachThreadInput(cur, tgt, False)
        return True
    except Exception:
        return False


# ------------------------------------------------------------------ 剪贴板

def set_clipboard(text):
    """写入系统剪贴板（带重试，避免别的程序临时占用剪贴板导致写入失败）"""
    data = text.encode("utf-16-le") + b"\x00\x00"
    for _ in range(6):
        if u32.OpenClipboard(None):
            try:
                u32.EmptyClipboard()
                h = k32.GlobalAlloc(0x0002, len(data))
                if not h:
                    return False
                p = k32.GlobalLock(h)
                ctypes.memmove(p, data, len(data))
                k32.GlobalUnlock(h)
                if u32.SetClipboardData(CF_UNICODETEXT, h):
                    return True
            finally:
                u32.CloseClipboard()
        time.sleep(0.02)
    return False


def press_ctrl_v():
    def k(vk, up=False):
        pad = (ctypes.c_ubyte * 8)()
        return INPUT(1, KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, 0), pad)
    seq = (INPUT * 4)(k(0x11), k(0x56), k(0x56, True), k(0x11, True))
    ptr = ctypes.cast(ctypes.byref(seq), ctypes.POINTER(INPUT))
    u32.SendInput(4, ptr, ctypes.sizeof(INPUT))


# -------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    app = None

    def do_GET(self):
        # 手机端 Sender.ping() 走 GET /ping；任何 GET 都回 200 表示在线
        self.reply({"ok": True, "service": "keyvoice",
                    "connected": bool(Handler.app and Handler.app.total > 0)})

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
        self.total = 0                 # 累计收到条数
        self.messages = []             # 当前列表中的消息 {id,text,edited,row,label,edit_btn}
        self.last_external = None
        self.top = True                # 是否置顶
        self.docked = False            # 是否已收起贴顶
        self.normal_geom = None        # 收起前窗口位置/尺寸
        self.drag = None               # 拖拽中的消息
        self.drag_ghost = None         # 拖拽时跟随光标的小窗
        self._idle = None             # 空闲计时器

        self.var_status = tk.StringVar(value="监听中（端口 %d）· 等待连接" % port)
        self.var_auto = tk.BooleanVar(value=True)

        root.title("KeyVoice")
        root.attributes("-topmost", True)
        root.resizable(True, True)
        sw = root.winfo_screenwidth()
        root.geometry("%dx420+%d+40" % (min(420, sw - 40), sw - min(420, sw - 40) - 20))
        root.configure(bg=BG)
        try:
            root.wm_attributes("-toolwindow", True)   # 不在任务栏占大块
        except Exception:
            pass

        # 绿闪条（贴在最顶，收到消息时变绿 1 秒）
        self.flash_bar = tk.Frame(root, bg=BORDER, height=3)
        self.flash_bar.pack(fill="x")

        # 顶栏
        hdr = tk.Frame(root, bg=SURFACE)
        hdr.pack(fill="x")
        self.hdr = hdr
        self.title_label = tk.Label(hdr, text="KeyVoice", bg=SURFACE, fg=TEXT,
                                    font=FONT_TITLE)
        self.status_canvas = tk.Canvas(hdr, width=10, height=10, bg=SURFACE, highlightthickness=0)
        self.dot = self.status_canvas.create_oval(0, 0, 10, 10, fill=WARNING)
        self.status_label = tk.Label(hdr, textvariable=self.var_status, bg=SURFACE, fg=TEXT2,
                                     font=FONT_SM, anchor="w")
        self.status_spacer = tk.Label(hdr, text="   ", bg=SURFACE)
        self.btn_top = self._hdr_btn(hdr, "置顶:开", self._toggle_top)
        self.btn_dock = self._hdr_btn(hdr, "收起", self._toggle_dock)
        self.close_btn = self._hdr_btn(hdr, "×", self.close, danger=True)
        self._pack_header()

        # 消息列表（可滚动）
        self.list_frame = tk.Frame(root, bg=BG)
        self.list_frame.pack(fill="both", expand=True, padx=0, pady=0)
        self.canvas = tk.Canvas(self.list_frame, bg=BG, highlightthickness=0)
        self.scroll = tk.Scrollbar(self.list_frame, orient="vertical",
                                   command=self.canvas.yview, bg=SURFACE_HI,
                                   troughcolor=BG, width=10)
        self.list_inner = tk.Frame(self.canvas, bg=BG)
        self.canvas.create_window((0, 0), window=self.list_inner, anchor="nw")
        self.canvas.configure(yscrollcommand=self.scroll.set)
        self.canvas.pack(side="left", fill="both", expand=True, padx=(0, 0))
        self.scroll.pack(side="right", fill="y")
        self.list_inner.bind("<Configure>",
                             lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<MouseWheel>", self._on_wheel)

        # 底部输入框 + 控制条
        self.input_frame = tk.Frame(root, bg=SURFACE)
        self.input_frame.pack(fill="x")
        self.input_box = tk.Text(self.input_frame, height=3, bg=SURFACE_HI, fg=TEXT,
                                 font=FONT_SM, wrap="word", relief="flat",
                                 borderwidth=0, insertbackground=TEXT, padx=8, pady=6)
        self.input_box.pack(fill="x", padx=10, pady=(8, 4))
        bar = tk.Frame(self.input_frame, bg=SURFACE)
        bar.pack(fill="x", padx=10, pady=(0, 8))
        self._btn(bar, "上屏", self._paste_input, "left")
        self._btn(bar, "清空输入框", self._clear_input, "left")
        self._btn(bar, "清空列表", self._clear_list, "left")
        tk.Checkbutton(bar, text="收到即上屏", variable=self.var_auto, bg=SURFACE, fg=TEXT2,
                       selectcolor=SURFACE_HI, activebackground=SURFACE, activeforeground=TEXT,
                       font=FONT_SM, borderwidth=0, highlightthickness=0).pack(side="left", padx=8)
        tk.Label(bar, text="   把消息拖到上面输入框可粘贴", bg=SURFACE, fg=TEXT3,
                 font=FONT_SM).pack(side="right", padx=4)

        root.protocol("WM_DELETE_WINDOW", self.close)
        root.bind("<Configure>", self._on_configure)
        root.bind("<Motion>", self._activity)
        root.bind("<ButtonPress-1>", self._activity)
        root.bind("<KeyPress>", self._activity)
        root.bind("<MouseWheel>", self._activity)
        self._start_focus_tracker()
        self.update_status()
        self._reset_idle()

    # ----------------------------------------------------------- UI 小工具
    @staticmethod
    def _btn(parent, text, cmd, side):
        b = tk.Button(parent, text=text, command=cmd, bg=SURFACE_HI, fg=TEXT,
                      activebackground=PRIMARY_PRESS, activeforeground="#FFFFFF",
                      font=FONT_SM, borderwidth=0, relief="flat", padx=12, pady=5,
                      highlightthickness=0, highlightbackground=BORDER)
        b.pack(side=side, padx=2)
        return b

    def _hdr_btn(self, parent, text, cmd, danger=False):
        b = tk.Button(parent, text=text, command=cmd,
                      bg=SURFACE, fg=(ERROR if danger else TEXT2),
                      activebackground=(ERROR if danger else PRIMARY_PRESS),
                      activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                      relief="flat", padx=10, pady=9,
                      highlightthickness=0, highlightbackground=BORDER)
        b.pack(side="right")
        return b

    def _pack_header(self):
        """按固定顺序重建顶栏（收起/展开后恢复时用，保证部件顺序不变）。"""
        for w in (self.title_label, self.status_canvas, self.status_label,
                  self.status_spacer, self.btn_top, self.btn_dock, self.close_btn):
            w.pack_forget()
        self.title_label.pack(side="left", padx=12, pady=9)
        self.status_canvas.pack(side="left")
        self.status_label.pack(side="left", padx=8, pady=9)
        self.status_spacer.pack(side="left", fill="x", expand=True)
        self.btn_top.pack(side="right")
        self.btn_dock.pack(side="right")
        self.close_btn.pack(side="right")

    # ----------------------------------------------------------- 焦点跟踪
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

    # ----------------------------------------------------------- 收消息
    def receive(self, text):
        self.root.after(0, self._on_receive, text)

    def _on_receive(self, text):
        self.total += 1
        msg = {"id": self.total, "text": text, "edited": False}
        self.messages.append(msg)
        self._render_message(msg)
        self.scroll_to_bottom()
        self.update_status()

        # 收到消息：若已收起则展开，并绿闪一下
        if self.docked:
            self.undock()
        self.flash_green()

        ok = set_clipboard(text)
        if not ok:
            self.toast("剪贴板被占用，写入失败（请稍后再试）")
            return
        if self.var_auto.get() and self._paste_to_foreign():
            self.toast("已上屏到当前光标处")
        else:
            self.toast("已存剪贴板（点一下目标输入框，再发即可 Ctrl+V）")

    # ----------------------------------------------------------- 消息行渲染
    def _render_message(self, msg):
        row = tk.Frame(self.list_inner, bg=SURFACE,
                       highlightbackground=BORDER, highlightthickness=1)
        row.pack(fill="x", padx=8, pady=4)
        label = tk.Label(row, text=msg["text"], bg=SURFACE, fg=TEXT,
                         font=FONT_SM, anchor="w", justify="left",
                         wraplength=300, cursor="hand2")
        label.pack(side="left", fill="both", expand=True, padx=8, pady=8)
        btns = tk.Frame(row, bg=SURFACE)
        btns.pack(side="right", padx=4, pady=4)
        b_copy = tk.Button(btns, text="复制", command=lambda m=msg: self._copy_msg(m),
                           bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                           activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                           relief="flat", padx=8, pady=3,
                           highlightthickness=0, highlightbackground=BORDER)
        b_edit = tk.Button(btns, text="编辑",
                           command=lambda: self._edit_msg(msg, row, label, b_edit),
                           bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                           activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                           relief="flat", padx=8, pady=3,
                           highlightthickness=0, highlightbackground=BORDER)
        b_copy.pack(side="top", pady=2)
        b_edit.pack(side="top", pady=2)

        msg["row"] = row
        msg["label"] = label
        msg["edit_btn"] = b_edit

        # 拖拽：在文字上按住拖到输入框松手即粘贴
        label.bind("<ButtonPress-1>", lambda e, m=msg: self._start_drag(e, m))
        label.bind("<B1-Motion>", lambda e, m=msg: self._drag_motion(e, m))
        label.bind("<ButtonRelease-1>", lambda e, m=msg: self._end_drag(e, m))

    def _copy_msg(self, msg):
        if set_clipboard(msg["text"]):
            self.toast("已复制这条文字")
        else:
            self.toast("剪贴板被占用")

    def _edit_msg(self, msg, row, label, btn):
        # 行内编辑：把文字标签换成可编辑 Text，按钮变“保存”
        text = tk.Text(row, height=3, bg=SURFACE_HI, fg=TEXT, font=FONT_SM,
                       wrap="word", relief="flat", borderwidth=0,
                       insertbackground=TEXT, padx=8, pady=6)
        text.insert("1.0", msg["text"])
        label.pack_forget()
        text.pack(in_=row, side="left", fill="both", expand=True, padx=8, pady=6)
        btn.configure(text="保存", command=lambda: self._save_edit(msg, row, label, text, btn))

    def _save_edit(self, msg, row, label, text, btn):
        new = text.get("1.0", "end-1c")
        msg["text"] = new
        msg["edited"] = True
        text.pack_forget()
        label.configure(text=new)
        label.pack(in_=row, side="left", fill="both", expand=True, padx=8, pady=8)
        btn.configure(text="编辑", command=lambda: self._edit_msg(msg, row, label, btn))
        self.toast("已保存修改")

    # ----------------------------------------------------------- 拖拽到输入框
    def _start_drag(self, event, msg):
        self.drag = msg
        if self.drag_ghost:
            self.drag_ghost.destroy()
        g = tk.Toplevel(self.root)
        g.wm_overrideredirect(True)
        g.attributes("-topmost", True)
        snippet = msg["text"]
        if len(snippet) > 40:
            snippet = snippet[:40] + "…"
        tk.Label(g, text=snippet, bg=SURFACE_HI, fg=TEXT, font=FONT_SM,
                 padx=10, pady=6).pack()
        g.geometry("+%d+%d" % (event.x_root + 12, event.y_root + 12))
        self.drag_ghost = g

    def _drag_motion(self, event, msg):
        if self.drag_ghost:
            self.drag_ghost.geometry("+%d+%d" % (event.x_root + 12, event.y_root + 12))

    def _end_drag(self, event, msg):
        if self.drag_ghost:
            self.drag_ghost.destroy()
            self.drag_ghost = None
        self.drag = None
        # 松手点是否落在输入框范围内
        ix, iy = self.input_box.winfo_rootx(), self.input_box.winfo_rooty()
        iw, ih = self.input_box.winfo_width(), self.input_box.winfo_height()
        if ix <= event.x_root <= ix + iw and iy <= event.y_root <= iy + ih:
            self.input_box.insert("insert", msg["text"])
            self.toast("已粘贴到输入框")

    # ----------------------------------------------------------- 上屏 / 清空
    def _paste_to_foreign(self):
        """上屏到外部应用的输入框：优先“最近使用的外部窗口”，其次当前前台窗口。
        拉到前台后先用 has_caret() 确认真的有光标，再 Ctrl+V（修复“收到却没录入”）。"""
        tgt = None
        if self.last_external and self._foreign(self.last_external):
            tgt = self.last_external
        fg = u32.GetForegroundWindow()
        if tgt is None and self._foreign(fg):
            tgt = fg
        if tgt is None:
            return False
        for _ in range(4):
            focus_external(tgt)
            time.sleep(0.08)
            cur = u32.GetForegroundWindow()
            if self._foreign(cur):
                break
        time.sleep(0.05)
        if not has_caret():            # ← 关键修复：没光标就别瞎打
            return False
        press_ctrl_v()
        return True

    def _paste_input(self):
        text = self.input_box.get("1.0", "end-1c")
        if not text.strip():
            self.toast("输入框为空")
            return
        set_clipboard(text)
        if self._paste_to_foreign():
            self.toast("已上屏（输入框内容）")
        else:
            self.toast("已复制到剪贴板（手动 Ctrl+V）")

    def _clear_input(self):
        self.input_box.delete("1.0", "end")

    def _clear_list(self):
        for m in self.messages:
            if "row" in m and m["row"].winfo_exists():
                m["row"].destroy()
        self.messages.clear()
        self.update_status()
        self.toast("已清空消息列表")

    # ----------------------------------------------------------- 置顶 / 收起
    def _toggle_top(self):
        self.top = not self.top
        self.root.attributes("-topmost", self.top)
        self.btn_top.configure(text="置顶:开" if self.top else "置顶:关")
        self._reset_idle()

    def _toggle_dock(self):
        if self.docked:
            self.undock()
        else:
            self.dock()

    def dock(self):
        """收起贴到屏幕顶部：只留一条细窄的顶栏（保持当前水平位置，不跳到居中）。"""
        if self.docked:
            return
        self.normal_geom = self.root.geometry()
        x = self.root.winfo_x()
        sw = self.root.winfo_screenwidth()
        # 只隐藏“中间偏宽”的部件（状态/置顶），标题与收起/关闭保留，bar 更窄也不塌陷
        self.status_canvas.pack_forget()
        self.status_label.pack_forget()
        self.status_spacer.pack_forget()
        self.btn_top.pack_forget()
        self.list_frame.pack_forget()
        self.input_frame.pack_forget()
        self.flash_bar.configure(height=0)   # 收起时隐藏绿闪条，避免额外高度
        hh = self.hdr.winfo_height() or 38
        w = 220
        if x < 0 or x > sw - w:
            x = max(0, (sw - w) // 2)
        self.root.geometry("%dx%d+%d+0" % (w, hh, x))
        self.docked = True
        self.btn_dock.configure(text="展开")

    def undock(self):
        """从贴顶状态恢复成正常窗口。"""
        if not self.docked:
            return
        if self.normal_geom:
            self.root.geometry(self.normal_geom)
        self.flash_bar.configure(height=3)
        self._pack_header()   # 按正确顺序恢复顶栏所有部件
        self.list_frame.pack(fill="both", expand=True, padx=0, pady=0)
        self.input_frame.pack(fill="x")
        self.docked = False
        self.btn_dock.configure(text="收起")
        self.scroll_to_bottom()
        self._reset_idle()

    # ----------------------------------------------------------- 空闲计时
    def _activity(self, *a):
        if self.docked:
            # 用户碰了收起的小条，先展开
            self.undock()
        self._reset_idle()

    def _reset_idle(self):
        if self._idle:
            try:
                self.root.after_cancel(self._idle)
            except Exception:
                pass
        self._idle = self.root.after(IDLE_MS, self.dock)

    # ----------------------------------------------------------- 绿闪
    def flash_green(self):
        self.flash_bar.configure(bg=SUCCESS)
        self.root.after(FLASH_MS, lambda: self.flash_bar.configure(bg=BORDER))

    # ----------------------------------------------------------- 滚动 / 适配
    def scroll_to_bottom(self):
        self.canvas.update_idletasks()
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        self.canvas.yview_moveto(1.0)

    def _on_wheel(self, event):
        if self.docked:
            return
        self.canvas.yview_scroll(-1 * (event.delta // 120), "units")

    def _on_configure(self, event):
        # 窗口宽度变化时，让每条消息文字按新宽度折行
        if event.widget != self.root:
            return
        w = self.list_inner.winfo_width() - 96
        if w < 60:
            w = 60
        for m in self.messages:
            if "label" in m and m["label"].winfo_exists():
                m["label"].configure(wraplength=w)

    # ----------------------------------------------------------- 状态 / 提示
    def toast(self, msg):
        self.var_status.set("已收到 %d 条 · %s" % (self.total, msg))
        self.root.after(2500, self.update_status)

    def update_status(self):
        if self.total == 0:
            self.var_status.set("监听中（端口 %d）· 等待连接" % self.port)
            self.status_canvas.itemconfig(self.dot, fill=WARNING)
        else:
            self.var_status.set("已连接 · 收到 %d 条 · 端口 %d" % (self.total, self.port))
            self.status_canvas.itemconfig(self.dot, fill=SUCCESS)

    def close(self):
        try:
            self.root.destroy()
        finally:
            raise SystemExit(0)


def main():
    try:  # pythonw（无控制台后台运行）下 stdout 为 None，print 会抛错
        print("KeyVoice 接收端  消息列表窗口 右上角置顶  端口 %d" % PORT)
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
