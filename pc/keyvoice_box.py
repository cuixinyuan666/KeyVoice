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
- 【文件互传】手机 ↔ 电脑，任意类型文件：
    · 手机 → 电脑：POST /file（手机 App「文件」页选文件即传），落盘到收件目录
      （默认 %USERPROFILE%\\Downloads\\KeyVoice），列表里出现文件行，可一键打开。
    · 电脑 → 手机：底部「发文件」→ 选文件 → 传给局域网/adb 发现的手机
      （手机端 App 保存到 Download/KeyVoice）。协议形态参考 LocalSend（单请求直传）。
- 【手机控电脑 · 网页远控】顶部「远控」按钮开/关：内置远控服务器（默认端口 17894，
  见 kv_remote.py，MJPEG + SendInput，架构参考 noVNC/Guacamole「浏览器即客户端」）。
  手机 App「远控」页直接用 WebView 打开即可看到本机屏幕并操作，零安装。
- 【电脑控手机】集成 scrcpy（151k★，ADB 通道，无需在手机装任何东西）：
  顶部「手机投屏」按钮一键检测/启动 scrcpy，USB 或无线 adb 均可。
- 局域网自动发现（多设备互发现）：
    · 监听 UDP 17891（老协议）→ 回 KEYVOICE_HELLO:<port>
    · 监听 UDP 17893（新协议）→ 回 KV_PC_HERE:<port>:<设备名>
    · 点「搜索设备」主动广播 17893，列出局域网内所有手机与电脑
- 本机设备名可改（存 %APPDATA%\\KeyVoice\\config.json），会广播给其它终端。
- adb 模式：adb reverse tcp:17890 tcp:17890 后手机发到 127.0.0.1 即可。

配色与 Android 端（res/values/colors.xml / Ui.java）共用同一套深色 token。
用法：  python keyvoice_box.py [端口]      默认 17890
依赖：仅 Python 标准库 + tkinter（无需 pip 安装任何东西）；
      网页远控为可选功能，需要 Pillow（界面里点「远控」会给出安装引导）。
"""

import ctypes
import http.client
import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from tkinter import filedialog, messagebox
from urllib.parse import quote as urlquote

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 17890
# 手机端消息接收端口（LanServer.java 同款协议：POST /text {"text":"..."}）
PHONE_SRV_PORT = 17892
# 最近一次 UDP 发现的手机 IP（供「发到手机」直连用）
PHONE_ADDR = {"ip": None}
# 局域网发现端口（新协议：手机与电脑互发现）
DISCOVER_PORT = 17893
# 老协议发现端口（仅电脑应答）
BEACON_PORT = 17891
# 网页远控端口（手机控电脑；见 kv_remote.py）
REMOTE_PORT = 17894

# 把脚本目录加入 sys.path：双击运行 / pythonw 启动时也要能 import 同目录的 kv_remote
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import kv_remote
except Exception:                        # 缺文件时不致命：远控按钮届时提示
    kv_remote = None


# ---------------------------------------------------------------- 运行诊断日志
# 排查「电脑搜不到手机 / 手机发送失败」这类只有用户现场才复现的问题时，
# 界面上的提示太笼统（只有一个「未发现设备」），所以把关键事件写进
# %APPDATA%\KeyVoice\diag.log（只在出错/扫描/收消息时写，量很小，可随时删）。

_DIAG_LOCK = threading.Lock()


def diag(msg):
    try:
        p = os.path.join(os.path.dirname(_config_path() or ""), "diag.log")
        if not os.path.dirname(p):
            return
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with _DIAG_LOCK:
            if os.path.exists(p) and os.path.getsize(p) > 512 * 1024:
                try:
                    os.replace(p, p + ".1")
                except Exception:
                    pass
            with open(p, "a", encoding="utf-8") as f:
                f.write("%s %s\n" % (time.strftime("%m-%d %H:%M:%S"), msg))
    except Exception:
        pass


# ---------------------------------------------------------------- 文件互传工具

def default_recv_dir():
    """收件目录：%USERPROFILE%\\Downloads\\KeyVoice。

    config.json 里可用 "recv_dir" 覆盖（见 load_recv_dir）。"""
    home = os.path.expanduser("~")
    return os.path.join(home, "Downloads", "KeyVoice")


def load_recv_dir():
    p = _config_path()
    if p:
        try:
            with open(p, "r", encoding="utf-8") as f:
                v = (json.load(f).get("recv_dir") or "").strip()
            if v:
                return v
        except Exception:
            pass
    return default_recv_dir()


def ensure_recv_dir():
    d = load_recv_dir()
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        d = default_recv_dir()
        os.makedirs(d, exist_ok=True)
    return d


def safe_filename(name):
    """清洗来自网络的文件名：去路径分隔与非法字符，防目录穿越（../../x）。

    注意不能用 os.path.basename：Windows 上它会先把 "a:b" 里的 a: 当驱动器号，
    把冒号吃掉；这里手动按 / 切分，再把盘符冒号等非法字符统一替换成 _。
    """
    name = (name or "").replace("\\", "/").split("/")[-1]
    bad = '<>:"/\\|?*'
    name = "".join(("_" if c in bad or ord(c) < 32 else c) for c in name).strip(" .")
    if not name:
        name = "file"
    return name[:180]


def unique_path(folder, name):
    """目标文件已存在时按「名字 (1).ext」递增，不覆盖。"""
    base, ext = os.path.splitext(name)
    p = os.path.join(folder, name)
    i = 1
    while os.path.exists(p):
        p = os.path.join(folder, "%s (%d)%s" % (base, i, ext))
        i += 1
    return p


def human_size(n):
    try:
        n = float(n)
    except Exception:
        return "?"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%d %s" if unit == "B" else "%.1f %s") % (n, unit)
        n /= 1024.0
    return "%.1f GB" % n


def _read_exact(rfile, out, total, chunk=262144):
    """按 Content-Length 精确读 total 字节写入 out（流式，内存占用恒定）。"""
    got = 0
    while got < total:
        buf = rfile.read(min(chunk, total - got))
        if not buf:
            break
        out.write(buf)
        got += len(buf)
    return got


def _read_chunked(rfile, out, chunk=262144):
    """解码 HTTP/1.1 Transfer-Encoding: chunked 的请求体（少数客户端不发长度）。"""
    got = 0
    while True:
        line = rfile.readline(64)
        if not line:
            break
        size = int(line.split(b";")[0].strip() or b"0", 16)
        if size == 0:
            while True:                       # 吃掉 trailer
                t = rfile.readline(64)
                if not t or t in (b"\r\n", b"\n"):
                    break
            break
        remain = size
        while remain > 0:
            buf = rfile.read(min(chunk, remain))
            if not buf:
                return got
            out.write(buf)
            got += len(buf)
            remain -= len(buf)
        rfile.readline(8)                     # chunk 结尾 CRLF
    return got


# --------------------------------------------------------- 配置文件（设备名）

def _config_path():
    """配置文件路径。优先 %APPDATA%\\KeyVoice\\config.json，写不了就退回脚本同目录。"""
    candidates = []
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidates.append(os.path.join(appdata, "KeyVoice", "config.json"))
    candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "keyvoice_config.json"))
    for p in candidates:
        d = os.path.dirname(p)
        try:
            os.makedirs(d, exist_ok=True)
            # 试探性打开，确认真的可写
            with open(p, "a", encoding="utf-8"):
                pass
            return p
        except Exception:
            continue
    return None


def load_device_name():
    """读取本机设备名；没有就返回默认（计算机名）。"""
    p = _config_path()
    if p:
        try:
            with open(p, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            name = (cfg.get("device_name") or "").strip()
            if name:
                return name
        except Exception:
            pass
    return socket.gethostname() or "电脑"


def save_device_name(name):
    """持久化本机设备名。存不下也不影响本次会话（内存里已经生效）。"""
    p = _config_path()
    if not p:
        return False
    data = {}
    try:
        with open(p, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        data = {}
    data["device_name"] = (name or "").strip()
    try:
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception:
        return False


# 设备名是全局共享的：应答别的设备发现、发消息署名都用它
DEVICE_NAME = {"name": load_device_name()}

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

    def _route(self):
        from urllib.parse import urlparse, parse_qs
        u = urlparse(self.path)
        return u.path, parse_qs(u.query)

    def do_GET(self):
        # 手机端 Sender.ping() 走 GET /ping；任何 GET 都回 200 表示在线
        self.reply({"ok": True, "service": "keyvoice",
                    "connected": bool(Handler.app and Handler.app.total > 0)})

    def do_POST(self):
        path, qs = self._route()
        if path == "/file":
            self.handle_file(qs)
            return
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
            diag("收到文字 from=%r len=%d" % (from_name, len(text)))
            Handler.app.receive(text, from_name)
        self.reply({"ok": True})

    # ------------------------------------------------------------- 收文件
    def handle_file(self, qs):
        """POST /file?name=<urlencoded>[&from=<设备名>]  请求体 = 文件原始字节。

        协议形态参考 LocalSend 的「单请求直传」：元数据走 query，体是纯字节流，
        分块流式写盘（大文件不占内存），完成后回 {"ok":true,"saved":...,"size":...}。
        """
        name = safe_filename((qs.get("name", [""])[0] or "file"))
        from_name = qs.get("from", [""])[0] or ""
        total = int(self.headers.get("Content-Length") or 0)
        chunked = "chunked" in (self.headers.get("Transfer-Encoding") or "").lower()
        try:
            folder = ensure_recv_dir()
            dest = unique_path(folder, name)
            with open(dest, "wb") as f:
                if chunked:
                    got = _read_chunked(self.rfile, f)
                else:
                    got = _read_exact(self.rfile, f, total)
            if Handler.app:
                Handler.app.receive_file(dest, got, from_name)
            self.reply({"ok": True, "saved": dest, "size": got})
        except Exception as e:
            self.reply({"ok": False, "err": str(e)})

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
    """HTTP 接收服务；绑定失败（端口被占等）不放弃，隔 2 秒重试，避免静默失联。"""
    Handler.app = app
    while True:
        try:
            ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
        except Exception:
            time.sleep(2)


# -------------------------------------------------------- 局域网自动发现（回应）

def udp_listen(port):
    """老协议应答（UDP 17891）：老版本手机只认 KEYVOICE_HELLO:<port>。

    外层 while 是自愈脚手架：端口被别的实例占住、网卡切换导致 bind 失败等情况下
    隔一会儿重试，而不是让线程静默死掉（thread daemon 死了没有任何界面提示）。
    """
    while True:
        try:
            _udp_listen_once(port)
        except Exception as e:
            diag("udp_listen 17891 异常退出，2s 后重启: %r" % (e,))
        time.sleep(2)


def _udp_listen_once(port):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", BEACON_PORT))
        s.settimeout(0.5)
        while True:
            try:
                data, addr = s.recvfrom(256)
            except socket.timeout:
                continue
            except OSError:
                # Windows 上「上一次 sendto 的目标端口不可达」会让下一次 recvfrom
                # 抛 WSAECONNRESET(10054)。以前这里 break，线程就静默退出、socket 却
                # 还占着端口 —— 表现正是「PC 端明明在运行，却永远发现不了手机」。
                time.sleep(0.05)
                continue
            except Exception:
                time.sleep(0.2)
                continue
            try:
                if b"KEYVOICE_DISCOVER" in data:
                    PHONE_ADDR["ip"] = addr[0]   # 记住手机 IP，供「发到手机」直连
                    s.sendto(("KEYVOICE_HELLO:%d" % port).encode("utf-8"), addr)
            except Exception:
                continue
    except Exception:
        pass


def _ips_from_ipconfig():
    """从 ipconfig 输出里补齐本地 IPv4（Windows 专用兜底）。

    getaddrinfo(gethostname()) 在某些机器上只返回 127.0.0.1（主机名没进 DNS/hosts），
    那样算出来的广播地址就会漏掉真实网段 —— 表现是「点了搜索，一台设备都搜不到」。
    ipconfig 是 Windows 上最全的来源，用它兜底。
    """
    out = set()
    try:
        import re
        import subprocess
        out_bytes = subprocess.run(
            ["ipconfig"], capture_output=True, timeout=5,
            creationflags=0x08000000,          # CREATE_NO_WINDOW，不闪黑框
        ).stdout
        for enc in ("gbk", "utf-8"):
            try:
                txt = out_bytes.decode(enc)
                break
            except Exception:
                continue
        else:
            return out
        for m in re.finditer(r"IPv4[^:\uff1a]*[:\uff1a]\s*([0-9.]+)", txt):
            out.add(m.group(1))
    except Exception:
        pass
    return out


def my_ips():
    """本机【所有】IPv4 地址。三个来源取并集，尽量不漏网卡。

    UDP 广播自己也能收到，所以「过滤自己」不能只比一个 IP：机器常有多张网卡
    （WiFi / 有线 / 虚拟机 / VPN），按路由选出来的那个未必就是发广播用的那个。
    只要漏判，设备清单里就会多出一台"自己"。这里收集全部本地地址，命中任一即视为自己。
    """
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    ips |= _ips_from_ipconfig()
    ips.discard("127.0.0.1")
    return ips


def my_ip():
    """本机主 IPv4（用于展示）。取不到返回空串。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return ""


