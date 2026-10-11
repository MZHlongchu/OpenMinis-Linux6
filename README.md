<p align="right"><a href="#english">English</a> · <b>中文</b></p>

# Minis Ultra

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#下载)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

Android arm64 上的私人 AI Agent。把兼容接口接到手机里的一台 Ubuntu 24.04：能装包、跑脚本、用浏览器、技能和记忆，也能派出子代理。

包名 `com.openminis.linux`，启动器名称 **Minis Ultra**。可与上游 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）并排安装。检查更新只指向本仓库。

本仓库以 [GNU GPL v3.0](LICENSE) 发布。它包含并修改了 GPLv3 程序，因此衍生作品也必须按 GPLv3 提供对应源码。沙箱链接的 [PRoot](https://github.com/proot-me/PRoot/) 为 GPLv2，合并分发时按 GPLv3 处理。第三方组件的名称、版本和许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 下载

当前版本见 [Releases](https://github.com/tall-1997/OpenMinis-Linux/releases)。安装包名为 `minis-ultra-com.openminis.linux.apk`。

- 正式版：带版本号的 tag（例如 `1.36.36-linux`）
- 滚动构建：[android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)（`main` 每次成功构建覆盖）

侧载前允许「安装未知应用」。debug 签名不能覆盖另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。

## 能做什么

- **沙箱**：Ubuntu 24.04 arm64，跑在 PRoot 里。客户机命令、主机 `su`、共享存储见 [LINUX.md](LINUX.md)。
- **会话**：未分组时一会话一工作区；归入项目后共享工作区、附件和浏览器缓存，日记仍按会话隔离。
- **模型**：接兼容接口；缺参数的模型 id 会按目录补全上下文、输出长度和思考档位。
- **多智能体**：主会话调度子代理。设置 → 多智能体。
- **技能与记忆**：全局技能；本会话日记在 `/var/minis/memory`，`GLOBAL.md` 在设置里单独维护。
- **社区与技能生态**：上游 OpenMinis 的用例合集 [AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)、技能库 [MinisSkills](https://github.com/OpenMinis/MinisSkills) 与媒体报道见下方 [English](#english) 的 *Upstream and community* 一节。

## 从源码构建

PRoot 和 Ubuntu rootfs 在构建时生成，不进仓库。需要 NDK r29（`29.0.14206865`）。远端备份用的 rclone AAR 是可选的：要它就得有 Go 1.26+ 与 gomobile，没有它 APK 照样编译，只是远端备份目标不可用。完整步骤见 [BUILDING.md](BUILDING.md)。

```sh
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
# 可选：远端备份（需要 Go 1.26+ 与 gomobile）
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
cd src/android && ./gradlew :app:assembleDebug
```

国内镜像见 [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)。不要用 sdkmanager 的 x86_64 包覆盖客户机里的 aarch64 `aapt2`。

## 目录

```
src/android/    Android 应用（Kotlin / Compose）与 JNI
src/shared/     共享资源
deps/           原生依赖构建脚本
docs/           说明与发行注记
scripts/        rootfs 与开发脚本
```

## 引用

- 应用源码与本仓库修改：© 各贡献者，[GPL-3.0](LICENSE)
- [PRoot](https://github.com/proot-me/PRoot/)（GPL-2.0），本仓库子模块 `deps/proot` 基于 [OpenMinis/proot](https://github.com/OpenMinis/proot)
- proot ELF loaders（GPL-2.0），随 APK 分发的 Termux 构建：`src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so`、`libproot-loader32.so`
- [talloc](https://talloc.samba.org)（LGPL-3.0-or-later）
- Ubuntu 24.04 arm64 根文件系统：构建时下载，许可随 Canonical / Ubuntu 软件包
- [rclone](https://github.com/rclone/rclone)（MIT），远端备份的 gomobile 绑定；构建时生成，不进仓库
- Android SDK / AndroidX、OkHttp、Kotlin 协程等：见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

问题与功能请求请提到 [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues)。

---

<p align="right" id="english"><b>English</b> · <a href="#minis-ultra">中文</a></p>

# Minis Ultra

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#download)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

A private AI agent for Android arm64. It connects compatible model APIs to an Ubuntu 24.04 guest on the phone: packages, scripts, a browser, skills, memory, and sub-agents.

Package id `com.openminis.linux`. Launcher name **Minis Ultra**. It can be installed beside upstream [OpenMinis](https://github.com/OpenMinis/OpenMinis) (GPL-3.0). Update checks point only at this repository.

This repository is published under the [GNU GPL v3.0](LICENSE). It contains and modifies GPL-3 programs, so derivative works must ship corresponding source under GPL-3. The sandbox links [PRoot](https://github.com/proot-me/PRoot/) (GPL-2.0); the combined work is distributed under GPL-3. Names, versions, and licenses of other components are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Download

Current builds are on [Releases](https://github.com/tall-1997/OpenMinis-Linux/releases). The APK is `minis-ultra-com.openminis.linux.apk`.

- Versioned tags (for example `1.36.36-linux`)
- Rolling build: [android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest) (replaced on each successful `main` build)

Allow unknown-app installs before sideloading. A debug signature cannot replace an install signed with a different certificate. See [docs/SIGNING.md](docs/SIGNING.md).

## What it does

- **Sandbox**: Ubuntu 24.04 arm64 under PRoot. Guest commands, host `su`, and shared storage are described in [LINUX.md](LINUX.md).
- **Sessions**: an ungrouped session has its own workspace; sessions in a project share the workspace, attachments, and browser cache. The diary stays per session.
- **Models**: compatible endpoints. A model id missing limits is filled from the catalog (context, output, thinking).
- **Agents**: the main session can dispatch sub-agents. Settings → Agents.
- **Skills and memory**: global skills; the session diary lives at `/var/minis/memory`. `GLOBAL.md` is edited separately in Settings.

## Upstream and community

Minis Ultra is the Linux/Android fork of
[OpenMinis](https://github.com/OpenMinis/OpenMinis), and the ecosystem around the
upstream app applies here unchanged:

- **Community use cases** —
  **[AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** collects
  user-contributed workflows: logging a meal from a photo, turning group chat into
  tracked tasks, mounting an Obsidian vault, filing a page into a calendar event.
- **Skills** — a skill is a folder with a `SKILL.md` (instructions, plus optional
  scripts, references and assets) that the agent loads on demand when a request
  matches it. Metadata stays in context for triggering; the body and bundled
  resources load only once the skill is used. Skills written for Claude, Codex,
  OpenClaw or Hermes Agent generally run here as-is; ones adapted to Minis' tools
  do more, because they can reach the Linux shell, device integrations and native
  offloads directly. **[MinisSkills](https://github.com/OpenMinis/MinisSkills)**
  carries both kinds.
- **Press** — "the most impressive indie app I've seen in a while"
  ([MacStories](https://www.macstories.net/reviews/open-minis-is-the-ios-agent-i-wish-siri-ai-could-be/));
  「这可能是 iPhone 最强 Agent 软件，没有之一」
  ([知乎](https://zhuanlan.zhihu.com/p/2045570157783807562));
  「可能是 iOS 端最强 AI Agent」([小众软件](https://www.appinn.com/open-minis/)).
- **Upstream releases** — the iOS TestFlight beta and the upstream APK are on
  [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/releases). This
  repository's own builds are in [Download](#download) above.

## Build from source

PRoot and the Ubuntu rootfs are produced at build time and are not stored in git. NDK r29 (`29.0.14206865`) is required. The rclone AAR for remote backups is optional: it needs Go 1.26+ and gomobile, and without it the APK still builds — only remote backup destinations are unavailable. Full steps are in [BUILDING.md](BUILDING.md).

```sh
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
# optional: remote backups (needs Go 1.26+ and gomobile)
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
cd src/android && ./gradlew :app:assembleDebug
```

Do not replace the guest's aarch64 `aapt2` with the sdkmanager x86_64 package. Mirrors: [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md).

## Layout

```
src/android/    Android app (Kotlin / Compose) and JNI
src/shared/     Shared resources
deps/           Native dependency build scripts
docs/           Notes and release notes
scripts/        Rootfs and development scripts
```

## Citations

- Application source and modifications in this repository: © contributors, [GPL-3.0](LICENSE)
- [PRoot](https://github.com/proot-me/PRoot/) (GPL-2.0). The `deps/proot` submodule tracks [OpenMinis/proot](https://github.com/OpenMinis/proot)
- proot ELF loaders (GPL-2.0), Termux builds shipped in the APK: `src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so` and `libproot-loader32.so`
- [talloc](https://talloc.samba.org) (LGPL-3.0-or-later)
- Ubuntu 24.04 arm64 rootfs: downloaded at build time; licenses follow Canonical / Ubuntu packages
- [rclone](https://github.com/rclone/rclone) (MIT), the gomobile binding for remote backups; produced at build time, not stored in git
- Android SDK / AndroidX, OkHttp, Kotlin coroutines, and the rest: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

File issues and feature requests at [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues).
