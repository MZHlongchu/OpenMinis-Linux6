# Minis Ultra 1.36.5-linux

- versionCode **58**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

浏览器底栏、检查更新能看到说明、网页搜索可自定义。1.36.4 的聊天出视频仍在。

## 变更

### 应用内浏览器

地址栏和 UA/设置（手机图标）挪到底部导航。顶栏左侧关闭、右侧展开全屏（再点收回）。默认高度 80%。底栏补齐后退、前进、下载、刷新/停止、设置、系统浏览器打开。

### 检查更新

滚动包 `android-latest` 的 release body 往往只有 `versionCode` / `versionName`。现在会去同版本的正式 tag 补完整更新说明。对话框始终展示「更新内容」，过长可滚动。选包时按版本互相比较，不再把所有版本都和 `"0"` 比（避免滚动包盖过更高 semver）。

### 网页搜索

主列表点进各引擎二级页再配密钥或地址。新增自定义引擎：URL 模板支持 `{query}`（可选 `{key}`），JSON / HTML 都能解析。DuckDuckGo / SearXNG / Bing 的配置也改到各自二级页。

## 未改动

- 聊天视频生成（1.36.4）
- `generate_image` 仍为占位（未在 Agent 工具接线）
- 数据库版本（Room 14）、签名、包名

侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.4（versionCode 57）会被 58 覆盖。
