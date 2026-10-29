#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · 手机控电脑（内嵌网页远控）
=====================================

架构参考（不重复造轮子）：
  · noVNC / Apache Guacamole —— 「浏览器即客户端」：PC 端提供 HTTP 服务，
    手机端零安装，用 WebView / 浏览器打开页面即可看到屏幕并操作。
  · 视频通道用 MJPEG（multipart/x-mixed-replace）—— 几十年历史的成熟方案，
    所有 Chromium 内核（含 Android WebView）原生支持 <img> 直显。
  · 输入注入用 Windows 官方 SendInput（与 scrcpy 在电脑侧注入键鼠同理）。

为什么不用 RustDesk / scrcpy 直接代替：
  · scrcpy 是【电脑控手机】（ADB 通道），本模块覆盖的是反方向【手机控电脑】。
  · RustDesk 是完整独立应用（125k★），体验更流畅但需要两端各自安装；
    本模块的价值是【零安装、完全内嵌进 KeyVoice】，开机即用。
    重度使用推荐同时装 RustDesk（README「远程互控」一节有对比）。

实现要点：
  · 截屏：ctypes 直调 GDI（BitBlt → GetDIBits），零额外依赖；
  · 编码：Pillow（PIL）把 BGRA 帧降采样 + JPEG 编码（可选依赖，缺失时给出安装引导）；
  · 传输：MJPEG 多部分流；仅在有观看者时截屏（没人看不耗 CPU）；
  · 控制：心跳/观看计数；输入事件 POST /remote/input（JSON）；
  · 安全：简单 PIN 校验（URL 参数），连续失败锁定该 IP 10 分钟。