def broadcast_addrs():
    """所有本地网段的定向广播地址，兜底再加 255.255.255.255。

    必须枚举【全部】本地 IP：机器常有多张网卡（WiFi / 有线 / 虚拟机 / VPN），
    只按默认路由算一个网段的话，目标设备若不在该网段就永远收不到广播，
    表现为「点了搜索却一台也搜不到」。
    """
    out = set()
    for ip in my_ips():
        parts = ip.split(".")
        if len(parts) == 4:
            out.add(".".join(parts[:3]) + ".255")
    out.add("255.255.255.255")
    return out


def _parse_hello(msg):
    """解析一条发现应答，返回 (kind, port, name)；不认识返回 None。

    新格式：KV_PC_HERE:<port>:<name> / KV_PHONE_HERE:<port>:<name>
    兼容更老的 build：KV_PC_HERE:<name>（没有端口，走默认端口）
    """
    for kind, tag, default_port in (("pc", "KV_PC_HERE", PORT),
                                    ("phone", "KV_PHONE_HERE", PHONE_SRV_PORT)):
        if msg.startswith(tag):
            rest = msg[len(tag):].lstrip(":").strip()
            if ":" in rest:
                p, _, name = rest.partition(":")
                try:
                    port = int(p.strip())
                except ValueError:
                    port = default_port
                return kind, port, name.strip()
            return kind, default_port, rest
    return None


def discover_lan(timeout=1.2):
    """广播 KV_DISCOVER，收集局域网内的 KeyVoice 设备（手机 + 电脑）。

    返回 [(ip, kind, name, port)]，kind: 'phone' / 'pc'
    """
    found = {}
    mine = my_ips()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.settimeout(0.15)
        # 每个本地网段都发定向广播（Windows 常禁止发 255.255.255.255）+ 全网广播兜底。
        # 多网卡机器上只发一个网段会漏掉另一网段里的设备。
        bcs = sorted(broadcast_addrs())
        for _ in range(4):
            for ip in bcs:
                try:
                    s.sendto(b"KV_DISCOVER", (ip, DISCOVER_PORT))
                except Exception:
                    pass
            time.sleep(0.05)
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = s.recvfrom(256)
                msg = data.decode("utf-8", "ignore")
                # 排除自己：本机也会收到自己发的广播并应答
                if addr[0] in mine:
                    continue
                parsed = _parse_hello(msg)
                if not parsed:
                    continue
                kind, port, name = parsed
                # 同一台设备可能同时回新老两种报文，优先保留带名字的那条
                prev = found.get(addr[0])
                if prev is None or (not prev[1] and name):
                    found[addr[0]] = (kind, name, port)
            except socket.timeout:
                continue
            except OSError as e:
                # 同一个坑：局域网里【任何一台不跑 KeyVoice 的设备】收到广播都会回
                # ICMP 端口不可达，Windows 把它报到 recvfrom 上（WSAECONNRESET）。
                # 旧代码 break → 整轮扫描直接返回空列表，界面显示「未发现设备」，
                # 而实际上手机早就回包了（只是可能还没读到）。
                diag("discover_lan recvfrom OSError: %r（继续扫描）" % (e,))
                time.sleep(0.02)
                continue
            except Exception as e:
                diag("discover_lan recvfrom error: %r（继续扫描）" % (e,))
                time.sleep(0.02)
                continue
        s.close()
    except Exception as e:
        diag("discover_lan 失败: %r" % (e,))
    return [(ip, k, n, p) for ip, (k, n, p) in found.items()]


