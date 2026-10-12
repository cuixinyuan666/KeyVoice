# KeyVoice · 按键语音

**Android 9 上按一个实体键 → 用手机上装的讯飞做语音转文字 → 通过 adb 送到 Windows 10 → 显示在置顶悬浮框里，有光标就直接上屏。**

屏幕可以是黑的：灭屏按一下键，说完自动上屏，屏幕全程不亮。

```
[实体按键 / 耳机键 / 快捷磁贴 / 广播]  →  手机端 App（语音转文字）
                                              │ adb reverse 隧道（USB）
                                              ▼
                                    Win10 置顶悬浮接收框
                                              │
                        有输入光标 → Ctrl+V 上屏；没有光标 → 留在框里/剪贴板
```

---

## 和 GitHub 上已有方案的区别

现成项目（`mofanx/voice-input`、`QAA-Tools/qaa-airtype`、`franj/PhoneMic`、`signalband/Sinput`、`enximi/tele_type_phone`）思路都是：**手机上打开网页/App → 点按钮说话 → 发到电脑**。

它们能用，但缺的正是本项目的重点：

| | 现有项目 | KeyVoice |
|---|---|---|
| 实体/自定义按键触发 | ❌ 要先摸屏幕点按钮 | ✅ 无障碍服务截获物理按键，也能绑耳机键、收广播 |
| 灭屏直接说 | ❌ 必须亮屏 | ✅ 常驻前台服务 + 唤醒锁，屏幕保持熄灭 |
| 通道 | 全部走局域网 | ✅ **adb（USB）为主**，局域网可选 |
| Windows 端 | 多为命令行 + 剪贴板 | ✅ 置顶悬浮框：显示 / 复制 / 全部清除 / 关闭 |
| 用手机已装的讯飞 | ✅（输入法） | ✅ 输入法模式 + 识别服务模式 |

---

## 一、Windows 10 端

零依赖，Python 自带库（tkinter）就能跑。

```bat
cd pc
python keyvoice_box.py            :: 默认端口 17890
python keyvoice_box.py 9000       :: 换端口
```

窗口**一直置顶**，右上角。三个按钮：

- **复制** —— 把框里全部文字放进剪贴板
- **全部清除** —— 清空
- **关闭** —— 退出

下面一个勾选项「收到即上屏」：

- 有输入光标（光标在闪）→ 写剪贴板 + `Ctrl+V` 上屏
- 没有光标（桌面、资源管理器等）→ 只留在框里并写入剪贴板，不动键盘

**光标怎么判断**：调 `GetGUIThreadInfo`，看 `GUI_CARETBLINKING`（光标正在闪烁）和 `hwndCaret`；少数软件（Chrome / Electron / VS Code / 微信）不注册系统 caret，用窗口类名兜底（`Edit`、`RichEdit`、`Chrome_WidgetWin_1`、`Scintilla`、`QWidget`…）。

---

## 二、adb 通道（不用局域网）

手机插 USB，电脑上执行一次：

```bat
adb reverse tcp:17890 tcp:17890
```

这条命令把**手机自己的 `127.0.0.1:17890`** 映射到**电脑的 `17890`**。所以手机 App 发往 `127.0.0.1:17890`，就等于发到电脑，全程不经过 WiFi。

App 里「传输方式」选 **adb（USB 线，不需要局域网）** 即可（默认就是这个）。

> 拔线或重启手机后 `adb reverse` 会失效，重新插线再执行一次。

其它触发/发送方式（都走同一条通道）：

```bat
adb shell am broadcast -a com.keyvoice.TRIGGER -n com.keyvoice/.TriggerReceiver
adb shell am broadcast -a com.keyvoice.SEND_TEXT --es text "直接发这句话" -n com.keyvoice/.TriggerReceiver
```

**adb 要不要 root？** **不需要。** 只要开「开发者选项 → USB 调试」。`adb reverse` 是系统 `adbd` 提供的能力，普通权限即可，也不需要解锁 bootloader。

**能不能开手机热点把 Win10 和手机连起来？** 能，但没必要 —— adb 走 USB 更稳、延迟更低、也不占 WiFi。如果你确实想无线：

1. 手机开热点，电脑连上（这时两者其实已在同一个网段，直接用局域网模式即可）
2. 想无线 adb：先插一次 USB 执行 `adb tcpip 5555`，再 `adb connect 手机IP:5555`，之后 `adb reverse` 照常可用。Android 9 没有「无线调试」开关，这一步是否需要 root 因 ROM 而异，**插线方案最省心**。

---