依赖：Pillow（pip install pillow）。未安装时 RemoteServer.start() 抛 RuntimeError，
调用方（keyvoice_box.py 的「远控」按钮）负责引导安装。
"""

import ctypes
import io
import json
import os
import random
import threading
import time
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image
    HAS_PIL = True
except Exception:
    Image = None
    HAS_PIL = False

# ---------------------------------------------------------------- Win32 常量

SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN = 76, 77
SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 78, 79

SRCCOPY = 0x00CC0020
DIB_RGB_COLORS = 0

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

# 常用虚拟键码（网页端映射）
VK = {
    "enter": 0x0D, "esc": 0x1B, "tab": 0x09, "backspace": 0x08, "delete": 0x2E,
    "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "ctrl": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B,
}
for _i in range(1, 13):
    VK["f%d" % _i] = 0x6F + _i


# ---------------------------------------------------------------- 结构体

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_void_p)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.c_void_p)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                ("wParamH", wintypes.WORD)]


class _INPUTunion(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTunion)]


_u32 = ctypes.windll.user32
_g32 = ctypes.windll.gdi32


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG),
                ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                ("biClrImportant", wintypes.DWORD)]


# ---------------------------------------------------------------- 截屏 / 编码

_cap_lock = threading.Lock()


def screen_metrics():
    """整个虚拟桌面（多显示器拼接）的原点与尺寸（物理像素）。"""
    x = _u32.GetSystemMetrics(SM_XVIRTUALSCREEN)
    y = _u32.GetSystemMetrics(SM_YVIRTUALSCREEN)
    w = _u32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
    h = _u32.GetSystemMetrics(SM_CYVIRTUALSCREEN)
    if w <= 0 or h <= 0:                      # 兜底：主屏
        w = _u32.GetSystemMetrics(0)
        h = _u32.GetSystemMetrics(1)
        x = y = 0
    return x, y, w, h


def grab_screen():
    """GDI 抓一帧整个虚拟桌面，返回 (w, h, 原始 BGRA bytes)。

    BitBlt 屏幕 DC 是 Windows 上最稳的零依赖截屏方式（与老版远程工具同源）。
    """
    with _cap_lock:
        x, y, w, h = screen_metrics()
        hdc = _u32.GetDC(0)
        mem = _g32.CreateCompatibleDC(hdc)
        bmp = _g32.CreateCompatibleBitmap(hdc, w, h)
        old = _g32.SelectObject(mem, bmp)
        try:
            _g32.BitBlt(mem, 0, 0, w, h, hdc, x, y, SRCCOPY)
            bmi = BITMAPINFOHEADER()
            bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
            bmi.biWidth = w
            bmi.biHeight = -h                 # 负高度 = 自上而下
            bmi.biPlanes = 1
            bmi.biBitCount = 32
            bmi.biCompression = 0             # BI_RGB
            buf = ctypes.create_string_buffer(w * h * 4)
            _g32.GetDIBits(mem, bmp, 0, h, buf, ctypes.byref(bmi), DIB_RGB_COLORS)
            return w, h, buf.raw
        finally:
            _g32.SelectObject(mem, old)
            _g32.DeleteObject(bmp)
            _g32.DeleteDC(mem)
            _u32.ReleaseDC(0, hdc)


def encode_jpeg(raw, w, h, target_w=1280, target_h=0, quality=60):
    """BGRA → 缩放 → JPEG bytes（Pillow）。

    target_h>0 时按目标分辨率（如手机屏幕分辨率）直接拉伸输出，供全屏模式
    使用；否则等比缩放到 target_w（普通模式）。
    """
    img = Image.frombuffer("RGB", (w, h), raw, "raw", "BGRX", 0, 1)
    if target_w and target_h and w > 0 and h > 0:
        img = img.resize((max(2, int(target_w)), max(2, int(target_h))),
                         Image.BILINEAR)
    elif target_w and w > target_w:
        th = max(1, int(h * target_w / float(w)))
        img = img.resize((target_w, th), Image.BILINEAR)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=int(quality), optimize=False)
    return out.getvalue()


# ---------------------------------------------------------------- 输入注入

def _send(*inputs):
    n = len(inputs)
    arr = (INPUT * n)(*inputs)
    _u32.SendInput(n, ctypes.byref(arr), ctypes.sizeof(INPUT))


def _mouse(dx=0, dy=0, data=0, flags=0):
    return INPUT(type=INPUT_MOUSE,
                 u=_INPUTunion(mi=MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, None)))


def _key(vk=0, scan=0, flags=0):
    return INPUT(type=INPUT_KEYBOARD,
                 u=_INPUTunion(ki=KEYBDINPUT(vk, scan, flags, 0, None)))


def mouse_move(nx, ny):
    """按虚拟桌面归一化坐标(0..1)移动鼠标。"""
    _send(_mouse(int(nx * 65535), int(ny * 65535),
                 flags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK))


_MOUSE_BTN = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}


def mouse_button(button, down):
    pair = _MOUSE_BTN.get(button)
    if not pair:
        return
    _send(_mouse(flags=pair[0] if down else pair[1]))


def mouse_wheel(notches):
    """notches>0 向上滚。"""
    _send(_mouse(data=int(notches * 120) & 0xFFFFFFFF, flags=MOUSEEVENTF_WHEEL))


def key_char(ch):
    """发送一个 Unicode 字符（中文/emoji 都能进输入框）。"""
    scan = ord(ch)
    if scan > 0xFFFF:                       # BMP 外：拆成 UTF-16 代理对
        high = 0xD800 + ((scan - 0x10000) >> 10)
        low = 0xDC00 + ((scan - 0x10000) & 0x3FF)
        for cp in (high, low):
            _send(_key(0, cp, KEYEVENTF_UNICODE), _key(0, cp, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        return
    _send(_key(0, scan, KEYEVENTF_UNICODE), _key(0, scan, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))


def key_vk(name, mods=()):
    """发送特殊键（Enter/Tab/方向键…），可带 ctrl/shift/alt 组合。"""
    code = VK.get((name or "").lower())
    if code is None:
        return
    mod_codes = [VK[m] for m in mods if m in VK]
    for mc in mod_codes:
        _send(_key(mc))
    _send(_key(code), _key(code, 0, KEYEVENTF_KEYUP))
    for mc in reversed(mod_codes):
        _send(_key(mc, 0, KEYEVENTF_KEYUP))


# ---------------------------------------------------------------- 网页端 HTML

_HTML = r"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>KeyVoice 远控</title>
<style>
  :root{--bg:#0E1116;--sf:#161A21;--hi:#1F2530;--bd:#2A313C;--tx:#E6EAF0;--t2:#9AA4B2;--t3:#6B7585;--pri:#4F8CFF;--ok:#2ECC71;--err:#FF5C5C}
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  html,body{margin:0;height:100%;background:var(--bg);color:var(--tx);font:14px system-ui,"Microsoft YaHei";overflow:hidden}
  #bar{position:fixed;left:0;right:0;top:0;height:46px;display:flex;align-items:center;gap:8px;padding:0 10px;background:var(--sf);border-bottom:1px solid var(--bd);z-index:9}
  #bar .st{font-size:12px;color:var(--t2);flex:1;overflow:hidden;white-space:nowrap}
  .b{border:1px solid var(--bd);background:var(--hi);color:var(--tx);border-radius:9px;padding:7px 11px;font-size:13px}
  .b.on{background:var(--pri);border-color:var(--pri);color:#fff}
  #wrap{position:fixed;inset:46px 0 0 0;display:flex;align-items:center;justify-content:center}
  #view{max-width:100%;max-height:100%;display:block;touch-action:none;image-rendering:auto}
  #hint{position:fixed;left:0;right:0;bottom:8px;text-align:center;color:var(--t3);font-size:12px;pointer-events:none}
  #kbar{position:fixed;left:0;right:0;bottom:0;display:none;gap:6px;padding:8px;background:var(--sf);border-top:1px solid var(--bd);z-index:9;flex-wrap:wrap}
  #kbar .b{padding:9px 12px}
  #tin{position:fixed;left:-9999px;top:-9999px;width:10px;height:10px}
  /* 全屏模式：覆盖顶栏/底条，画面铺满整个手机屏幕 */
  #wrap.full{inset:0}
  #view.full{width:100%;height:100%;object-fit:fill}
  #exf{position:fixed;right:12px;top:12px;display:none;z-index:20;border:1px solid var(--bd);background:rgba(22,26,33,.85);color:var(--tx);border-radius:9px;padding:8px 12px;font-size:13px}
</style>
</head>
<body>
<div id="bar">
  <span class="st" id="st">连接中…</span>
  <button class="b" id="bf">全屏</button>
  <button class="b" id="bq">画质</button>
  <button class="b" id="bk">键盘</button>
  <button class="b" id="br">右键</button>
</div>
<div id="wrap"><img id="view" alt=""></div>
<button id="exf">退出全屏</button>
<div id="kbar">
  <button class="b" data-vk="esc">Esc</button>
  <button class="b" data-vk="tab">Tab</button>
  <button class="b" data-vk="backspace">⌫</button>
  <button class="b" data-vk="enter">⏎</button>
  <button class="b" data-vk="left">←</button>
  <button class="b" data-vk="up">↑</button>
  <button class="b" data-vk="down">↓</button>
  <button class="b" data-vk="right">→</button>
  <button class="b" data-vk="pageup">PgUp</button>
  <button class="b" data-vk="pagedown">PgDn</button>
  <button class="b" data-vk="ctrl+c">Ctrl+C</button>
  <button class="b" data-vk="ctrl+v">Ctrl+V</button>
  <button class="b" data-vk="ctrl+z">Ctrl+Z</button>
  <button class="b" data-vk="alt+tab">Alt+Tab</button>
  <button class="b" data-vk="win">⊞</button>
</div>
<div id="hint">单击=左键 · 长按=右键 · 拖动=拖拽 · 双指上下=滚轮 · 点「键盘」输入中文</div>
<input id="tin" autocomplete="off" autocorrect="off" autocapitalize="off">
<script>
(function(){
  var q = new URLSearchParams(location.search);
  var pin = q.get('pin') || localStorage.getItem('kv_pin') || '';
  if(!pin){ pin = prompt('请输入电脑上显示的 4 位 PIN：') || ''; }
  localStorage.setItem('kv_pin', pin);
  var qw = parseInt(q.get('w')||'0',10);     // 0=默认(跟随服务端)
  var qq = parseInt(q.get('q')||'0',10);
  var view=document.getElementById('view'), st=document.getElementById('st');
  var wrap=document.getElementById('wrap'), tin=document.getElementById('tin');
  var rightMode=false, kbar=document.getElementById('kbar');
  var quality=1, FQ=[[960,45],[1280,60],[1600,75]];

  function api(path){ return '/remote'+path+(path.indexOf('?')<0?'?':'&')+'pin='+encodeURIComponent(pin); }
  function post(o){
    fetch(api('/input'),{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify(o)}).catch(function(){});
  }
  function start(){
    view.src = api('/stream?w='+FQ[quality][0]+'&q='+FQ[quality][1]+'&t='+Date.now());
  }
  view.onload=function(){ st.textContent='已连接 · '+view.naturalWidth+'×'+view.naturalHeight; };
  view.onerror=function(){ st.textContent='连接失败：请在电脑端开启「远控」并核对 PIN'; };
  start();

  // ---- 坐标换算：显示元素内 → 0..1 ----
  function norm(ev){
    var r=view.getBoundingClientRect();
    var x=ev.clientX-r.left, y=ev.clientY-r.top;
    return [Math.min(1,Math.max(0,x/r.width)), Math.min(1,Math.max(0,y/r.height))];
  }

  // ---- 触摸手势 ----
  var t0=0, sx=0, sy=0, lx=0, ly=0, moved=false, dragging=false, willRight=false, tly=null;
  function sendMove(n){ post({t:'m', x:n[0], y:n[1]}); }
  wrap.addEventListener('touchstart', function(e){
    if(e.touches.length>1){ tly=null; return; }   // 双指：滚轮
    var n=norm(e.touches[0]); sx=lx=n[0]; sy=ly=n[1]; t0=Date.now();
    moved=false; dragging=false; willRight=rightMode;
    if(rightMode){ rightMode=false; document.getElementById('br').classList.remove('on'); }
    e.preventDefault();
  }, {passive:false});
  wrap.addEventListener('touchmove', function(e){
    if(e.touches.length>1){                      // 双指拖 = 滚轮（自然方向：手指下拖=内容下移）
      var cy=(e.touches[0].clientY+e.touches[1].clientY)/2;
      if(tly===null){ tly=cy; e.preventDefault(); return; }
      if(Math.abs(cy-tly)>=36){ post({t:'s', n:(cy>tly?1:-1)}); tly=cy; }
      e.preventDefault(); return;
    }
    var n=norm(e.touches[0]);
    moved = moved || (Math.abs(n[0]-sx)>0.01 || Math.abs(n[1]-sy)>0.01);
    if(!dragging && moved){                      // 进入拖拽：先按下
      dragging=true; sendMove([sx,sy]); post({t:'d', b:'left'});
    }
    if(dragging) sendMove(n);
    lx=n[0]; ly=n[1];
    e.preventDefault();
  }, {passive:false});
  wrap.addEventListener('touchend', function(e){
    tly=null;
    if(e.touches.length>0) return;
    var n=[lx,ly];
    if(dragging){ sendMove(n); post({t:'u', b:'left'}); }
    else{
      // 轻点=左键；长按>550ms 或预置右键=右键。
      // 双击不特殊处理：两次快速轻点会自然落进 Windows 的双击判定窗口。
      var btn=(willRight || Date.now()-t0>550) ? 'right' : 'left';
      sendMove(n); post({t:'c', b:btn, n:1});
    }
    e.preventDefault();
  }, {passive:false});
  view.addEventListener('mousedown', function(e){ // 桌面浏览器也支持
    var n=norm(e); sendMove(n); post({t:'d', b:e.button===2?'right':'left'}); e.preventDefault();
  });
  view.addEventListener('mouseup', function(e){
    var n=norm(e); sendMove(n); post({t:'u', b:e.button===2?'right':'left'}); e.preventDefault();
  });
  view.addEventListener('contextmenu', function(e){ e.preventDefault(); });
  view.addEventListener('mousemove', function(e){ var n=norm(e); sendMove(n); });
  view.addEventListener('wheel', function(e){ post({t:'s', n: e.deltaY>0?-1:1}); e.preventDefault(); }, {passive:false});

  // ---- 按钮 ----
  var full=false, bf=document.getElementById('bf'), exf=document.getElementById('exf');
  var bar=document.getElementById('bar'), hint=document.getElementById('hint');
  function enterFull(){
    full=true;
    bar.style.display='none'; hint.style.display='none'; kbar.style.display='none';
    wrap.classList.add('full'); view.classList.add('full');
    exf.style.display='block';
    // 以手机屏幕分辨率为标准：服务端按「手机宽 × 手机高」直接出图，画面铺满整个手机屏
    view.src=api('/stream?w='+Math.round(window.innerWidth)+'&h='+Math.round(window.innerHeight)
                 +'&q='+FQ[quality][1]+'&t='+Date.now());
  }
  function exitFull(){
    full=false;
    bar.style.display='flex'; hint.style.display='block';
    wrap.classList.remove('full'); view.classList.remove('full');
    exf.style.display='none';
    start();
  }
  bf.addEventListener('click', function(){ if(full){ exitFull(); } else { enterFull(); } });
  exf.addEventListener('click', function(){ exitFull(); });
  document.getElementById('bq').addEventListener('click', function(){
    quality=(quality+1)%FQ.length; this.textContent='画质:'+['流畅','标准','高清'][quality]; start();
  });
  document.getElementById('bk').addEventListener('click', function(){
    var on=kbar.style.display!=='flex'; kbar.style.display=on?'flex':'none';
    if(on) tin.focus();
    this.classList.toggle('on', on);
  });
  document.getElementById('br').addEventListener('click', function(){
    rightMode=!rightMode; this.classList.toggle('on', rightMode);
  });

  // ---- 键盘：软键盘字符 + 特殊键 ----
  tin.addEventListener('input', function(){
    var v=tin.value; tin.value='';
    if(!v) return;
    for(var i=0;i<v.length;i++) post({t:'k', ch:v[i]});
  });
  kbar.addEventListener('click', function(e){
    var vk=e.target.getAttribute && e.target.getAttribute('data-vk');
    if(vk){ var p=vk.split('+'); post({t:'vk', v:p[p.length-1], mods:p.slice(0,-1)}); tin.focus(); }
  });

  window.addEventListener('resize', function(){});
})();
</script>
</body>
</html>
"""


