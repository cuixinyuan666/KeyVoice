#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
KeyVoice · 一键下载 scrcpy（电脑控手机 / 手机投屏）
====================================================

为什么用 Python 而不是纯 bat 下载脚本：
  Windows 批处理文件里写中文，在「控制台代码页 ≠ UTF-8」时会被按 GBK 误读，
  UTF-8 的中文字节一旦被拆错，可能凭空产生 ^ 或 | 之类控制字符把脚本结构搞坏
  （本机实测：UTF-8+chcp 的 bat 在非交互调用时解析错乱）。
  → 逻辑放 Python（编码完全自控），bat 只做纯 ASCII 入口。

做三件事：
  1. 查 GitHub 最新 release，挑 win64 资产；
  2. 下载到临时文件（带进度、可重试镜像）；
  3. 解压到 pc\\tools\\scrcpy\\（自动压平 zip 里的一层目录）。

用法：双击 pc\\下载scrcpy.bat（或 python get_scrcpy.py）
"""

import io
import json
import os
import ssl
import sys
import tempfile
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEST = os.path.join(HERE, "tools", "scrcpy")
API = "https://api.github.com/repos/Genymobile/scrcpy/releases/latest"
# 直连不通时的镜像前缀（ghproxy 系），按顺序尝试
MIRRORS = ["", "https://ghproxy.net/", "https://mirror.ghproxy.com/"]

UA = {"User-Agent": "KeyVoice-scrcpy-downloader/1.0"}


def say(msg):
    """控制台可能是 GBK；直接用 replace 兜底，保证不因打印失败中断。"""
    try:
        print(msg, flush=True)
    except Exception:
        try:
            print(msg.encode("utf-8", "replace").decode("gbk", "replace"), flush=True)
        except Exception:
            pass


def fetch(url, timeout=40):
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=timeout, context=ctx)


def pick_asset():
    say("[1/3] 查询 scrcpy 最新版本……")
    with fetch(API) as r:
        rel = json.loads(r.read().decode("utf-8"))
    tag = rel.get("tag_name", "?")
    for a in rel.get("assets", []):
        n = a.get("name", "")
        if "win64" in n and n.endswith(".zip"):
            say("      最新版 %s → %s（%.1f MB）" % (tag, n, a.get("size", 0) / 1048576.0))
            return a["browser_download_url"], n
    raise RuntimeError("release 里没有 win64 资产")


def download(url, dest):
    last_err = None
    for pre in MIRRORS:
        u = (pre + url) if pre else url
        try:
            say("[2/3] 下载中（%s）……" % (u.split('/')[2]))
            with fetch(u, timeout=60) as r, open(dest, "wb") as f:
                total = int(r.headers.get("Content-Length") or 0)
                got = 0
                t0 = __import__("time").time()
                while True:
                    buf = r.read(262144)
                    if not buf:
                        break
                    f.write(buf)
                    got += len(buf)
                    if total:
                        pct = got * 100 // total
                        speed = got / 1048576.0 / max(0.001, __import__("time").time() - t0)
                        sys.stdout.write("\r      %3d%%  %.1f/%.1f MB  %.1f MB/s" %
                                         (pct, got / 1048576.0, total / 1048576.0, speed))
                        sys.stdout.flush()
            sys.stdout.write("\n")
            if os.path.getsize(dest) > 1_000_000:
                return True
            last_err = "下载文件过小"
        except Exception as e:
            last_err = e
            say("      失败：%s，换下一个源…" % e)
    say("      下载失败：%s" % last_err)
    return False


def extract(zip_path):
    say("[3/3] 解压到 %s" % DEST)
    os.makedirs(DEST, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            if n.endswith("/"):
                continue
            parts = n.split("/", 1)
            rel = parts[1] if len(parts) > 1 else parts[0]   # 压平一层目录
            out = os.path.join(DEST, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with z.open(n) as src, open(out, "wb") as f:
                while True:
                    buf = src.read(262144)
                    if not buf:
                        break
                    f.write(buf)
    return os.path.exists(os.path.join(DEST, "scrcpy.exe"))


def main():
    say("=" * 62)
    say("  KeyVoice · 下载 scrcpy（电脑控手机）")
    say("=" * 62)
    try:
        url, name = pick_asset()
    except Exception as e:
        say("查询最新版失败：%s" % e)
        say("请手动下载： https://github.com/Genymobile/scrcpy/releases/latest")
        say("把 win64 zip 解压到： %s" % DEST)
        return 1

    tmp = os.path.join(tempfile.gettempdir(), "kv_%s" % name)
    if not download(url, tmp):
        say("请手动下载： https://github.com/Genymobile/scrcpy/releases/latest")
        say("把 win64 zip 解压到： %s（要能直接看到 scrcpy.exe）" % DEST)
        return 1

    ok = False
    try:
        ok = extract(tmp)
    finally:
        try:
            os.remove(tmp)
        except Exception:
            pass

    if ok:
        say("")
        say("完成！scrcpy 已就位： %s" % os.path.join(DEST, "scrcpy.exe"))
        say("打开 KeyVoice 接收端 → 点顶部「手机投屏」即可。")
        say("锁屏也能操作：投屏窗口里【右键】= 屏幕熄了先唤醒；Alt+P = 电源开关。")
        return 0
    say("解压结果不完整，请手动处理。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
