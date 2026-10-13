# -*- coding: utf-8 -*-
"""创建桌面快捷方式：KeyVoice 接收端（keyvoice_box.py，端口 17890）"""
import os
import win32com.client

desktop = os.path.join(os.path.expanduser("~"), "Desktop")
lnk = os.path.join(desktop, "KeyVoice接收端.lnk")

shell = win32com.client.Dispatch("WScript.Shell")
sc = shell.CreateShortCut(lnk)
sc.TargetPath = r"C:\Users\Administrator\Documents\a_conda\pythonw.exe"
sc.Arguments = r'"D:\1\typetext\pc\keyvoice_box.py" 17890'
sc.WorkingDirectory = r"D:\1\typetext\pc"
sc.IconLocation = r"C:\Users\Administrator\Documents\a_conda\python.exe,0"
sc.Description = "KeyVoice Win10 接收端：监听 17890，后台运行无黑窗口，收到文字直接上屏到光标处"
sc.Save()

print("created:", lnk, os.path.exists(lnk))
