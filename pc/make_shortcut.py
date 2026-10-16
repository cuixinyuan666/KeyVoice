# -*- coding: utf-8 -*-
"""创建桌面快捷方式：KeyVoice 接收端（keyvoice_box.py，端口 17890）。

双击图标即可后台常驻：用 pythonw.exe 启动，不会弹出黑色控制台窗口。

路径全部按【当前机器】推导，不写死用户名 / 盘符 —— 换机器、换目录都不用改代码：
    · 解释器：取当前运行本脚本的那个 Python 同目录下的 pythonw.exe
    · 脚本  ：本文件所在目录下的 keyvoice_box.py

用法：  python make_shortcut.py
依赖：  pywin32（win32com）。缺的话先 pip install pywin32。
"""

import os
import sys

PORT = "17890"

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "keyvoice_box.py")

# ---- 解释器：优先同目录的 pythonw.exe（无控制台窗口）----
_py_dir = os.path.dirname(os.path.abspath(sys.executable))
_pythonw = os.path.join(_py_dir, "pythonw.exe")
if not os.path.exists(_pythonw):
    _pythonw = sys.executable          # 找不到就退回当前解释器（会有黑窗口）

_python = os.path.join(_py_dir, "python.exe")
if not os.path.exists(_python):
    _python = sys.executable


def main():
    if not os.path.exists(SCRIPT):
        raise SystemExit("找不到 keyvoice_box.py：%s" % SCRIPT)

    try:
        import win32com.client
    except ImportError:
        raise SystemExit("缺少 pywin32，请先执行：  pip install pywin32")

    desktop = os.path.join(os.path.expanduser("~"), "Desktop")
    lnk = os.path.join(desktop, "KeyVoice接收端.lnk")

    shell = win32com.client.Dispatch("WScript.Shell")
    sc = shell.CreateShortCut(lnk)
    sc.TargetPath = _pythonw
    sc.Arguments = '"%s" %s' % (SCRIPT, PORT)
    sc.WorkingDirectory = HERE
    sc.IconLocation = "%s,0" % _python
    sc.Description = ("KeyVoice Win10 接收端：监听 %s，后台常驻无黑窗口，"
                      "收到文字直接上屏到光标处" % PORT)
    sc.Save()

    print("已创建桌面快捷方式：")
    print("  图标  :", lnk, "->", os.path.exists(lnk))
    print("  解释器:", _pythonw)
    print("  脚本  :", SCRIPT, " 端口", PORT)
    print()
    print("双击该图标即可启动接收端（无黑窗口，常驻在屏幕右上角）。")


if __name__ == "__main__":
    main()
