# OpenMinis-Linux 1.36.12-linux

- versionCode **65**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

检查更新不再在「没给安装权限 / 跳系统设置被杀进程 / 安装器只是唤起」时卡死或重下已下完的 APK。确认按钮按状态显示打开设置 / 立即安装 / 下载，完成后不再灰掉。

## 变更

1. **下载前先要权限** — 未授权「安装未知应用」时把请求写进磁盘再跳设置；回来后自动继续下载。
2. **唤起安装器不清 pending** — 打开系统安装界面不等于装完。记录在运行版本追上目标版本后才退休。
3. **复用已下载的 APK** — 磁盘上已有完整且大小匹配的包直接安装，不再从 0 重下。
4. **确认按钮三分支** — 打开设置 / 立即安装 / 下载；下载中以外都可点。
5. **生命周期观察者配对注销** — 避免进设置页多次后一次恢复弹多个安装界面。
6. **冷启动可续上** — 进程被杀后从磁盘重读 pending，不再只靠内存里的 `remember`。

## 兼容性

- 数据库仍为 Room 16，无迁移。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.11（versionCode 64）会被 65 覆盖。