def lan_discover_responder():
    """应答别的设备的发现广播（手机 → 本电脑 / 电脑 ↔ 电脑）。

    新协议应答里带上【端口 + 本机设备名】，这样手机和别的电脑扫到的是
    「张三的电脑」这种名字，而不是一串 IP。设备名可在界面上改，立即生效。

    外层 while 是自愈脚手架（同 udp_listen）：绑定失败或线程意外退出后 2 秒重试。
    """
    while True:
        try:
            _lan_discover_responder_once()
        except Exception as e:
            diag("发现应答 17893 异常退出，2s 后重启: %r" % (e,))
        time.sleep(2)


def _lan_discover_responder_once():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", DISCOVER_PORT))
        s.settimeout(0.5)
        while True:
            try:
                data, addr = s.recvfrom(256)
            except socket.timeout:
                continue
            except OSError:
                # 同 udp_listen：ICMP 端口不可达会让 Windows 把错误报到下一次
                # recvfrom 上，必须继续循环而不是退出，否则发现应答永久失效。
                time.sleep(0.05)
                continue
            except Exception:
                time.sleep(0.2)
                continue
            try:
                if data.startswith(b"KV_DISCOVER") and addr[0] not in my_ips():
                    out = ("KV_PC_HERE:%d:%s" % (PORT, DEVICE_NAME["name"])).encode("utf-8")
                    s.sendto(out, addr)
            except Exception:
                continue
    except Exception:
        pass


# ------------------------------------------------------------ 投屏省电看门狗