## 三、Android 9 手机端

### 配置步骤

1. 电脑上先跑 `python pc/keyvoice_box.py`，再执行 `adb reverse tcp:17890 tcp:17890`
2. App 里「传输方式」保持 **adb**；端口填 17890 → 点「测试发送」，电脑悬浮框应出现一行测试文字
3. **语音来源** 二选一：
   - *用手机装的输入法（讯飞）语音输入* —— 亮屏用。触发后底部弹输入条，按讯飞输入法语音键说话，停 1.2 秒自动发走
   - *直接调用识别服务（可灭屏、无需界面）* —— **灭屏必须用这个**。下拉框列出手机里所有识别服务，带「讯飞」字样的优先选
4. **触发方式**：
   - 实体键：点「打开系统无障碍设置」→ 开 *按键语音 · 按键监听* → 回 App 点「学习按键」→ 按下你要用的键（侧键、音量键、F1~F12、扫描键都行）。可勾「长按触发」防误触
   - 耳机/蓝牙媒体键：勾上即可，**灭屏可用**
   - 下拉快捷磁贴「语音输入」
   - adb 广播（上面有命令）
5. **灭屏听写**：勾「常驻前台服务」→ 点「启动常驻服务」。之后灭屏，按触发键说话，文字自动上屏到电脑，屏幕不亮。勾「连续听写」可一直说，再按一次停止
6. 识别服务模式下要点「授予麦克风权限」

### 关于「讯飞」

- **输入法模式**：手机装了讯飞输入法并设为当前输入法即可，不需要任何 key
- **识别服务模式**：依赖注册了 `android.speech.RecognitionService` 的组件。讯飞输入法的部分版本 / 「讯飞语音引擎」会注册，App 会自动列出并标「（讯飞）」；列表里没有就装一个**讯飞语音引擎**，或亮屏走输入法模式

### 灭屏为什么要常驻前台服务

Android 9（API 28）起后台应用拿不到麦克风（录音返回静音），只有前台服务/前台 Activity 能录音。所以灭屏听写必须：

- `startForeground()` 常驻 + 通知栏常驻
- 录音期间持 `PARTIAL_WAKE_LOCK`（CPU 不睡）+ `WifiLock`（网络不断）
- 全程无界面 → 屏幕保持熄灭

---

## 四、编译与签名

```bat
gradlew assembleDebug      :: 调试包 app\build\outputs\apk\debug\app-debug.apk
gradlew assembleRelease
```

**正式签名**：把你的 `.jks` 放到项目根目录，复制 `keystore.properties.example` 为 `keystore.properties` 并填四行，然后 `gradlew assembleRelease`，产出即是用你正式签名签好的 `app-release.apk`。

没有 `keystore.properties` 时**不会**偷偷用测试签名，产出的是 `app-release-unsigned.apk`（未签名，交给你的正式签名流程）。签名文件与密码都在 `.gitignore` 里，不会进仓库。

---

## 五、通信协议

手机 → 电脑：`POST http://127.0.0.1:<端口>/text`（adb 模式）或 `http://<电脑IP>:<端口>/text`（局域网模式）

```json
{ "text": "识别出的文字", "action": "paste", "ts": 1730000000000 }
```

- `action`: `paste` = 有光标就上屏、没光标只入框/剪贴板；`copy` = 永不上屏
- 返回 `{"ok": true}`

局域网模式下的自动发现：电脑向 `255.255.255.255:17891` 广播 `KEYVOICE_HELLO:<端口>`，手机点「搜索电脑」监听 3 秒即可列出。adb 模式不需要发现过程。

---

## 六、常见问题

**悬浮框没反应 / 手机发不出**
- 电脑端程序是否运行；`adb reverse` 是否执行过（`adb reverse --list` 可查看）
- `adb devices` 能否看到手机；手机是否点了「允许 USB 调试」

**灭屏按键没反应**
- 常驻服务是否启动（通知栏有没有「按键语音」）
- 麦克风权限给了没
- 部分 ROM 灭屏时不把音量键/侧键派发给无障碍服务 → 改用**耳机媒体键**或 **adb 广播**

**识别服务列表里没有讯飞** → 装「讯飞语音引擎」，或亮屏用输入法模式

**上屏到错误位置** → 接收端只往「当前前台窗口的焦点控件」粘贴，发之前先把电脑光标点到目标输入框

**终端 / vim / ssh 里粘不上** → 这些程序不接受 `Ctrl+V`，需要改成逐字 `SendInput(KEYEVENTF_UNICODE)`

---

## 许可

MIT
