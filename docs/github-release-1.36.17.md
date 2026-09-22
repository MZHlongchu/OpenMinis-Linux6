# OpenMinis-Linux 1.36.17-linux

- versionCode **70**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

已归档会话每次启动都会把仍留在私有目录的共享文件搬进项目工作区，修掉 1.36.14 只写分组、不搬文件的半归档。从分组移出或解散分组时，共享文件会拷回该会话；有冲突则保留项目副本。会话列表右下角只留一个「新建文件夹」FAB。

## 变更

1. **启动收敛** — `warmup` → 恢复中断的 `.staging` → `reconcileWorkspaceFiles` → 一次性迁移。已有 `folder_id` 的会话把私有 `workspace` / 附件 / 浏览器 / offloads 搬进项目。
2. **移出分组** — 先记下原项目，再清 `folder_id`，再把项目树拷回会话私有目录。
3. **解散分组** — 成员变未分组的同时走同一套拷回；没有剩余成员且全部拷回成功才删项目目录。
4. **冲突不删** — 私有子目录已经有文件时不覆盖、不把该会话当成可独立于项目，避免删掉共享侧唯一副本。
5. **自动归档搬文件** — `setFolderIfUnfiled` 写库后立刻 `moveSessionIntoProject`（与启动收敛一样幂等）。
6. **单 FAB** — 去掉叠在主按钮上的「新会话」小 FAB；新对话从工作区卡片或长按模型组进入。

## 兼容性

- 会话库仍为 Room 16。Provider 库 v5。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.16（versionCode 69）会被 70 覆盖。