class MirrorGuard:
    """投屏省电看门狗：黑屏省电 + 鼠标一动自动亮屏可操控。

    <b>实测结论（Android 15 / Flyme，2026-10-16 真机对照实验）</b>：
    · {@code cmd display power-off} 只关显示：画面冻结、输入被丢弃（亮屏同坐标
      点击能打开 App、黑屏同坐标点击无反应）——Android 安全设计，且系统随后
      可能转入 Dozing（画面全黑）。不可用。
    · POWER 键熄屏（Dozing）：输入同样被丢弃、scrcpy 画面全黑。不可用。
    · <b>亮度设 0（本方案）</b>：物理屏幕不发光（OLED 省电≈熄屏），但系统认为
      屏幕仍是 ON + Awake → 【输入注入有效】、【scrcpy 画面实时更新】、
      adb 连接稳定。三项核心需求全部满足。

    <b>策略</b>：投屏启动后把亮度设为 0（黑屏省电），看门狗每 250ms 检测鼠标
    是否进入投屏窗口矩形：一旦进入就恢复原亮度点亮手机，用户即可正常操控。
    鼠标离开窗口超过 idle_off 秒后自动再把亮度设 0，持续省电。
    首次使用时记住原亮度/亮度模式，退出时完整还原。
    """

    def __init__(self, adb, serial, window_title="KeyVoice 手机投屏",
                 idle_off=60.0, poll=0.25):
        self.adb = adb
        self.serial = serial
        self.title = window_title
        self.idle_off = idle_off          # 鼠标离开窗口多久自动黑屏（秒；<=0 禁用）
        self.poll = poll
        self._stop = threading.Event()
        self._thread = None
        self._proc = None
        self._screen_on = True            # 本地记录的亮/黑状态（避免重复 adb 调用）
        self._last_active = 0.0
        self._orig_brightness = None      # 用户原亮度（首次黑屏前读取）
        self._orig_mode = None            # 用户原亮度模式（0 手动 / 1 自动）
        self._u32 = ctypes.windll.user32

    def attach(self, proc):
        self._proc = proc

    def start(self):
        self._thread = threading.Thread(target=self._run,
                                        name="KeyVoiceMirrorGuard", daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    # ---- 内部 ----
    def _settings_get(self, key):
        try:
            out = subprocess.run([self.adb, "-s", self.serial, "shell",
                                  "settings", "get", "system", key],
                                 capture_output=True, timeout=8,
                                 creationflags=0x08000000).stdout.decode().strip()
            return out
        except Exception:
            return ""

    def _settings_put(self, key, value):
        try:
            subprocess.run([self.adb, "-s", self.serial, "shell",
                            "settings", "put", "system", key, str(value)],
                           capture_output=True, timeout=8,
                           creationflags=0x08000000)
            return True
        except Exception:
            return False

    def _read_orig(self):
        if self._orig_brightness is None:
            v = self._settings_get("screen_brightness")
            try:
                self._orig_brightness = max(1, int(v))
            except Exception:
                self._orig_brightness = 128
        if self._orig_mode is None:
            v = self._settings_get("screen_brightness_mode")
            self._orig_mode = v if v in ("0", "1") else "0"

    def _power(self, on):
        """on=True 恢复原亮度（亮屏可操控）；on=False 亮度设 0（黑屏省电）。"""
        if not self.adb:
            return
        if on:
            self._read_orig()
            self._settings_put("screen_brightness_mode", self._orig_mode)
            self._settings_put("screen_brightness", self._orig_brightness)
            self._screen_on = True
        else:
            self._read_orig()
            self._settings_put("screen_brightness_mode", 0)   # 先切手动，否则自动亮度会覆盖
            self._settings_put("screen_brightness", 0)
            self._screen_on = False

    def _cursor_in(self, hwnd):
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        class RECT(ctypes.Structure):
            _fields_ = [("l", ctypes.c_long), ("t", ctypes.c_long),
                        ("r", ctypes.c_long), ("b", ctypes.c_long)]
        p = POINT()
        if not self._u32.GetCursorPos(ctypes.byref(p)):
            return False
        r = RECT()
        self._u32.GetWindowRect(hwnd, ctypes.byref(r))
        return r.l <= p.x <= r.r and r.t <= p.y <= r.b

    def _run(self):
        while not self._stop.is_set():
            try:
                if self._proc is not None and self._proc.poll() is not None:
                    break                       # scrcpy 已退出
                hwnd = self._u32.FindWindowW(None, self.title)
                inside = self._cursor_in(hwnd) if hwnd else False
                now = time.time()
                if inside:
                    self._last_active = now
                    if not self._screen_on:
                        self._power(True)       # 鼠标进来 → 亮屏，开始操控
                elif (self._screen_on and self.idle_off > 0
                      and self._last_active > 0
                      and now - self._last_active > self.idle_off):
                    self._power(False)          # 鼠标离开超时 → 自动黑屏省电
            except Exception:
                pass
            self._stop.wait(self.poll)
        # 收尾：恢复亮度，避免手机被留在黑屏状态
        try:
            self._power(True)
        except Exception:
            pass


# -------------------------------------------------------------------- 界面

class Box:
    def _safe_after(self, fn, *args):
        """后台线程安全切主线程：放进队列，由 _pump_ui 在主线程里取出执行。

        以前这里是 `self._safe_after(lambda: fn(*args))` —— 自己调自己，
        结果是无限递归直到 RecursionError 被 except 吞掉：**任务一次都没执行过**。
        表现就是：消息收到了但列表不刷新、「搜索设备」扫到了但设备栏不变、
        toast 永远不弹，而 HTTP 照样回 200 —— 手机显示成功、电脑上却什么都没变。
        """
        try:
            self._uiq.put((fn, args))
        except Exception:
            pass

    def _pump_ui(self):
        """主线程定时把队列里的任务跑掉（Tk 只能在主线程操作）。"""
        try:
            while True:
                fn, args = self._uiq.get_nowait()
                try:
                    fn(*args)
                except Exception as e:
                    diag("UI 任务执行失败: %r" % (e,))
        except queue.Empty:
            pass
        except Exception:
            pass
        try:
            self.root.after(30, self._pump_ui)
        except Exception:
            pass


    def __init__(self, root, port):
        self.root = root
        self.port = port
        self._uiq = queue.Queue()      # 后台线程 → 主线程的任务队列（见 _safe_after）
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
        self.lan_devices = []         # 最近一次扫描到的局域网设备
        self._scanning = False        # 是否正在扫描（防重入）
        self.remote = None            # kv_remote.RemoteServer 实例（远控开启时非 None）
        self.remote_win = None        # 远控信息小窗（Toplevel）
        self.sending_file = False     # 是否正在向手机发文件（防重入）
        self.mirror_proc = None       # scrcpy 进程（手机投屏）
        self.mirror_guard = None      # 投屏省电看门狗

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

        # 状态行（取消独立顶栏横列后：状态文本/状态点并入设备栏下方的一条窄行）
        status_row = tk.Frame(root, bg=SURFACE)
        status_row.pack(fill="x")
        self.status_row = status_row
        self.status_canvas = tk.Canvas(status_row, width=10, height=10, bg=SURFACE,
                                       highlightthickness=0)
        self.dot = self.status_canvas.create_oval(0, 0, 10, 10, fill=WARNING)
        self.status_canvas.pack(side="left", padx=(12, 4), pady=4)
        self.status_label = tk.Label(status_row, textvariable=self.var_status, bg=SURFACE,
                                     fg=TEXT2, font=FONT_SM, anchor="w")
        self.status_label.pack(side="left", fill="x", expand=True, pady=4)

        # 设备栏：本机名称 + 局域网设备数量与清单（点「搜索设备」重新扫描）
        devbar = tk.Frame(root, bg=SURFACE)
        devbar.pack(fill="x")
        self.devbar = devbar
        self.var_devname = tk.StringVar(value=DEVICE_NAME["name"])
        self.dev_name_label = tk.Label(devbar, textvariable=self.var_devname, bg=SURFACE,
                                       fg=TEXT, font=("Microsoft YaHei", 10, "bold"),
                                       anchor="w")
        self.dev_name_label.pack(side="left", padx=(12, 6), pady=(0, 6))
        self.var_devlist = tk.StringVar(value="未发现设备")
        self.dev_list_label = tk.Label(devbar, textvariable=self.var_devlist, bg=SURFACE,
                                       fg=TEXT3, font=FONT_SM, anchor="w", justify="left")
        self.dev_list_label.pack(side="left", fill="x", expand=True, pady=(0, 6))
        self.btn_scan = self._hdr_btn(devbar, "搜索设备", self._scan_devices)
        self.btn_rename = self._hdr_btn(devbar, "改名", self._rename_device)
        self.btn_remote = self._hdr_btn(devbar, "远控", self.toggle_remote)
        self.btn_scr = self._hdr_btn(devbar, "手机投屏", self.launch_scrcpy)
        # 置顶/收起（原顶栏按钮，随顶栏取消后并入设备栏）
        self.btn_top = self._hdr_btn(devbar, "置顶:开", self._toggle_top)
        self.btn_dock = self._hdr_btn(devbar, "收起", self._toggle_dock)
        # 触屏输入（九宫格 / 全键盘，覆盖全屏）
        self.btn_touch = self._hdr_btn(devbar, "九宫格", self.open_touch_keyboard)

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
        bar.pack(fill="x", padx=10, pady=(0, 4))
        # 输入框左下角「＋」：发送文件/文件夹（全端共享）
        self._btn(bar, "＋ 文件", self.send_files_to_phone, "left")
        self._btn(bar, "上屏", self._paste_input, "left")
        self._btn(bar, "发到手机", self.send_to_phone, "left")
        self._btn(bar, "收件箱", self.open_recv_dir, "left")
        # 关闭按钮（原顶栏取消后移到输入框控制条右侧，不再占独立大白边）
        self.close_btn = self._hdr_btn(bar, "×", self.close, danger=True)
        bar2 = tk.Frame(self.input_frame, bg=SURFACE)
        bar2.pack(fill="x", padx=10, pady=(0, 8))
        self._btn(bar2, "清空输入框", self._clear_input, "left")
        self._btn(bar2, "清空列表", self._clear_list, "left")
        tk.Checkbutton(bar2, text="收到即上屏", variable=self.var_auto, bg=SURFACE, fg=TEXT2,
                       selectcolor=SURFACE_HI, activebackground=SURFACE, activeforeground=TEXT,
                       font=FONT_SM, borderwidth=0, highlightthickness=0).pack(side="left", padx=8)
        tk.Label(bar2, text="   拖消息到输入框可粘贴", bg=SURFACE, fg=TEXT3,
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

        # 进界面先扫一次局域网，顶栏就能立刻显示「发现 N 台设备」
        self._auto_scan()

    # --------------------------------------------------------- 定时扫描设备

    # 自动刷新间隔（毫秒）。只是偶尔发几个 UDP 广播，开销可以忽略。
    DEV_SCAN_MS = 15000

    def _auto_scan(self):
        """静默刷新设备数量：只更新设备栏文字，不弹提示。"""
        def work():
            devices = discover_lan()

            def done():
                self.lan_devices = devices
                if not devices:
                    self.var_devlist.set("未发现设备")
                else:
                    names = ", ".join("%s（%s）" % (d[2] or d[0],
                                                   "手机" if d[1] == "phone" else "电脑")
                                       for d in devices)
                    self.var_devlist.set("发现 %d 台设备：%s" % (len(devices), names))

            self._safe_after(done)

        threading.Thread(target=work, daemon=True).start()
        self.root.after(self.DEV_SCAN_MS, self._auto_scan)

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
        self._safe_after(self._on_receive, text, from_name)

    def _on_receive(self, text, from_name=""):
        self.total += 1
        msg = {"id": self.total, "text": text, "from": from_name or "", "edited": False}
        self.messages.append(msg)
        self._render_message(msg)
        self.scroll_to_bottom()
        self.update_status()
        diag("已上屏到列表 id=%d 共 %d 条" % (msg["id"], len(self.messages)))

        # 展开态：绿闪条；收起(隐藏)态：不弹任何可见提示，鼠标移到顶部才显示（QQ 式贴边）
        if not self.docked:
            self.flash_green()

        ok = set_clipboard(text)
        if not ok:
            self.toast("剪贴板被占用，写入失败（请稍后再试）")
            return
        if self.var_auto.get() and not os.environ.get("KV_NO_PASTE") and self._paste_to_foreign():
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

            self._safe_after(done)

        threading.Thread(target=work, daemon=True).start()

    def _send_all(self, text):
        from_name = DEVICE_NAME["name"]
        body = json.dumps({"text": text, "from": from_name}).encode("utf-8")
        targets, seen = [], set()
        for ip, kind, name, port in discover_lan():
            if ip in seen:
                continue
            seen.add(ip)
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

    # ==================================================== 文件互传（手机 ↔ 电脑）

    def receive_file(self, path, size, from_name=""):
        """HTTP 线程收完文件后回调到这里：消息列表加一条文件行。"""
        self._safe_after(self._on_receive_file, path, size, from_name)

    def _on_receive_file(self, path, size, from_name=""):
        self.total += 1
        msg = {"id": self.total, "type": "file", "path": path,
               "text": os.path.basename(path), "size": size,
               "from": from_name or ""}
        self.messages.append(msg)
        self._render_file(msg)
        self.scroll_to_bottom()
        self.update_status()
        if not self.docked:
            self.flash_green()
        self.toast("收到文件 %s（%s）→ %s" % (msg["text"], human_size(size),
                                            os.path.dirname(path)))

    def _render_file(self, msg):
        """文件消息行：头像 + 文件名/大小 + 右侧「打开 / 目录」按钮。"""
        who = msg.get("from") or "手机"
        row = tk.Frame(self.list_inner, bg=BG)
        row.pack(fill="x", padx=8, pady=4)
        btns = tk.Frame(row, bg=BG)
        btns.pack(side="right", padx=4, pady=4)
        b_open = tk.Button(btns, text="打开", command=lambda m=msg: self._open_file(m))
        b_dir = tk.Button(btns, text="目录", command=lambda m=msg: self._open_file_dir(m))
        for b in (b_open, b_dir):
            b.configure(bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                        activeforeground="#FFFFFF", font=FONT_SM, borderwidth=0,
                        relief="flat", padx=8, pady=3,
                        highlightthickness=0, highlightbackground=BORDER)
        b_open.pack(side="top", pady=2)
        b_dir.pack(side="top", pady=2)

        av = tk.Canvas(row, width=36, height=36, bg=BG, highlightthickness=0)
        av.create_oval(0, 0, 36, 36, fill=SUCCESS, outline="")
        av.create_text(18, 18, text="档", fill="#062B12", font=FONT_SM)
        av.pack(side="left", anchor="n", padx=(0, 8), pady=2)

        bubble = tk.Frame(row, bg=SURFACE, highlightbackground=BORDER, highlightthickness=1)
        bubble.pack(side="left", fill="both", expand=True)
        tk.Label(bubble, text="%s · 文件" % who, bg=SURFACE, fg=TEXT3,
                 font=("Microsoft YaHei", 8)).pack(anchor="w", padx=8, pady=(4, 0))
        tk.Label(bubble, text=msg["text"], bg=SURFACE, fg=TEXT, font=FONT_SM,
                 anchor="w", justify="left", wraplength=300).pack(
                     anchor="w", padx=8, pady=(2, 0))
        tk.Label(bubble, text=human_size(msg.get("size", 0)) + " · 已存入收件目录",
                 bg=SURFACE, fg=TEXT3, font=("Microsoft YaHei", 8)).pack(
                     anchor="w", padx=8, pady=(0, 6))
        msg["row"] = row

    def _open_file(self, msg):
        path = msg.get("path", "")
        try:
            if os.path.exists(path):
                os.startfile(path)
            else:
                self.toast("文件不存在（可能已被移动或删除）")
        except Exception as e:
            self.toast("打开失败：%s" % e)

    def _open_file_dir(self, msg):
        path = msg.get("path", "")
        try:
            subprocess.Popen('explorer /select,"%s"' % path)
        except Exception:
            self.open_recv_dir()

    def open_recv_dir(self):
        d = ensure_recv_dir()
        try:
            os.startfile(d)
        except Exception as e:
            self.toast("打开目录失败：%s" % e)

    # ------------------------------------------------ 触屏输入（九宫格 / 全键盘）

    def open_touch_keyboard(self):
        """触屏输入：无边框 Toplevel 覆盖整个屏幕，九宫格 / 全键盘可切换，
        输入文字后一键群发到所有终端（与「发到手机」同语义，全端共享）。"""
        top = tk.Toplevel(self.root)
        top.title("触屏输入")
        try:
            top.attributes("-topmost", True)
            top.overrideredirect(True)
        except Exception:
            pass
        sw = top.winfo_screenwidth()
        sh = top.winfo_screenheight()
        top.geometry("%dx%d+0+0" % (sw, sh))
        top.configure(bg=BG)

        # 顶部条：标题 + 切换 + 发送 + 关闭
        hdr = tk.Frame(top, bg=SURFACE)
        hdr.pack(fill="x")
        tk.Label(hdr, text="触屏输入（全端共享）", bg=SURFACE, fg=TEXT,
                 font=FONT_TITLE).pack(side="left", padx=14, pady=10)
        btn_close = tk.Button(hdr, text="✕", command=top.destroy,
                              bg=ERROR, fg="#FFFFFF", activebackground="#C0392B",
                              font=FONT_SM, borderwidth=0, relief="flat",
                              padx=14, pady=8)
        btn_close.pack(side="right", padx=8, pady=8)
        btn_send = tk.Button(hdr, text="发送到所有设备", command=self._tk_send,
                             bg=SUCCESS, fg="#062B12", activebackground="#27AE60",
                             font=FONT_SM, borderwidth=0, relief="flat",
                             padx=14, pady=8)
        btn_send.pack(side="right", padx=8, pady=8)
        btn_caps = tk.Button(hdr, text="大写", command=self._tk_toggle_caps,
                             bg=SURFACE_HI, fg=TEXT2, activebackground=PRIMARY_PRESS,
                             font=FONT_SM, borderwidth=0, relief="flat",
                             padx=12, pady=8)
        btn_caps.pack(side="right", padx=8, pady=8)
        btn_switch = tk.Button(hdr, text="九宫格", command=self._tk_switch_mode,
                               bg=SURFACE_HI, fg=TEXT2, activebackground=PRIMARY_PRESS,
                               font=FONT_SM, borderwidth=0, relief="flat",
                               padx=12, pady=8)
        btn_switch.pack(side="right", padx=8, pady=8)

        entry = tk.Text(top, height=3, bg=SURFACE_HI, fg=TEXT, font=FONT_SM,
                        wrap="word", relief="flat", borderwidth=0,
                        insertbackground=TEXT, padx=10, pady=8)
        entry.pack(fill="x", padx=10, pady=6)
        entry.focus_set()

        keys = tk.Frame(top, bg=BG)
        keys.pack(fill="both", expand=True, padx=10, pady=(0, 10))

        # 全键盘：数字行 + 字母三行 + 符号行；九宫格：3×4 + 功能行
        QW = ["1234567890",
              "qwertyuiop",
              "asdfghjkl",
              "zxcvbnm"]
        GRID = [("1", "2", "3"), ("4", "5", "6"), ("7", "8", "9"), ("*", "0", "#")]
        SYM = ",.?;:'\"!()[]-_@/"

        def rebuild():
            for w in keys.winfo_children():
                w.destroy()
            if mode["grid"]:
                for row in GRID:
                    fr = tk.Frame(keys, bg=BG)
                    fr.pack(fill="both", expand=True, pady=2)
                    for ch in row:
                        tk.Button(fr, text=ch, command=lambda c=ch: self._tk_type(c),
                                  bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                                  font=("Microsoft YaHei", 22, "bold"), borderwidth=0,
                                  relief="flat").pack(side="left", fill="both", expand=True,
                                                      padx=2, pady=2)
                fr = tk.Frame(keys, bg=BG)
                fr.pack(fill="both", expand=True, pady=2)
                tk.Button(fr, text="空格", command=lambda: self._tk_type(" "),
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)
                tk.Button(fr, text="⌫", command=self._tk_backspace,
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)
                tk.Button(fr, text="⏎", command=self._tk_newline,
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)
            else:
                rows = list(QW)
                for r in rows:
                    fr = tk.Frame(keys, bg=BG)
                    fr.pack(fill="both", expand=True, pady=2)
                    for ch in r:
                        tk.Button(fr, text=ch, command=lambda c=ch: self._tk_type(c),
                                  bg=SURFACE_HI, fg=TEXT, activebackground=PRIMARY_PRESS,
                                  font=("Consolas", 18), borderwidth=0,
                                  relief="flat").pack(side="left", fill="both", expand=True,
                                                      padx=2, pady=2)
                fr = tk.Frame(keys, bg=BG)
                fr.pack(fill="both", expand=True, pady=2)
                for ch in SYM:
                    tk.Button(fr, text=ch, command=lambda c=ch: self._tk_type(c),
                              bg=SURFACE_HI, fg=TEXT, font=("Consolas", 15), borderwidth=0,
                              relief="flat").pack(side="left", fill="both", expand=True,
                                                  padx=1, pady=2)
                fr = tk.Frame(keys, bg=BG)
                fr.pack(fill="both", expand=True, pady=2)
                tk.Button(fr, text="空格", command=lambda: self._tk_type(" "),
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)
                tk.Button(fr, text="⌫", command=self._tk_backspace,
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)
                tk.Button(fr, text="⏎", command=self._tk_newline,
                          bg=SURFACE_HI, fg=TEXT, font=FONT_SM, borderwidth=0,
                          relief="flat").pack(side="left", fill="both", expand=True,
                                              padx=2, pady=2)

        self._tk_win = top
        self._tk_entry = entry
        self._tk_switch_btn = btn_switch
        self._tk_caps_btn = btn_caps
        self._tk_rebuild = rebuild
        rebuild()
        top.bind("<Escape>", lambda e: top.destroy())

    def _tk_type(self, ch):
        e = getattr(self, "_tk_entry", None)
        if e is None:
            return
        if ch.isalpha() and getattr(self, "_tk_caps", False):
            ch = ch.upper()
        e.insert("end", ch)

    def _tk_backspace(self):
        e = getattr(self, "_tk_entry", None)
        if e is None:
            return
        try:
            e.delete("end-1c")
        except Exception:
            pass

    def _tk_newline(self):
        e = getattr(self, "_tk_entry", None)
        if e is None:
            return
        e.insert("end", "\n")

    def _tk_toggle_caps(self):
        self._tk_caps = not getattr(self, "_tk_caps", False)
        self._tk_caps_btn.configure(bg=PRIMARY if self._tk_caps else SURFACE_HI)

    def _tk_switch_mode(self):
        self._tk_mode_grid = not getattr(self, "_tk_mode_grid", True)
        self._tk_switch_btn.configure(
            text="全键盘" if self._tk_mode_grid else "九宫格")
        self._tk_rebuild()

    def _tk_send(self):
        e = getattr(self, "_tk_entry", None)
        if e is None:
            return
        text = e.get("1.0", "end-1c")
        if not text.strip():
            self.toast("没有输入内容")
            return
        win = getattr(self, "_tk_win", None)
        e.delete("1.0", "end")

        def work():
            ok, msg = self._send_all(text)
            if win is not None:
                self._safe_after(win.destroy)
            self._safe_after(self.toast, msg)

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------ 电脑 → 手机（发文件）

    def send_files_to_phone(self):
        """选文件 / 文件夹 → 群发给局域网 / adb 发现的所有终端（文件传输全端共享）。"""
        if self.sending_file:
            self.toast("正在发送文件，请稍候…")
            return
        files = list(filedialog.askopenfilenames(title="选择要发送的文件（可多选）"))
        if not files:
            folder = filedialog.askdirectory(title="或选择要发送的文件夹")
            if not folder:
                return
            files = []
            for dirpath, _dirs, names in os.walk(folder):
                for n in names:
                    files.append(os.path.join(dirpath, n))
            if not files:
                self.toast("文件夹里没有文件")
                return
        self.sending_file = True
        self.toast("正在查找终端…")
        threading.Thread(target=self._do_send_files, args=(files,), daemon=True).start()

    def _do_send_files(self, files):
        try:
            targets = self._file_targets()
            if not targets:
                self._safe_after(self.toast,
                                "没有发现手机：请让手机与电脑同 WiFi，或插 USB 后走 adb")
                return
            for path in files:
                if not os.path.isfile(path):
                    continue
                base = os.path.basename(path)
                size = os.path.getsize(path)
                results = []
                for url, tname in targets:
                    last_pct = [-1]

                    def prog(sent, total, _n=tname, _b=base, _lp=last_pct):
                        pct = int(sent * 100 / max(1, total))
                        if pct == _lp[0] or pct % 5:
                            return
                        _lp[0] = pct
                        self._safe_after(self.var_status.set,
                                        "发送 %s → %s：%d%%" % (_b, _n, pct))

                    ok, msg = self._upload_file(url, path, prog)
                    results.append("%s %s" % (tname, "完成" if ok else "失败"))
                self._safe_after(self.toast,
                                "已发「%s」（%s）：%s" % (base, human_size(size),
                                                        "；".join(results)))
        finally:
            self.sending_file = False
            self._safe_after(self.update_status)

    def _file_targets(self):
        """可接收文件的终端目标列表 [(url, 显示名)]：局域网发现【所有终端】（电脑+手机），adb 兜底。

        文件与文字同语义、全端共享：手机发的文件所有电脑都能收，电脑发的文件
        所有手机/电脑都能收（每台终端都自带 /file 接收端点）。
        """
        targets, seen = [], set()
        try:
            for ip, kind, name, port in discover_lan():
                if not ip or ip in seen:
                    continue
                seen.add(ip)
                targets.append(("http://%s:%d/file" % (ip, port), name or ip))
        except Exception:
            pass
        if PHONE_ADDR["ip"] and PHONE_ADDR["ip"] not in seen:
            seen.add(PHONE_ADDR["ip"])
            targets.append(("http://%s:%d/file" % (PHONE_ADDR["ip"], PHONE_SRV_PORT),
                            "手机(记忆)"))
        # adb 兜底：逐台 forward 到不同本地端口
        try:
            out = subprocess.run(["adb", "devices"], capture_output=True,
                                 timeout=6,
                                 creationflags=0x08000000).stdout.decode("utf-8", "ignore")
            i = 0
            for line in out.splitlines():
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    serial = parts[0]
                    local_port = PHONE_SRV_PORT + 1 + i
                    subprocess.run(["adb", "-s", serial, "forward",
                                    "tcp:%d" % local_port, "tcp:%d" % PHONE_SRV_PORT],
                                   capture_output=True, timeout=6,
                                   creationflags=0x08000000)
                    url = "http://127.0.0.1:%d/file" % local_port
                    if url not in [t[0] for t in targets]:
                        targets.append((url, "adb:" + serial[-4:]))
                    i += 1
        except Exception:
            pass
        return targets

    @staticmethod
    def _upload_file(base_url, path, on_progress=None):
        """把本地文件流式 POST 到 <base_url>?name=...&from=...，返回 (ok, msg)。"""
        import urllib.parse
        size = os.path.getsize(path)
        name = os.path.basename(path)
        u = urllib.parse.urlparse(base_url)
        conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
        try:
            conn.putrequest("POST", "%s?name=%s&from=%s" % (
                u.path, urlquote(name), urlquote(DEVICE_NAME["name"])))
            conn.putheader("Content-Type", "application/octet-stream")
            conn.putheader("Content-Length", str(size))
            conn.endheaders()
            sent = 0
            with open(path, "rb") as f:
                while True:
                    buf = f.read(262144)
                    if not buf:
                        break
                    conn.send(buf)
                    sent += len(buf)
                    if on_progress:
                        try:
                            on_progress(sent, size)
                        except Exception:
                            pass
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", "ignore")
            try:
                ok = bool(json.loads(body).get("ok"))
            except Exception:
                ok = resp.status == 200
            return ok, body
        except Exception as e:
            return False, str(e)
        finally:
            try:
                conn.close()
            except Exception:
                pass

    # ==================================================== 远控（手机控电脑）

    def _ensure_pil(self):
        """Pillow 可能刚被安装：重新加载 kv_remote 再探测一次。"""
        if kv_remote is None:
            return False
        if kv_remote.HAS_PIL:
            return True
        try:
            import importlib
            importlib.reload(kv_remote)
        except Exception:
            pass
        return kv_remote.HAS_PIL

    def toggle_remote(self):
        if self.remote is not None:
            self._stop_remote()
            return
        if kv_remote is None:
            messagebox.showerror("KeyVoice", "缺少 pc/kv_remote.py，无法启动远控。")
            return
        if not self._ensure_pil():
            if messagebox.askyesno(
                    "远控需要 Pillow",
                    "屏幕画面的压缩需要 Pillow 图像库（约 3 MB）。\n\n"
                    "是否现在自动安装？（pip install pillow）"):
                self._install_pillow()
            return
        try:
            self.remote = kv_remote.RemoteServer(
                port=REMOTE_PORT,
                on_log=lambda s: self._safe_after(self.toast, s))
            self.remote.start()
        except Exception as e:
            self.remote = None
            messagebox.showerror("远控启动失败",
                                 "%s\n\n（端口 %d 可能被占用）" % (e, REMOTE_PORT))
            return
        try:
            self.btn_remote.configure(fg=SUCCESS, text="远控:开")
        except Exception:
            pass
        self._show_remote_panel()

    def _stop_remote(self):
        if self.remote is not None:
            try:
                self.remote.stop()
            except Exception:
                pass
            self.remote = None
        try:
            self.btn_remote.configure(fg=TEXT2, text="远控")
        except Exception:
            pass
        if self.remote_win is not None and self.remote_win.winfo_exists():
            self.remote_win.destroy()
        self.remote_win = None
        self.toast("远控已关闭")

    def _lan_ip(self):
        try:
            ip = my_ip()
            if ip:
                return ip
        except Exception:
            pass
        return "127.0.0.1"

    def _show_remote_panel(self):
        if self.remote is None:
            return
        if self.remote_win is not None and self.remote_win.winfo_exists():
            self.remote_win.lift()
            return
        win = tk.Toplevel(self.root)
        win.title("KeyVoice 远控 · 手机控电脑")
        win.configure(bg=BG)
        win.attributes("-topmost", True)
        win.transient(self.root)
        self.remote_win = win
        url = "http://%s:%d/remote" % (self._lan_ip(), self.remote.port)
        tk.Label(win, text="手机端打开（KeyVoice「远控」页，或任意浏览器）：",
                 bg=BG, fg=TEXT2, font=FONT_SM).pack(anchor="w", padx=16, pady=(14, 2))
        tk.Label(win, text=url, bg=BG, fg=PRIMARY,
                 font=("Consolas", 12, "bold")).pack(anchor="w", padx=16)
        tk.Label(win, text="PIN 码（手机首次打开时输入一次）：",
                 bg=BG, fg=TEXT2, font=FONT_SM).pack(anchor="w", padx=16, pady=(12, 0))
        tk.Label(win, text=self.remote.pin, bg=BG, fg=SUCCESS,
                 font=("Consolas", 30, "bold")).pack(anchor="w", padx=16)
        tk.Label(win, text=("adb（USB）模式：在电脑执行一次  adb reverse tcp:%d tcp:%d，\n"
                            "手机 App 会自动改走 127.0.0.1，无需 WiFi。\n"
                            "局域网需在防火墙放行 TCP %d（见 allow_firewall.bat）。"
                            % (REMOTE_PORT, REMOTE_PORT, REMOTE_PORT)),
                 bg=BG, fg=TEXT3, font=FONT_SM, justify="left").pack(
                     anchor="w", padx=16, pady=(8, 4))
        row = tk.Frame(win, bg=BG)
        row.pack(fill="x", padx=16, pady=(6, 14))
        self._btn(row, "复制地址", lambda: (set_clipboard(url), self.toast("已复制")), "left")
        self._btn(row, "本机浏览器打开",
                  lambda: __import__("webbrowser").open(url + "?pin=" + self.remote.pin),
                  "left")
        self._btn(row, "关闭远控", self._stop_remote, "left")

    def _install_pillow(self):
        self.toast("正在安装 Pillow…（完成后请再点一次「远控」）")

        def work():
            try:
                out = subprocess.run([sys.executable, "-m", "pip", "install", "--user", "pillow"],
                                     capture_output=True, timeout=600,
                                     creationflags=0x08000000)
                ok = out.returncode == 0
                tail = ((out.stdout or b"") + (out.stderr or b"")).decode("utf-8", "ignore")[-400:]
            except Exception as e:
                ok, tail = False, str(e)

            def done():
                if ok:
                    self.toast("Pillow 安装完成：再点一次「远控」即可开启")
                else:
                    messagebox.showerror(
                        "安装失败", "自动安装失败，请手动执行：\n\npip install pillow\n\n" + tail)
            self._safe_after(done)

        threading.Thread(target=work, daemon=True).start()

    # ==================================================== 电脑控手机（scrcpy 集成）

    def _scrcpy_paths(self):
        """找 scrcpy / adb。优先 pc\\tools\\scrcpy\\（便携解压目录），再查 PATH。

        adb 的选择顺序有个关键坑：必须优先用【PATH / Android SDK 里的那个】，
        因为手机现在多半就是用它连上的（adb server 由该版本启动）。
        scrcpy 自带的 adb 版本通常不同，直接用它会把 adb server 重启掉，
        正在生效的无线调试（adb connect）连接会断。
        拿到准确路径后通过 ADB 环境变量传给 scrcpy（scrcpy 认这个变量）。
        """
        import shutil
        here = os.path.dirname(os.path.abspath(__file__))
        sdir = os.path.join(here, "tools", "scrcpy")
        scr = None
        for cand in (os.path.join(sdir, "scrcpy.exe"), shutil.which("scrcpy")):
            if cand and os.path.exists(cand):
                scr = cand
                break
        adb = shutil.which("adb")
        if not adb:
            for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
                root = os.environ.get(env)
                if root:
                    cand = os.path.join(root, "platform-tools", "adb.exe")
                    if os.path.exists(cand):
                        adb = cand
                        break
        if not adb:
            cand = os.path.join(sdir, "adb.exe")
            if os.path.exists(cand):
                adb = cand
        return scr, adb

    def _adb_devices(self, adb_exe):
        try:
            out = subprocess.run([adb_exe, "devices"], capture_output=True, timeout=8,
                                 creationflags=0x08000000).stdout.decode("utf-8", "ignore")
            devs = []
            for line in out.splitlines():
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    devs.append(parts[0])
            return devs
        except Exception:
            return []

    def launch_scrcpy(self):
        """手机投屏 / 电脑控手机：一键启动 scrcpy（151k★，ADB 通道，手机不用装 App）。

        <b>投屏省电模式（重点，实测方案）</b>：
        · 投屏启动后手机屏幕【自动黑屏】省电（OLED 不发光；实现是把亮度设为 0，
          不是关显示电源——实测关显示后输入会被 Android 丢弃、画面冻结，而亮度 0
          时系统仍认为亮屏：【输入有效】【画面实时】【连接稳定】三项全占）。
        · 【鼠标一动就亮屏】：把鼠标移入投屏窗口，看门狗自动恢复原亮度点亮手机，
          随后即可正常点击/滑动/输密码。
        · 鼠标移开投屏窗口 60 秒后自动再把亮度设 0，持续省电。
        · 再次点「手机投屏」按钮 = 停止投屏并恢复手机原亮度。
        · 其它：右键 = 熄屏时唤醒/亮屏时返回；Alt+P 电源；插充电线时 -w 生效
          （投屏期间不自动息屏）。
        """
        # 再次点击 = 停止（toggle）
        if self.mirror_proc is not None and self.mirror_proc.poll() is None:
            self._stop_mirror()
            return
        scr, adb = self._scrcpy_paths()
        if not scr:
            if messagebox.askyesno(
                    "未找到 scrcpy",
                    "「手机投屏 / 电脑控手机」直接复用开源项目 scrcpy（GitHub 151k★）。\n\n"
                    "获取方式（任选其一）：\n"
                    "1. 双击 pc\\下载scrcpy.bat 自动下载到 pc\\tools\\scrcpy\\\n"
                    "2. 打开 https://github.com/Genymobile/scrcpy/releases/latest 下 win64\n"
                    "   压缩包，解压到  pc\\tools\\scrcpy\\  （或把目录加入 PATH）\n"
                    "3. 里面自带 adb.exe，无需另外安装\n\n"
                    "是否现在打开下载页？"):
                __import__("webbrowser").open(
                    "https://github.com/Genymobile/scrcpy/releases/latest")
            return
        serial = None
        if adb:
            devs = self._adb_devices(adb)
            if not devs:
                if messagebox.askyesno(
                        "未检测到手机",
                        "adb 没有发现设备。\n\n"
                        "· USB：连接手机并开启「开发者选项 → USB 调试」\n"
                        "· 无线：先用 USB 执行一次  adb tcpip 5555，拔线后\n"
                        "  执行  adb connect <手机IP>:5555\n\n"
                        "是否打开 scrcpy 无线连接说明？"):
                    __import__("webbrowser").open(
                        "https://github.com/Genymobile/scrcpy/blob/master/doc/connection.md")
                return
            serial = devs[0]
            # 先唤醒一次：保证投屏建立时看到的是已点亮界面（锁屏 ≠ 黑屏，可解锁）
            try:
                subprocess.run([adb, "-s", serial, "shell", "input", "keyevent", "224"],
                               capture_output=True, timeout=8,
                               creationflags=0x08000000)
            except Exception:
                pass
        args = [scr]
        if serial:
            args += ["-s", serial]
        args += ["-w",                       # 插电时投屏期间不息屏（使用中不易被锁屏打断）
                 "--window-title", "KeyVoice 手机投屏",
                 # 固定初始窗口尺寸：防止上次异常退出后 scrcpy 记住了畸形几何
                 # （实测 terminate 会跳过窗口几何保存，下次可能开出 185x208 的小窗）
                 "--window-width", "480", "--window-height", "1040"]
        env = dict(os.environ)
        if adb:
            # 关键：让 scrcpy 用【手机当前连接所在的那个 adb】，
            # 否则自带 adb 版本不同会把 adb server 重启、无线调试连接掉线
            env["ADB"] = adb
        try:
            self.mirror_proc = subprocess.Popen(args, env=env,
                                                creationflags=0x00000008)
        except Exception as e:
            self.mirror_proc = None
            messagebox.showerror("启动 scrcpy 失败", str(e))
            return
        if adb and serial:
            threading.Thread(target=self._mirror_powersave,
                             args=(adb, serial), daemon=True).start()

    def _mirror_powersave(self, adb, serial):
        """等投屏窗口出现 → 自动黑屏省电 → 起看门狗（鼠标进入即亮屏）。"""
        u32 = ctypes.windll.user32
        hwnd = None
        for _ in range(48):                      # 最多等 12 秒
            if self.mirror_proc is None or self.mirror_proc.poll() is not None:
                return
            hwnd = u32.FindWindowW(None, "KeyVoice 手机投屏")
            if hwnd:
                break
            time.sleep(0.25)
        if not hwnd:
            self._safe_after(self.toast, "投屏已启动（未找到窗口，跳过自动省电）")
            return
        # 自动黑屏省电：亮度设 0（物理不发光；系统仍认为亮屏 → 可操控、画面实时）
        guard = MirrorGuard(adb, serial)
        guard.attach(self.mirror_proc)
        guard._power(False)
        self.mirror_guard = guard
        guard.start()
        try:
            self._safe_after(self.toast,
                            "投屏省电模式已开启：手机屏幕已自动关闭；"
                            "鼠标移入投屏窗口即亮屏可操控；移开 60 秒自动再黑屏")
        except Exception:
            pass

    def _stop_mirror(self):
        """停止投屏：停看门狗 → 结束 scrcpy → 恢复手机亮度。"""
        guard = self.mirror_guard
        adb = serial = None
        if guard is not None:
            adb, serial = guard.adb, guard.serial
            guard.stop()
            self.mirror_guard = None
        if self.mirror_proc is not None:
            try:
                if self.mirror_proc.poll() is None:
                    self.mirror_proc.terminate()
            except Exception:
                pass
            self.mirror_proc = None
        # 恢复亮度（guard 收尾会自动做一次；这里兜底再确保）
        if adb is None:
            scr, adb = self._scrcpy_paths()
        if adb and serial is None:
            devs = self._adb_devices(adb)
            if devs:
                serial = devs[0]
        if adb and serial:
            g = MirrorGuard(adb, serial)
            g._power(True)
        self.toast("已停止投屏，手机屏幕已恢复")

    # ------------------------------------------------- 设备名 / 设备数量

    def _apply_device_name(self, name):
        """改本机名：内存 + 配置文件 + 界面同步。空名忽略（退回原值）。"""
        name = (name or "").strip()
        if not name:
            return False
        DEVICE_NAME["name"] = name
        self.var_devname.set(name)
        save_device_name(name)
        return True

    def _rename_device(self):
        """弹一个小窗口改本机设备名（会广播给局域网内其它 KeyVoice 终端）。"""
        win = tk.Toplevel(self.root)
        win.title("设置本机名称")
        win.configure(bg=BG)
        win.attributes("-topmost", True)
        win.resizable(False, False)
        win.transient(self.root)
        try:
            win.grab_set()
        except Exception:
            pass

        tk.Label(win, text="本机名称（其它设备搜索时会看到）", bg=BG, fg=TEXT2,
                 font=FONT_SM).pack(anchor="w", padx=14, pady=(12, 6))
        entry = tk.Entry(win, bg=SURFACE_HI, fg=TEXT, insertbackground=TEXT,
                         relief="flat", font=FONT_SM, width=28)
        entry.insert(0, DEVICE_NAME["name"])
        entry.pack(fill="x", padx=14, pady=(0, 10))
        entry.select_range(0, "end")

        hint = tk.Label(win, text="", bg=BG, fg=TEXT3, font=FONT_SM)
        hint.pack(anchor="w", padx=14, pady=(0, 6))

        def commit():
            if self._apply_device_name(entry.get()):
                hint.configure(text="已保存，其它设备搜索时即可看到新名称", fg=SUCCESS)
                self.toast("本机名称已更新为：" + DEVICE_NAME["name"])
                self.root.after(700, win.destroy)
            else:
                hint.configure(text="名称不能为空", fg=ERROR)

        bar = tk.Frame(win, bg=BG)
        bar.pack(fill="x", padx=14, pady=(0, 12))
        self._btn(bar, "保存", commit, "right")
        self._btn(bar, "取消", win.destroy, "right")
        entry.bind("<Return>", lambda e: commit())
        entry.focus_set()

        # 居中到主窗口
        win.update_idletasks()
        w, hgt = win.winfo_width(), win.winfo_height()
        x = self.root.winfo_rootx() + (self.root.winfo_width() - w) // 2
        y = self.root.winfo_rooty() + (self.root.winfo_height() - hgt) // 2
        win.geometry("+%d+%d" % (max(0, x), max(0, y)))

    def _scan_devices(self):
        """扫描局域网，更新「发现 N 台设备」并把清单显示在设备栏。"""
        if getattr(self, "_scanning", False):
            return
        self._scanning = True
        self.btn_scan.configure(text="搜索中…", state="disabled")
        self.var_devlist.set("正在搜索设备…")

        def work():
            devices = discover_lan()
            diag("「搜索设备」结果=%r" % (devices,))

            def done():
                self._scanning = False
                self.btn_scan.configure(text="搜索设备", state="normal")
                self.lan_devices = devices
                if not devices:
                    self.var_devlist.set("未发现设备（确认对方已运行 KeyVoice 且在同一 WiFi）")
                    return
                names = ", ".join("%s（%s %s）" % (d[2] or d[0],
                                                  "手机" if d[1] == "phone" else "电脑",
                                                  d[0])
                               for d in devices)
                self.var_devlist.set("发现 %d 台设备：%s" % (len(devices), names))
                self.toast("找到 %d 台设备" % len(devices))
                diag("设备栏已更新：%s" % self.var_devlist.get())

            self._safe_after(done)

        threading.Thread(target=work, daemon=True).start()

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
        self.status_row.pack(fill="x")
        self.devbar.pack(fill="x")
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
            if self.mirror_guard is not None:
                self.mirror_guard.stop()
        except Exception:
            pass
        try:
            if self.mirror_proc is not None and self.mirror_proc.poll() is None:
                self.mirror_proc.terminate()
        except Exception:
            pass
        try:
            if self.remote is not None:
                self.remote.stop()
        except Exception:
            pass
        try:
            self.root.destroy()
        finally:
            raise SystemExit(0)


def main():
    try:  # pythonw（无控制台后台运行）下 stdout 为 None，print 会抛错
        print("KeyVoice 接收端  消息列表窗口 右上角置顶  端口 %d" % PORT)
        print("adb 模式：  adb reverse tcp:%d tcp:%d" % (PORT, PORT))
        print("局域网：手机点「搜索设备」会自动找到本机（电脑和手机都能互相发现）")
        print("本机名称：%s（界面右上角「改名」可修改）" % DEVICE_NAME["name"])
        print("文件互传：手机 → 电脑自动存入 %s" % ensure_recv_dir())
        print("         电脑 → 手机点底部「发文件」")
        print("网页远控：点顶部「远控」（手机控电脑）；「手机投屏」调用 scrcpy（电脑控手机）")
    except Exception:
        pass
    threading.Thread(target=udp_listen, args=(PORT,), daemon=True).start()
    # 应答其它设备的发现广播（多设备互发现）
    threading.Thread(target=lan_discover_responder, daemon=True).start()
    diag("启动 端口=%d 本机IP=%r 设备名=%r" % (PORT, sorted(my_ips()), DEVICE_NAME["name"]))

    root = tk.Tk()
    app = Box(root, PORT)
    threading.Thread(target=serve, args=(PORT, app), daemon=True).start()
    root.after(0, app._pump_ui)             # 后台线程 → 主线程 的任务泵
    if os.environ.get("KV_AUTOSCAN"):       # 排查用：启动 3 秒后自动扫一次并写 diag.log
        root.after(3000, app._scan_devices)
    root.mainloop()


if __name__ == "__main__":
    main()
