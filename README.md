# KeyVoice · 按键语音

**Android 9 上按一个实体键 → 用手机上装的讯飞做语音转文字 → 文字通过局域网送到 Windows 10 → 直接上屏到电脑光标处。**

屏幕可以是黑的：灭屏按一下键，说完自动上屏，屏幕全程不亮。

```
[实体按键 / 耳机键 / 快捷磁贴 / 广播]  →  手机端 App（语音转文字）
                                              │ 局域网 HTTP POST
                                              ▼
                                     Windows 10 接收端
                                              │
                        有输入光标 → Ctrl+V 上屏；没有光标 → 只存剪贴板
```

---

## 和 GitHub 上已有方案的区别

搜过一轮，现成项目（`mofanx/voice-input`、`QAA-Tools/qaa-airtype`、`franj/PhoneMic`、`signalband/Sinput`、`enximi/tele_type_phone`）思路都是：**手机上打开网页/App → 点按钮说话 → 发到电脑**。

它们都能用，但缺两件事，而这两件正是本项目的重点：

| | 现有项目 | KeyVoice |
|---|---|---|
| 实体/自定义按键触发 | ❌ 都要先摸屏幕点按钮 | ✅ 无障碍服务直接截获物理按键，也可绑定耳机键、下发广播 |
| 灭屏直接说 | ❌ 必须亮屏开界面 | ✅ 常驻前台服务 + 唤醒锁，屏幕全程保持熄灭 |
| 用手机已装的讯飞 | ✅（走输入法） | ✅ 两种：输入法模式 / 识别服务模式 |
| Windows 端 | 多为 Python + 剪贴板 | 单文件零依赖，**自动判断有没有输入光标** |

---

## 目录结构

```
pc/keyvoice_server.py          Windows 10 接收端（Python 标准库，无需 pip）
app/src/main/java/com/keyvoice/
    MainActivity.java          设置界面
    VoiceService.java          常驻前台服务：灭屏听写的核心
    VoiceInputActivity.java    亮屏时的底部输入条（输入法模式）
    KeyTriggerService.java     无障碍服务：截获实体按键
    TriggerReceiver.java       广播触发入口（adb / KeyMapper / Tasker）
    VoiceTileService.java      下拉快捷设置磁贴
    BootReceiver.java          开机自启
    Sender.java / Discover.java / Config.java / Trigger.java
```

---

## 一、Windows 10 端（先跑这个）

零依赖，Python 3 自带库即可。

```bat
cd pc
python keyvoice_server.py          :: 默认 17890 端口
python keyvoice_server.py 9000     :: 换端口
```

启动后会打印本机局域网 IP，例如：

```
 手机填的电脑地址:  192.168.1.5   端口: 17890
 有输入光标 -> 上屏；没光标 -> 只存剪贴板
```

**上屏逻辑（就这么简单）：**

- 调 `GetGUIThreadInfo`，若 `GUI_CARETBLINKING`（光标正在闪烁）或存在 `hwndCaret` → 判定"有输入光标"
- 少数软件（Chrome / Electron / VS Code / 微信）不注册系统 caret，用窗口类名兜底（`Edit`、`RichEdit`、`Chrome_WidgetWin_1`、`Scintilla`、`QWidget`…）
- 有光标：写剪贴板 → `Ctrl+V` 上屏
- 没光标：只写剪贴板，不动键盘

> 防火墙：首次运行若弹出"是否允许访问网络"，选**允许**（局域网）。否则手机连不上。

---

## 二、Android 9 手机端

### 安装

```bat
gradlew assembleDebug
```
APK 在 `app\build\outputs\apk\debug\app-debug.apk`，装到 Android 9 手机即可。

### 配置（App 打开后）

1. **搜索局域网里的电脑** → 选中后自动填好 IP 和端口（电脑端要先跑起来）。搜不到就手填。
2. **语音来源** 二选一：
   - *用手机上装的输入法（讯飞）语音输入* —— 亮屏用。触发后底部弹出输入条，按讯飞输入法的语音按钮说话，停 1.2 秒自动发走。
   - *直接调用识别服务（可灭屏、无需界面）* —— **灭屏必须用这个**。下面下拉框会列出手机里所有识别服务，带「讯飞」字样的优先选它。
