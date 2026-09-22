# OpenMinis-Linux 1.36.18-linux

- versionCode **71**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

修沙箱引导：Android 系统 CA 写入客户机，apt/curl/git 不再报「无有效 CA 链」；`minis-mirror` 不靠 curl；dpkg 锁先全局串行再清残留；开机种子安装 curl/wget/python3/git/node；工具打开链接不再把应用界面踢走。

## 变更

1. **主机侧 CA** — `AndroidCAStore` 优先（Android 14+ APEX）；空则扫 `/apex/com.android.conscrypt/cacerts`、`/system/etc/security/cacerts`（空目录不算）、用户 CA。证书拷进客户机，不符号链接到 PRoot 看不见的 Android 路径。
2. **镜像** — `/dev/tcp` HTTP 快筛 + 临时 sources.list 的 `apt-get update`；HTTPS 失败改 HTTP；SJTU 进入回退链。
3. **锁** — 先 flock（失败 mkdir），再 fuser/删 dpkg 锁，再 `dpkg --configure -a`。
4. **种子** — 开机装 curl/wget/python3/git；nodejs 尝试一次。`minis-dev-setup` 去掉 `set -e`，锁冲突会重试。
5. **minis-open** — 无 TTY 也发 OSC，应用内预览；`--system` 才 ACTION_VIEW。

## 兼容性

- 会话库仍为 Room 16。Provider 库 v5。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.17（versionCode 70）会被 71 覆盖。