# ---------------------------------------------------------------- HTTP 服务

_HTML_BYTES = _HTML.encode("utf-8")


class _Handler(BaseHTTPRequestHandler):
    server_version = "KeyVoiceRemote/1.0"

    def log_message(self, *a):
        pass

    # ---- 工具 ----
    def _q(self, name, default=""):
        from urllib.parse import urlparse, parse_qs
        qs = parse_qs(urlparse(self.path).query)
        v = qs.get(name, [default])
        return v[0] if v else default

    def _check_pin(self):
        srv = self.server.kv
        ip = self.client_address[0] if self.client_address else "?"
        if srv.locked(ip):
            return False
        pin = self._q("pin")
        if pin and pin == srv.pin:
            srv.clear_fails(ip)
            return True
        srv.add_fail(ip)
        try:
            self.send_response(403)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write("PIN 错误".encode("utf-8"))
        except Exception:
            pass
        return False

    def _json(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---- 路由 ----
    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/remote", "/remote/"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(_HTML_BYTES)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(_HTML_BYTES)
            return
        if path == "/remote/stream":
            if not self._check_pin():
                return
            self._stream()
            return
        if path == "/remote/frame.jpg":
            if not self._check_pin():
                return
            frame = self.server.kv.snapshot()
            if frame is None:
                self.send_response(503)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(frame)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(frame)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/remote/input":
            if not self._check_pin():
                return
            try:
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n) if n else b"{}"
                ev = json.loads(raw.decode("utf-8"))
                apply_event(ev)
                self._json({"ok": True})
            except Exception as e:
                self._json({"ok": False, "err": str(e)})
            return
        self.send_response(404)
        self.end_headers()

    # ---- MJPEG 流 ----
    def _stream(self):
        srv = self.server.kv
        try:
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=kvframe")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Pragma", "no-cache")
            self.end_headers()
        except Exception:
            return
        srv.add_watcher()
        w = int(self._q("w", "0") or 0)
        h = int(self._q("h", "0") or 0)
        q = int(self._q("q", "0") or 0)
        if w > 0:
            srv.set_video(w, q or srv.quality, h)
        last = -1
        try:
            while not srv.stopped:
                frame, seq = srv.wait_frame(last, timeout=5.0)
                if frame is None:
                    if srv.stopped:
                        break
                    continue
                last = seq
                head = ("--kvframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n"
                        % len(frame)).encode("ascii")
                self.wfile.write(head)
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
        except Exception:
            pass
        finally:
            srv.remove_watcher()
            try:
                self.close_connection = True
            except Exception:
                pass