3. **触发方式**：
   - 想用**实体按键**：先点「打开系统无障碍设置」→ 找到 *按键语音 · 按键监听* → 打开；回到 App 点「学习按键」，按下你要用的那个键（侧键、音量键、F1~F12、扫描键等都行）。可勾选长按触发防误触。
   - 想用**耳机/蓝牙媒体键**：勾选「耳机/蓝牙媒体键也能触发」，灭屏按一下耳机键即可。
   - 也可以用**下拉快捷磁贴**「语音输入」。
   - 或者发广播（adb / KeyMapper / MacroDroid / Tasker 都行）：
     ```
     adb shell am broadcast -a com.keyvoice.TRIGGER -n com.keyvoice/.TriggerReceiver
     adb shell am broadcast -a com.keyvoice.SEND_TEXT --es text "直接发这句话" -n com.keyvoice/.TriggerReceiver
     ```
4. **灭屏听写**：勾选「常驻前台服务」，点「启动常驻服务」。之后按电源键灭屏，按触发键 → 说话 → 文字自动上屏到电脑，屏幕不亮。勾选「连续听写」可以一直说，再按一次键停止。
5. 识别服务模式下，记得点「授予麦克风权限」。

### 关于「讯飞」

- **输入法模式**：只要手机装了讯飞输入法并设为当前输入法，就能直接用它的语音识别，不需要任何 key。
- **识别服务模式**：依赖手机上注册了 `android.speech.RecognitionService` 的组件。讯飞输入法的部分版本 / 「讯飞语音引擎」会注册。App 的下拉框会自动列出并标出「（讯飞）」；如果列表里没有讯飞，就装一个**讯飞语音引擎**，或者改用输入法模式（亮屏）。

### 为什么灭屏要常驻前台服务

Android 9（API 28）起，**后台应用拿不到麦克风**（录音会返回静音）。只有前台服务、或前台 Activity 才能录音。所以灭屏听写必须：

- `startForeground()` 常驻 + 通知栏常驻
- 录音期间持有 `PARTIAL_WAKE_LOCK`（CPU 不睡）+ `WifiLock`（灭屏时 WiFi 不断，保证发得出去）
- 全程无界面 → 屏幕保持熄灭

---

## 三、通信协议

手机 → 电脑：`POST http://<电脑IP>:<端口>/text`

```json
{ "text": "识别出的文字", "action": "paste", "ts": 1730000000000 }
```

- `action`: `paste` = 有光标就上屏、没光标存剪贴板；`copy` = 永远只存剪贴板
- 返回 `{"ok": true, "msg": "..."}`

电脑 → 手机：UDP 广播到 `255.255.255.255:17891`，内容 `KEYVOICE_HELLO:<端口>`，手机点「搜索」时监听 3 秒即可自动发现。

---

## 四、常见问题

**手机搜不到电脑**
- 电脑端程序是否在运行；两者是否同一 WiFi
- Windows 防火墙是否拦了 Python（首次运行弹窗要点允许）
- 手机 WiFi 的"组播/广播过滤"：部分路由器开了 AP 隔离，关掉或改用手动填 IP

**灭屏按键没反应**
- 常驻服务是否启动（通知栏有没有「按键语音」）
- 麦克风权限给了没
- 部分 ROM 灭屏时不把音量键/侧键派发给无障碍服务 → 改用**耳机媒体键**或 **adb 广播**触发，这两条路是通的

**识别服务列表里没有讯飞**
- 装「讯飞语音引擎」，或亮屏时用输入法模式

**上屏到错误的位置**
- 接收端只往"当前前台窗口的焦点控件"粘贴。发之前先把电脑光标点到目标输入框

**终端 / vim / 远程 ssh 里粘贴不了**
- 这类程序不接受 `Ctrl+V`（本项目精简版只做剪贴板+Ctrl+V）。需要的话把接收端换成逐字 `SendInput(KEYEVENTF_UNICODE)` 即可

---

## 五、许可

MIT
