# OpenMinis-Linux 1.36.16-linux

- versionCode **69**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

终端换成 Termux VT 仿真。提供商可置顶，并可一键并行强制刷新模型列表。沙箱启动先自愈 apt 镜像，再重试失败的 dpkg/pip 世界，重置 Linux 会先快照再只装缺失的包。一级设置页去掉返回箭头。系统「选择文字」可直接送进会话。技能 `requirements.json` 按 Debian 解析，环境变量页能看到平台集成缺口。本版也带上已合入 main、但没有单独打正式 tag 的 1.36.15：遗留会话归档进工作区、工具写文件原子化。

## 变更

1. **Termux 终端** — PTY 界面改用 `terminal-view:0.118.0`，去掉自研 ANSI emulator / canvas 和 `pty_bridge` JNI。
2. **提供商置顶** — 列表按置顶分组；菜单可设为常用；一键并行强制刷新全部服务商模型（不走缓存）。Provider 库升到 v5，带 `pinned` 列迁移。
3. **apt 镜像自愈** — 客户机 `minis-mirror auto` 探测源是否可用并改写 `/etc/apt/sources.list`。启动顺序：overlay → 时区 → 镜像配置 → **先** `minis-mirror auto` **再** dpkg 重试 **再** pip 重试，避免死源烧掉重试次数。
4. **dpkg / pip 世界** — 重置前快照 `apt-mark showmanual` 与 pip extras；还原只安装当前 dpkg 库里没有的包（`--no-upgrade`）。失败队列最多 3 次，重试前再跑镜像自愈。启动后异步再 dump 一份。
5. **时区** — `/etc/localtime` 用相对 symlink（`Paths.get`，不再把相对路径解析成主机绝对路径），并写入 `/etc/timezone`。
6. **一级设置** — 多数一级页无返回箭头；Web 搜索列表无箭头，引擎详情保留。存储下的 Rootfs、多智能体下的工具限额仍有箭头。
7. **选择文字分享** — `PROCESS_TEXT` 把选中文本送进待分享缓冲，菜单名用应用名 Minis Ultra。
8. **技能 requirements** — `env` / `tiers` 改为 Map；优先读 `apt`，兼容遗留 `apk`。环境变量页按技能显示平台集成卡片（已配置 / 缺失）。
9. **工作区归档（1.36.15）** — 遗留会话会归档进项目工作区；`WorkspaceMover` 经 `.staging` 校验字节再替换；中断的移动下次启动恢复。项目目录缺失时 `hostDir()` 回退到会话私有副本，避免 `/var/minis` 空掉。
10. **原子写文件（1.36.15）** — 编辑/写入走按路径锁 + 临时文件 rename，写完回读校验；失败对调用方报错而不是假装成功。
11. **会话列表 FAB（1.36.15）** — 主 FAB 新建工作区文件夹，上方小 FAB 开新会话；长按仍是模型组。
12. **子代理条（1.36.15）** — 结束后从直播条移除，避免失败后芯片一直钉在顶上。过程摘要在反向列表里放到内容块之后，长回复也能碰到。
13. **仓库** — 本 fork 不再携带 `src/ios/`。

## 兼容性

- 会话库仍为 Room 16。Provider 库 **v5**（新增 `pinned`，迁移 `MIGRATION_4_5`）。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.15（versionCode 68）或 1.36.14（67）会被 69 覆盖。
- 重置 Linux 环境会先 dump 再还原用户 apt/pip 包；出厂已装的包不会被 `--no-upgrade` 升级。