def apply_event(ev):
    """把网页发来的一个输入事件注入到 Windows。"""
    t = ev.get("t")
    if t == "m":
        mouse_move(float(ev.get("x", 0)), float(ev.get("y", 0)))
    elif t == "d":
        mouse_button(ev.get("b", "left"), True)
    elif t == "u":
        mouse_button(ev.get("b", "left"), False)
    elif t == "c":                                   # 点击（可双击 n=2）
        b = ev.get("b", "left")
        n = int(ev.get("n", 1))
        for _ in range(max(1, n)):
            mouse_button(b, True)
            time.sleep(0.02)
            mouse_button(b, False)
            if n > 1:
                time.sleep(0.03)
    elif t == "s":                                   # 滚轮
        mouse_wheel(int(ev.get("n", 1)))
    elif t == "k":                                   # 字符
        ch = ev.get("ch") or ""
        for c in ch:
            key_char(c)
            time.sleep(0.004)
    elif t == "vk":                                  # 特殊键 / 组合键
        key_vk(ev.get("v", ""), ev.get("mods") or ())


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class RemoteServer:
    """手机控电脑：远控 HTTP 服务 + 截屏生产线程。

    典型用法：
        srv = RemoteServer(pin="8312")
        srv.start()          # 抛 RuntimeError 表示缺 Pillow
        ...
        srv.stop()
    """

    PORT = 17894

    def __init__(self, port=None, pin=None, on_log=None):
        self.port = int(port or self.PORT)
        self.pin = str(pin or random.randint(1000, 9999))
        self.on_log = on_log
        self.httpd = None
        self._thread = None
        self._prod = None
        self._cond = threading.Condition()
        self._frame = None
        self._seq = 0
        self._watchers = 0
        self.stopped = False
        self._lock = threading.Lock()
        self._fails = {}                      # ip -> [count, lock_until]
        # 视频参数（可被观看端覆盖）
        self.target_w = 1280
        self.target_h = 0          # 全屏模式的目标高度（手机屏幕分辨率），0=等比
        self.quality = 60
        self.fps = 12

    # ---- 生命周期 ----
    def start(self):
        if not HAS_PIL:
            raise RuntimeError("缺少 Pillow（pip install pillow）")
        if self.httpd:
            return
        self.stopped = False
        self.httpd = _Server(("0.0.0.0", self.port), _Handler)
        self.httpd.kv = self
        self._thread = threading.Thread(target=self.httpd.serve_forever,
                                        kwargs={"poll_interval": 0.3},
                                        name="KeyVoiceRemote", daemon=True)
        self._thread.start()
        self._prod = threading.Thread(target=self._produce, name="KeyVoiceFrames", daemon=True)
        self._prod.start()
        self._log("远控已开启 http://%s:%d/remote   PIN=%s" % ("127.0.0.1", self.port, self.pin))

    def stop(self):
        self.stopped = True
        with self._cond:
            self._cond.notify_all()
        if self.httpd:
            try:
                self.httpd.shutdown()
                self.httpd.server_close()
            except Exception:
                pass
            self.httpd = None
        self._log("远控已关闭")

    # ---- 帧生产 ----
    def _produce(self):
        while not self.stopped:
            if self._watchers <= 0:
                time.sleep(0.15)
                continue
            t0 = time.time()
            try:
                w, h, raw = grab_screen()
                jpg = encode_jpeg(raw, w, h, self.target_w, self.target_h,
                                  self.quality)
                with self._cond:
                    self._frame = jpg
                    self._seq += 1
                    self._cond.notify_all()
            except Exception as e:
                self._log("截屏失败: %s" % e)
                time.sleep(0.5)
                continue
            dt = time.time() - t0
            time.sleep(max(0.0, 1.0 / max(1, self.fps) - dt))

    def add_watcher(self):
        with self._lock:
            self._watchers += 1

    def remove_watcher(self):
        with self._lock:
            self._watchers = max(0, self._watchers - 1)

    def set_video(self, w, q, h=0):
        if w and 400 <= w <= 3840:
            self.target_w = int(w)
        if h and 400 <= h <= 3840:
            self.target_h = int(h)
        else:
            self.target_h = 0        # 未指定高度 → 等比缩放（普通模式）
        if q and 20 <= q <= 95:
            self.quality = int(q)

    def latest_frame(self, timeout=1.0):
        end = time.time() + timeout
        with self._cond:
            while self._seq == 0 and not self.stopped and time.time() < end:
                if self._watchers <= 0:
                    break
                self._cond.wait(0.2)
            return self._frame

    def snapshot(self, timeout=3.0):
        """单帧快照：无观看者时按需现抓一帧（/remote/frame.jpg 用）。"""
        frame = self.latest_frame(timeout=0.2)
        if frame is not None:
            return frame
        try:
            w, h, raw = grab_screen()
            return encode_jpeg(raw, w, h, self.target_w, self.target_h,
                               self.quality)
        except Exception as e:
            self._log("截屏失败: %s" % e)
            return None

    def wait_frame(self, last_seq, timeout=5.0):
        end = time.time() + timeout
        with self._cond:
            while self._seq <= last_seq and not self.stopped and time.time() < end:
                self._cond.wait(0.5)
            return self._frame, self._seq

    # ---- PIN 防爆破 ----
    def locked(self, ip):
        with self._lock:
            v = self._fails.get(ip)
            if not v:
                return False
            return v[1] > time.time()

    def add_fail(self, ip):
        with self._lock:
            v = self._fails.get(ip) or [0, 0]
            v[0] += 1
            if v[0] >= 5:
                v = [0, time.time() + 600]     # 锁 10 分钟
            self._fails[ip] = v

    def clear_fails(self, ip):
        with self._lock:
            self._fails.pop(ip, None)

    def _log(self, msg):
        if self.on_log:
            try:
                self.on_log(msg)
            except Exception:
                pass


if __name__ == "__main__":
    import socket
    pin = "".join(random.choice("0123456789") for _ in range(4))
    s = RemoteServer(pin=pin)
    s.start()
    host = socket.gethostbyname(socket.gethostname())
    print("KeyVoice 远控已启动：")
    print("  手机浏览器 / WebView 打开： http://%s:%d/remote?pin=%s" % (host, s.port, pin))
    print("  Ctrl+C 退出")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        s.stop()
