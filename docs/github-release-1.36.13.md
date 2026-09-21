# OpenMinis-Linux 1.36.13-linux

- versionCode **66**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

每个会话变成独立工作区，互不干扰；技能等工具装在工作区外给所有会话用。删除会话会删掉该工作区全部文件（含记忆）。内置人格改成详细版，本版安装时强制覆盖一次 `SOUL.md`。人格扩展已移除。

## 变更

1. **一会话一工作区** — `/var/minis/{workspace,memory,attachments,offloads,browser}` 按会话隔离；复制会话会连工作区一起拷走。
2. **工具全局共享** — `/var/minis/{skills,shared,mcp-servers}` 以及客户机系统前缀对所有会话可见。
3. **删会话清记忆** — `minis-sessions/<id>/` 整棵删除，包括该会话的 daily log。
4. **内置人格** — 详细 Minis Ultra：工作区边界、共享工具、记忆范围。Settings → Memory 仍是应用级 GLOBAL.md，不是 `/var/minis/memory`。
5. **本版一次覆盖 SOUL.md** — 不论用户是否改过，安装后写一次新人格，之后不再动。
6. **移除人格扩展** — 设置、会话菜单、deeplink 不再进入提示词模板 / 工作区规则页。

## 兼容性

- 数据库仍为 Room 16，无迁移。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.12（versionCode 65）会被 66 覆盖。
- 旧版写在 `minis-global/memory/` 里的日记不会自动迁进各会话；新日记写在当前会话工作区。
