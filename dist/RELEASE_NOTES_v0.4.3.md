## KeyVoice v0.4.3

### 修复：PC 端再也发现不了手机 / 手机发送失败

**根因**（已复现）：PC 端两个发现应答线程（UDP 17893 / 17891）的收包循环里写的是
`except Exception: break`。Windows 上只要**之前有一次 sendto 的目标端口已经关闭**
（设备下线、扫描端口复用、手机切后台后随机端口释放……），系统就会回一个 ICMP
端口不可达，并把 `WSAECONNRESET(10054)` 报到**下一次 recvfrom** 上 ——
于是应答线程 `break` 直接退出，socket 却还占着端口。

结果就是：进程还在、`netstat` 里 17893 还显示绑定、界面看起来一切正常，
但**再也不会回应任何发现广播**，手机永远搜不到电脑；手机端同理，反问线程
一处异常就永久失效，只能重启 App。

**修复内容**
- PC 端 `udp_listen` / `lan_discover_responder`：收包与回包分别 try/except，
  遇到 `OSError(10054)` 只 sleep 几毫秒继续，不再退出
- 两个应答线程 + HTTP 服务加自愈外壳：绑定失败（端口被占、网卡切换）每 2 秒重试，
  不会静默失联
- 手机端 `LanServer` 发现应答：循环体内逐包 try/catch，单条畸形包不再打断整个线程

### 验证
- 手机（vivo / Android 9）成功发现电脑：`cxy（电脑 · 192.168.1.8）`
- 电脑端 `discover_lan()` 成功发现手机：`('192.168.1.4', 'phone', 'vivo', 17892)`
- 手机 → 电脑 `POST /text` 返回 `{"ok": true}`，消息进入电脑端消息列表

### 升级
- APK 仍为 debug 签名，可直接覆盖安装（本次 versionCode 24 / versionName 0.4.3）
- 电脑端口**必须重启** "KeyVoice接收端" 才生效（Python 脚本改的是运行时行为）
