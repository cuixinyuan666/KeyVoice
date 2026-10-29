## KeyVoice v0.4.2

### 手机端（APK）
- **文件互传**：手机 ↔ 电脑双向传任意类型文件，流式落盘，不占内存
  - 手机 → 电脑：App「文件」页选文件即传，电脑端存到 `%USERPROFILE%\Downloads\KeyVoice`
  - 电脑 → 手机：电脑端「发文件」，手机端存到 `Download/KeyVoice`
- **手机控电脑（网页远控）**：App「远控」页内置 WebView，打开电脑端「远控」开关即可看到电脑屏幕并操作，手机端零安装
- **电脑控手机**：电脑端「手机投屏」一键检测/启动 scrcpy，USB / 无线 ADB 均可，手机无需装 App
- **投屏省电模式**：投屏时手机自动黑屏省电，鼠标移入即亮屏可操控（亮度 0 方案，Android 15 下黑屏仍可接收输入）
- 设备计数去重，多设备场景显示更准

### 电脑端（Python，免安装免编译）
- 集成 scrcpy 一键下载与启动（`pc/下载scrcpy.bat` / `python pc/get_scrcpy.py`）
- 锁屏黑屏自动点亮、ADB 防掉线
- `make_shortcut.py` 快捷方式路径改为按当前机器推导，不再写死作者本人的绝对路径
- 依赖不变：仅 Python 标准库 + tkinter（远控功能可选 `pip install pillow`）

### 升级说明
- APK 为 debug 签名，与历史版本证书一致，可直接覆盖安装
- 装完如遇 Android 15 提示「未安装应用」，按 README 的 Android 15 安装说明操作即可
