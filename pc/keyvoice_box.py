#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · Windows 接收端（消息列表版，深色设计系统）

- 常驻置顶窗口，支持拖动调节大小；QQ 式贴边：鼠标离开窗口即完全收起（屏幕上不留痕迹），
  鼠标移到屏幕顶部「原位置」热区时窗口重新出现；收到消息时绿闪并展开。
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
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890
# 手机端消息接收端口（LanServer.java 同款协议：POST /text {"text":"..."}）
PHONE_SRV_PORT = 17892
# 最近一次 UDP 发现的手机 IP（供「发到手机」直连用）
PHONE_ADDR = {"ip": None}

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

class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

u32.GetCursorPos.argtypes = [ctypes.POINTER(POINT)]
u32.GetCursorPos.restype = ctypes.c_int

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
u32.IsIconic.argtypes = [ctypes.c_void_p]
u32.IsIconic.restype = ctypes.c_int
u32.GetParent.argtypes = [ctypes.c_void_p]
u32.GetParent.restype = ctypes.c_void_p
u32.GetWindowRect.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_long * 4)]
u32.GetWindowRect.restype = ctypes.c_int

GUI_CARETBLINKING = 0x0001
CF_UNICODETEXT = 13
KEYEVENTF_KEYUP = 0x0002

# 少数软件不注册系统 caret，靠类名兜底
EDITABLE_CLASS = ("edit", "richedit", "scintilla", "chrome_widgetwin",
                  "chrome_renderwidgethosthwnd", "windows.ui.core.corewindow",
                  "_wwg", "qwidget", "sunawt", "terracontrol", "mintty", "vanilla",
                  "windowsforms10", "richedit20", "editcontrol")

# 不操作多少毫秒后自动收起贴顶（保留常量；QQ 式贴边改为「鼠标离开即收」，不再使用空闲计时）
IDLE_MS = 4000
# 绿闪持续时间
FLASH_MS = 1000
# 收起(隐藏)后，鼠标移到屏幕顶部这一热区（y<=REVEAL_PX 且落在该窗口水平范围）即自动展开（QQ 式贴边）
REVEAL_PX = 6
# 「离开即收」的边缘宽容：窗口四周这么多像素内都算“还在窗口上”。
# 否则用户想拖下边缘/右下角调节窗口大小（鼠标必须贴到边界）时，
# 鼠标刚碰到边界窗口就被收起，根本没法调高度。
EDGE_GRACE = 24
# 窗口尺寸刚变化后这段秒数内不收起（正在拖拽缩放的宽容期）
RESIZE_GRACE_S = 1.2


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
    AllowSetForegroundWindow(ANY) + 必要时还原最小化窗口 + AttachThreadInput + SetForegroundWindow + SetFocus。

    ⚠ 关键：**不能无条件调用 ShowWindow(SW_RESTORE)**。对「最大化」的窗口调用 SW_RESTORE
    会把它打回“还原”尺寸——用户看到的现象就是「收到消息时前台软件像被双击了标题栏一样
    从最大化变成还原」。所以只在窗口真的最小化（IsIconic）时才还原它。
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
        try:
            if u32.IsIconic(hwnd):
                u32.ShowWindow(hwnd, 9)      # SW_RESTORE：只救最小化的窗口，不动最大化/正常窗口
        except Exception:
            pass
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
        from_name = ""
        try:
            o = json.loads(raw.decode("utf-8"))
            text = o.get("text", "")
            from_name = o.get("from", "")
        except Exception:
            pass
        if text and Handler.app:
            Handler.app.receive(text, from_name)
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
                    PHONE_ADDR["ip"] = addr[0]   # 记住手机 IP，供「发到手机」直连
                    s.sendto(("KEYVOICE_HELLO:%d" % port).encode("utf-8"), addr)
            except socket.timeout:
                continue
            except Exception:
                break
    except Exception:
        pass


