# OpenMinis-Linux 1.36.14-linux

- versionCode **67**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

会话可以归进「工作区」项目文件夹，同一工作区里的多个会话共享磁盘上的 `workspace` / 附件 / 浏览器缓存，日记仍按会话隔离。折叠的「AI 过程」条只显示标题和思考/工具计数，不再铺工具芯片。旧版全局日记会迁进各会话。模型直通请求支持 multipart，密钥不进沙箱。

## 变更

1. **项目工作区** — 主页分组即项目。同一文件夹里的会话共享 `minis-workspaces/<id>/{workspace,attachments,offloads,browser}`，挂到 `/var/minis/` 对应目录；`memory` 仍在 `minis-sessions/<id>/memory`，删会话只清该会话日记，不拆掉项目文件。
2. **默认工作区** — 主页右下角 FAB（以及空态「开始对话」）改为「新建工作区会话」，图标为新建文件夹。优先放进当前展开的工作区；没有则创建/使用默认「工作区」。工作区卡片菜单仍可在该项目里新建会话。长按 FAB 仍选模型组。
3. **升级兼容** — 1.36.13 之前、还没有隔离文件的旧会话会归入默认工作区，并尽量用残留的 `minis-global/{workspace,attachments,...}` 灌种。已经按 1.36.13 隔离过的会话不强制合并，避免冲掉各自的文件。
4. **日记迁移** — 把 `minis-global/memory/YYYY-MM-DD.md` 拷进每个会话的 memory；`GLOBAL.md` / `SOUL.md` / `LEARNED.md` / personas 仍全局。只拷一次，已有文件不覆盖。
5. **AI 过程折叠** — 折叠条只保留「AI过程 / 思考* / 工具*」。展开后列出思考和工具行（折叠条不再带工具芯片，完成工具仍可点开）。
6. **HttpBody 直通** — `rawPassthroughRequest` 按 JSON / multipart / 原始字节推导 Content-Type；multipart 由 OkHttp 生成 boundary，用户头不能盖掉。密钥仍走 provider 的 `applyKeyAuth`，不在 PRoot 里 `curl -F`。`minis-model-use` 支持 `body_kind=multipart`。超大请求有体积/堆内存门闩。
7. **清理** — 去掉已无入口的 prompt-templates / persona-extension 字符串。

## 兼容性

- 数据库仍为 Room 16，无迁移。工作区文件夹沿用已有 `folders` 表；默认工作区 id 为 `default-workspace`。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.13（versionCode 66）会被 67 覆盖。
- 未分组会话仍按 1.36.13 一会话一工作区；一旦放进项目文件夹，就改用共享项目目录。
