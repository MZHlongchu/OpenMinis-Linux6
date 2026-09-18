# Minis Ultra 1.20-linux

- versionCode **32**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本，见 [docs/SIGNING.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/SIGNING.md)。

## 更新说明

1. **跨会话检索** — `search_sessions` / `read_session`，不必再绕 CLI。
2. **子 Agent** — `kind=worker|explore|plan`、`write_paths`、`max_turns`；explore/plan 只读。
3. **系统助手 + 小组件** — `ACTION_ASSIST` 与主屏新建对话。
4. **browser_use / 内置浏览器** — 目标 Chrome/151，实际跟系统 WebView；HTML 走 BrowserSheet。
5. **crash_handler** — 交叉编译 `libunwind.a`，`_Unwind_Backtrace` 可链接。

完整说明：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)