def my_ip():
    """本机局域网 IP（用于发现自己，避免把自己的应答当成别的设备）"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return ""


def discover_lan(timeout=0.8):
    """广播 KV_DISCOVER，收集局域网内的 KeyVoice 设备（手机+电脑）。
    返回 [(ip, kind, name)]，kind: 'phone' / 'pc'"""
    found = {}
    me = my_ip()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.settimeout(0.15)
        # 子网定向广播（Windows 常禁止发 255.255.255.255）+ 全网广播双保险
        me = my_ip()
        subnet_bc = (me.rsplit(".", 1)[0] + ".255") if me else ""
        for _ in range(4):
            try:
                s.sendto(b"KV_DISCOVER", ("255.255.255.255", 17893))
            except Exception:
                pass
            if subnet_bc:
                try:
                    s.sendto(b"KV_DISCOVER", (subnet_bc, 17893))
                except Exception:
                    pass
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = s.recvfrom(256)
                msg = data.decode("utf-8", "ignore")
                if addr[0] == me:
                    continue
                if msg.startswith("KV_PHONE_HERE:"):
                    found[addr[0]] = ("phone", msg.split(":", 1)[1])
                elif msg.startswith("KV_PC_HERE:"):
                    found.setdefault(addr[0], ("pc", msg.split(":", 1)[1]))
            except socket.timeout:
                continue
            except Exception:
                break
        s.close()
    except Exception:
        pass
    return [(ip, k, n) for ip, (k, n) in found.items()]


def lan_discover_responder():
    """应答别的设备的发现广播（PC ↔ PC 互发现）"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", 17893))
        s.settimeout(0.5)
        name = socket.gethostname() or "PC"
        while True:
            try:
                data, addr = s.recvfrom(256)
                if data.startswith(b"KV_DISCOVER") and addr[0] != my_ip():
                    out = ("KV_PC_HERE:" + name).encode("utf-8")
                    s.sendto(out, addr)
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
        self.dock_x = 0                # 收起前窗口的 x（顶部热区水平范围用）
        self.dock_w = 360              # 收起前窗口的宽
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
        # 限制最小尺寸：防止缩得太小时消息列表被挤没（贴顶收起时会临时解除）
        root.minsize(300, 240)
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
        self.clock = tk.Label(hdr, text="", bg=SURFACE, fg=TEXT2, font=FONT_SM)
        self.clock.bind("<Button-1>", self._hdr_click)   # 贴顶时点击横栏展开
        self._pack_header()
        # 贴顶时点击横栏（标题处）展开
        hdr.bind("<Button-1>", self._hdr_click)
        self.title_label.bind("<Button-1>", self._hdr_click)
        self._tick_clock()

        # 消息列表（可滚动）
        self.list_frame = tk.Frame(root, bg=BG)
        self.list_frame.pack(fill="both", expand=True, padx=0, pady=0)
        self.canvas = tk.Canvas(self.list_frame, bg=BG, highlightthickness=0)
        self.scroll = tk.Scrollbar(self.list_frame, orient="vertical",
                                   command=self.canvas.yview, bg=SURFACE_HI,
                                   troughcolor=BG, width=10)
        self.list_inner = tk.Frame(self.canvas, bg=BG)
        self.canvas_win = self.canvas.create_window((0, 0), window=self.list_inner, anchor="nw")
        # 让内层 Frame 宽度始终撑满画布 → 消息行充满整个窗口宽度（否则只是内容宽的一块）
        self.canvas.bind("<Configure>",
                         lambda e: self.canvas.itemconfigure(self.canvas_win, width=e.width))
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
        # 回车直接群发（不需要点按钮）
        self.input_box.bind("<Return>", self._on_return)
        bar = tk.Frame(self.input_frame, bg=SURFACE)
        bar.pack(fill="x", padx=10, pady=(0, 8))
        self._btn(bar, "上屏", self._paste_input, "left")
        self._btn(bar, "发到手机", self.send_to_phone, "left")
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

        # 贴顶态辅助：量出系统标题栏高度 / 预览条 / 悬停展开
        root.update_idletasks()
        self.titlebar_h = max(24, root.winfo_rooty() - root.winfo_y())
        self._hover_inside = False
        self._peeking = False
        self._armed = False            # 鼠标「进过窗口」后才允许自动收起（防止刚启动就消失）
        self._last_geom_change = 0.0   # 最近一次窗口尺寸变化的时间（调节大小期间不收起的宽容期）
        self._last_wh = None           # 最近一次根窗口尺寸，用于识别“尺寸真的变了”
        self._no_dock_until = time.time() + 2.0   # 启动宽限：刚起来这 2 秒先不做“离开即收”
        self.peek_label = tk.Label(root, bg=SUCCESS, fg="#062B12",
                                   font=("Microsoft YaHei", 10, "bold"),
                                   anchor="w", padx=10, pady=5)
        self._poll_hover()

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
    def receive(self, text, from_name=""):
        self.root.after(0, self._on_receive, text, from_name)

    def _on_receive(self, text, from_name=""):
        self.total += 1
        msg = {"id": self.total, "text": text, "from": from_name or "", "edited": False}
        self.messages.append(msg)
        self._render_message(msg)
        self.scroll_to_bottom()
        self.update_status()

        # 展开态：绿闪条；收起(隐藏)态：不弹任何可见提示，鼠标移到顶部才显示（QQ 式贴边）
        if not self.docked:
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
        """仿微信/QQ 聊天布局：圆头像 + 名称 + 气泡；右侧固定复制/编辑按钮"""
        who = msg.get("from") or "电脑"
        row = tk.Frame(self.list_inner, bg=BG)
        row.pack(fill="x", padx=8, pady=4)
        # 【先 pack 右侧按钮】再 pack 头像/气泡：窗口变窄时按钮不会被挤没
        btns = tk.Frame(row, bg=BG)
        btns.pack(side="right", padx=4, pady=4)
        b_copy = tk.Button(btns, text="复制", command=lambda m=msg: self._copy_msg(m),
                           bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                           activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                           relief="flat", padx=8, pady=3,
                           highlightthickness=0, highlightbackground=BORDER)
        b_edit = tk.Button(btns, text="编辑",
                           command=lambda: self._edit_msg(msg, row, msg["label"], b_edit))
        for b in (b_copy, b_edit):
            b.configure(bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                        activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                        relief="flat", padx=8, pady=3,
                        highlightthickness=0, highlightbackground=BORDER)
        b_copy.pack(side="top", pady=2)
        b_edit.pack(side="top", pady=2)
        # 圆形头像（取设备名首字）
        av = tk.Canvas(row, width=36, height=36, bg=BG, highlightthickness=0)
        av.create_oval(0, 0, 36, 36, fill=PRIMARY, outline="")
        av.create_text(18, 18, text=who[:1], fill="#FFFFFF", font=FONT_SM)
        av.pack(side="left", anchor="n", padx=(0, 8), pady=2)
        # 气泡（设备名小字 + 正文）
        bubble = tk.Frame(row, bg=SURFACE, highlightbackground=BORDER, highlightthickness=1)
        bubble.pack(side="left", fill="both", expand=True)
        tk.Label(bubble, text=who, bg=SURFACE, fg=TEXT3,
                 font=("Microsoft YaHei", 8)).pack(anchor="w", padx=8, pady=(4, 0))
        label = tk.Label(bubble, text=msg["text"], bg=SURFACE, fg=TEXT,
                         font=FONT_SM, anchor="w", justify="left",
                         wraplength=300, cursor="hand2")
        label.pack(side="left", fill="both", expand=True, padx=8, pady=8)

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
        label.pack(in_=label.master, side="left", fill="both", expand=True, padx=8, pady=8)
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
        text = msg["text"]
        # 落在 PC 自己的输入框内：直接插入
        ix, iy = self.input_box.winfo_rootx(), self.input_box.winfo_rooty()
        iw, ih = self.input_box.winfo_width(), self.input_box.winfo_height()
        if ix <= event.x_root <= ix + iw and iy <= event.y_root <= iy + ih:
            self.input_box.insert("insert", text)
            self.toast("已粘贴到输入框")
            return
        # 落在电脑上任意外部输入框：聚焦光标下的窗口并 Ctrl+V 粘贴（拖到哪粘到哪）
        if self._paste_external_at(event.x_root, event.y_root, text):
            self.toast("已粘贴到外部输入框")
        else:
            set_clipboard(text)
            self.toast("已复制到剪贴板（可手动 Ctrl+V）")

    def _paste_external_at(self, x, y, text):
        """把消息拖到电脑任意输入框松手：取光标下窗口、聚焦并 Ctrl+V 粘贴。"""
        try:
            u32.WindowFromPoint.argtypes = [POINT]
            u32.WindowFromPoint.restype = ctypes.c_void_p
            hwnd = u32.WindowFromPoint(POINT(int(x), int(y)))
        except Exception:
            return False
        if not hwnd or not self._foreign(hwnd):
            return False
        if not set_clipboard(text):
            return False
        focus_external(hwnd)
        time.sleep(0.12)
        press_ctrl_v()
        return True

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

    # ------------------------------------------------------ 发到多设备（群发）
    def send_to_phone(self):
        """把输入框内容群发给局域网内所有 KeyVoice 设备（手机+电脑）。
        UDP 发现优先（17893），未发现的手机走 adb forward 兜底。"""
        text = self.input_box.get("1.0", "end-1c")
        if not text.strip():
            self.toast("输入框为空")
            return

        def work():
            ok, msg = self._send_all(text)

            def done():
                self.toast(msg)
                if ok:
                    self.input_box.delete("1.0", "end")   # 发送成功清空输入框

            self.root.after(0, done)

        threading.Thread(target=work, daemon=True).start()

    def _send_all(self, text):
        from_name = socket.gethostname() or "PC"
        body = json.dumps({"text": text, "from": from_name}).encode("utf-8")
        targets, seen = [], set()
        for ip, kind, name in discover_lan():
            if ip in seen:
                continue
            seen.add(ip)
            port = PHONE_SRV_PORT if kind == "phone" else PORT
            targets.append(("http://%s:%d/text" % (ip, port), name or ip, None))
        if PHONE_ADDR["ip"] and PHONE_ADDR["ip"] not in seen:
            seen.add(PHONE_ADDR["ip"])
            targets.append(("http://%s:%d/text" % (PHONE_ADDR["ip"], PHONE_SRV_PORT),
                            "手机(记忆)", None))
        # adb 直连的所有设备：逐台 forward 到不同本地端口（多台手机同时连时也能群发）
        try:
            out = subprocess.run(["adb", "devices"], capture_output=True,
                                 timeout=6).stdout.decode("utf-8", "ignore")
            i = 0
            for line in out.splitlines():
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    serial = parts[0]
                    local_port = PHONE_SRV_PORT + 1 + i
                    subprocess.run(["adb", "-s", serial, "forward",
                                    "tcp:%d" % local_port, "tcp:%d" % PHONE_SRV_PORT],
                                   capture_output=True, timeout=6)
                    url = "http://127.0.0.1:%d/text" % local_port
                    if url not in [t[0] for t in targets]:
                        targets.append((url, "adb:" + serial[-4:], None))
                    i += 1
        except Exception:
            pass
        if not targets:
            return False, "局域网内没有发现任何 KeyVoice 设备"
        ok_n, results, last = 0, [], "无可用通道"
        for url, name, need_adb in targets:
            try:
                if need_adb:
                    subprocess.run(["adb", "forward", "tcp:%d" % PHONE_SRV_PORT,
                                    "tcp:%d" % PHONE_SRV_PORT], capture_output=True, timeout=6)
                req = urllib.request.Request(
                    url, data=body,
                    headers={"Content-Type": "application/json; charset=utf-8"})
                r = urllib.request.urlopen(req, timeout=2.5)
                d = json.loads(r.read().decode("utf-8"))
                ok_n += 1
                results.append(name if d.get("delivered", True) else name + "(暂存)")
            except Exception as e:
                last = "%s: %s" % (name, e)
        if ok_n == 0:
            return False, "全部发送失败：" + last
        return True, "已发给 %d 台设备：%s" % (ok_n, "、".join(results))

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
        """收起贴顶（QQ 式）：整个窗口完全隐藏，屏幕上不留任何痕迹（去掉贴边时的黑色边框）；
        只有把鼠标移到屏幕顶部“原位置”热区，窗口才会重新出现。"""
        if self.docked:
            return
        self.normal_geom = self.root.geometry()
        # 记下当前 x/宽，作为顶部热区的水平范围（只在该窗口原本的水平位置附近才展开）
        try:
            self.dock_x = self.root.winfo_x()
            self.dock_w = self.root.winfo_width()
        except Exception:
            pass
        self.docked = True
        self.root.withdraw()                  # 彻底隐藏，无边框、无残影

    def undock(self):
        """从收起态恢复成正常窗口（标题行、列表、输入框全部回来）。

        注意：`withdraw()` 之后**必须 `deiconify()`** 才能重新显示——
        只设 geometry() 的窗口仍然是 withdrawn 状态（实测 ismapped=0），
        这正是之前「点开/悬停都出不来、看起来像闪退」的根因。
        """
        if not self.docked:
            return
        if self.normal_geom:
            self.root.geometry(self.normal_geom)
        self.root.deiconify()                 # ← 关键修复：把窗口真正显示出来
        try:
            self.root.lift()
            if self.top:
                self.root.attributes("-topmost", True)
        except Exception:
            pass
        self.root.minsize(300, 240)           # 恢复最小尺寸限制
        self.root.title("KeyVoice")
        self.flash_bar.configure(height=3)
        self.hdr.pack(fill="x")               # 子部件未拆散，重排整个标题行即可
        self.list_frame.pack(fill="both", expand=True, padx=0, pady=0)
        self.input_frame.pack(fill="x")
        self.docked = False
        self._no_dock_until = time.time() + 1.0   # 展开后 1 秒宽限：从顶部热区往下移进窗口不会被立刻收起
        self.btn_dock.configure(text="收起")
        self.scroll_to_bottom()
        self._reset_idle()

    def _hdr_click(self, *a):
        """贴顶状态下点击横栏任意位置 → 展开（悬停展开为主，此处兜底）。"""
        if self.docked:
            self.undock()

    def _on_return(self, event):
        """输入框内按回车 → 群发给所有设备（不换行）"""
        self.send_to_phone()
        return "break"

    def _window_rect(self):
        """窗口在屏幕上的【真实】矩形（含标题栏与边框）。

        判断“鼠标还在不在窗口上”必须用它，不能用 Tk 的 winfo_x/y/width/height——
        后者是客户区，和用户实际看到/拖拽的边框对不上（实测窗口底边在 499，
        而 winfo 算出来只有 460，标题栏差 39px）。用错的结果就是：
        用户想拖下边缘调节高度时，鼠标其实已经在判定框之外 → 窗口立刻收起。
        """
        try:
            hwnd = self.root.winfo_id()
            top = u32.GetParent(hwnd)
            r = (ctypes.c_long * 4)()
            if u32.GetWindowRect(top if top else hwnd, ctypes.byref(r)):
                return r[0], r[1], r[2], r[3]
        except Exception:
            pass
        return None

    def _poll_hover(self):
        """QQ 式贴边：
        ① 收起(隐藏)时，鼠标移到屏幕顶部“原位置”热区（y<=REVEAL_PX 且 x 在该窗口水平范围）→ 自动展开；
        ② 展开态鼠标离开窗口 → 立即折叠回顶部。

        四条护栏（既避免“看起来像闪退”，也避免“贴着边缘调大小时被收起”）：
        · `_armed`：鼠标**进过窗口**之后才允许自动收起——否则刚启动、鼠标本来就不在窗口上时
          会立刻 withdraw，窗口一闪就没（用户说的“闪退”）。
        · `EDGE_GRACE`：窗口（含边框）外 24px 内都算“还在窗口上”，拖边缘调节大小不会被收起。
        · `approaching`：窗口正上方那条竖直带是“从顶部热区往下移进窗口”的必经之路，不收起。
        · `_no_dock_until`：展开后/启动初期的一小段宽限期，避免判定抖动导致来回闪。
        """
        try:
            pt = POINT()
            u32.GetCursorPos(ctypes.byref(pt))
            if self.docked:
                if pt.y <= REVEAL_PX and (self.dock_x - 24 <= pt.x <= self.dock_x + self.dock_w + 24):
                    self.undock()
            else:
                rect = self._window_rect()
                if rect:
                    x1, y1, x2, y2 = rect          # 真实窗口矩形（含标题栏/边框）
                else:
                    x1 = self.root.winfo_x()
                    y1 = self.root.winfo_y()
                    x2 = x1 + self.root.winfo_width()
                    y2 = y1 + self.root.winfo_height()
                g = EDGE_GRACE
                inside = (x1 - g <= pt.x <= x2 + g and y1 - g <= pt.y <= y2 + g)
                if inside:
                    self._armed = True
                    self._no_dock_until = time.time() + 0.4   # 在窗口上 → 持续续期，绝不被收起
                else:
                    # 窗口水平范围内、窗口底边以上的区域 = 从热区进来的必经之路 → 不收
                    approaching = (x1 - g <= pt.x <= x2 + g and pt.y <= y2)
                    # 刚拖拽过窗口大小的一小段时间内也不收
                    just_resized = (time.time() - self._last_geom_change) < RESIZE_GRACE_S
                    if (self._armed and not approaching and not just_resized
                            and time.time() >= self._no_dock_until):
                        self.dock()       # 鼠标真的离开了消息框 → 立即折叠到顶部
        except Exception:
            pass
        self.root.after(150, self._poll_hover)

    def _tick_clock(self):
        """贴顶时把当前时间并入系统标题栏文字（每秒刷新）。"""
        try:
            if self.docked:
                self.root.title("KeyVoice · " + time.strftime("%H:%M:%S"))
        except Exception:
            pass
        self.root.after(1000, self._tick_clock)

    # ------------------------------------------------------ 贴顶来消息特效
    def _peek(self, text):
        """贴顶时来消息：标题栏下方展开一条绿色预览条（动态特效），2.5 秒后自动收回。
        期间鼠标悬停仍可立即展开完整窗口。"""
        self._peeking = True
        self.flash_bar.configure(height=4, bg=SUCCESS)
        self.peek_label.configure(text="📩 " + (text[:36] + "…" if len(text) > 36 else text))
        self.peek_label.pack(fill="x")
        hh = self.titlebar_h + 4 + self.peek_label.winfo_reqheight()
        self.root.geometry("%dx%d+%d+0" % (self.root.winfo_width(), hh, self.root.winfo_x()))
        self.root.after(2500, self._peek_hide)

    def _peek_hide(self):
        self._peeking = False
        try:
            self.peek_label.pack_forget()
        except Exception:
            pass
        self.flash_bar.configure(height=0, bg=BORDER)
        if self.docked:
            self.root.geometry("%dx%d+%d+0" % (self.root.winfo_width(), self.titlebar_h,
                                               self.root.winfo_x()))

    # ----------------------------------------------------------- 空闲计时
    def _activity(self, *a):
        # 贴顶时鼠标划过不再自动展开（避免想看时钟时横栏闪开）；点击横栏才展开
        self._reset_idle()

    def _reset_idle(self):
        """空闲计时器：QQ 式贴边下，「收起」完全由鼠标离开窗口驱动，
        不再用空闲计时自动隐藏——否则用户盯着消息看（鼠标没动）几秒后窗口会突然消失，
        与「刚启动就没了」一起被误认为闪退。"""
        if self._idle:
            try:
                self.root.after_cancel(self._idle)
            except Exception:
                pass
            self._idle = None

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
        # 注意：root 上的 bind 会收到【所有子控件】的 <Configure>（bindtags 冒泡），
        # 只有根窗口自身【尺寸真的变了】才算“刚缩放过”。
        # 否则任何子控件重排（状态文字变化、消息行增删…）都会一直刷新宽容期，导致永远不收起。
        if event.widget != self.root:
            return
        wh = (event.width, event.height)
        if wh != self._last_wh:
            self._last_wh = wh
            self._last_geom_change = time.time()
        # 窗口宽度变化时，让每条消息文字按新宽度折行
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
    # 应答其它设备的发现广播（多设备互发现）
    threading.Thread(target=lan_discover_responder, daemon=True).start()

    root = tk.Tk()
    app = Box(root, PORT)
    threading.Thread(target=serve, args=(PORT, app), daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
