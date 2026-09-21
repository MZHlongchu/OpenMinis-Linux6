# OpenMinis-Linux 1.36.9-linux

- versionCode **62**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

热修 1.36.8：AI 过程折叠会收起已完成的底部工具条；release 包 `execute_code` 不再因 Rhino VMBridge 被 R8 裁掉而崩溃。

## 变更

### 聊天

1. **折叠浮动工具条** — 开启「外观 → 深度思考 → AI 过程折叠」后，已完成工具不再留在底部浮动条，只保留 STREAMING/PENDING/RUNNING。点摘要可展开思考和工具。
2. **偏好即时生效** — SharedPreferences 回调切回主线程；聊天页 `ON_RESUME` 重读外观开关。
3. **回合未结束不收起** — `isAwaitingModelResponse` 期间保持展开。

### 崩溃与体检

4. **Rhino keep** — `-keep class org.mozilla.javascript.**` / `org.mozilla.classfile.**`，修复 minify release 里 `execute_code` 的 `ExceptionInInitializerError`。
5. **SharedHttpClients** — Anthropic / Gemini / OpenAI / OpenRouter / Antigravity 目录 API 共用一个默认 OkHttpClient。
6. **WebViewHolder** — `destroy()` 幂等；`rememberWebViewHolder` 离开组合时释放，避免预览 WebView 残留 RenderThread。
7. **无障碍双击** — 第二次点击前 `awaitA11yEvent(80)`，去掉 `Thread.sleep`。

## 兼容性

- 数据库仍为 Room 16，无迁移。
- 折叠默认关闭，需在外观设置打开。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.8（versionCode 61）会被 62 覆盖。
