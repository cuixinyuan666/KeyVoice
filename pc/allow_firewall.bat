@echo off
chcp 65001 >nul
rem KeyVoice 防火墙一键放行：接收端口 17890(TCP) + 自动发现端口 17891(UDP)
rem 请右键"以管理员身份运行"

netsh advfirewall firewall add rule name="KeyVoice TCP 17890" dir=in action=allow protocol=TCP localport=17890
netsh advfirewall firewall add rule name="KeyVoice UDP 17891" dir=in action=allow protocol=UDP localport=17891

echo.
echo 已放行。删除规则命令：
echo   netsh advfirewall firewall delete rule name="KeyVoice TCP 17890"
echo   netsh advfirewall firewall delete rule name="KeyVoice UDP 17891"
echo.
pause
