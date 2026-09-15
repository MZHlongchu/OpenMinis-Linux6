# Requirements Document — Android GLibc 开发套件版（proot 完整 Linux + AI Agent 深度集成）

Feature Name: android-glibc-devstack
Updated: 2026-09-15
Status: CONFIRMED — 用户已确认三项关键决策（发行版=Ubuntu/Debian；root 直通=自由模式；工具链交付=混合模式）

## Introduction

在 OpenMinis Android 端现有 proot + Alpine (musl/apk) 沙箱基础上，扩展出一个面向开发者的 GLibc 完整 Linux 发行版环境，并强化 AI Agent 与系统的集成深度。五个核心能力：

1. 真 Linux（GLibc 生态）以 proot 沙箱方式原生运行于 Android，终端体验与桌面 Linux 一致
2. 内置完整开发工具链，支持手机端直接编译 Android APK
3. AI Agent 深度集成 + 自定义工具/权限扩展，root 命令直通（经手机本机 `su`，即 Magisk/KernelSU 授权）
4. SAF 授权挂载外部目录到 `/var/minis/mounts/`
5. 与原版 OpenMinis 共存（applicationId 区分 + abstract socket 命名隔离）

## Glossary

- **Devstack Rootfs**: 本 fork 内置的 Ubuntu (GLibc) 基础 Linux rootfs（区别于原版 Alpine minirootfs）
- **GLibc 环境**: 以 glibc 为 C 库的 Linux 用户态，兼容标准发行版软件包生态（apt/dpkg，yum 同类工具生态等价可用）
- **proot**: 用户态 chroot 实现，`-0` 提供假 root，无需真实 root 即可运行完整 rootfs
- **root 直通**: Agent 通过手机本机 `su` 二进制（Magisk/KernelSU/KernelSU Next 授权）以 Android 真实 root 身份执行命令，独立于 Shizuku 通道
- **工具链套件**: Python3、GCC、Git、FFmpeg、OpenJDK、Gradle、Android SDK commandline tools + platform/build-tools
- **共存**: 本应用与原版 OpenMinis（applicationId `com.openminis.app`）可同时安装、同时运行、互不干扰
- **Abstract Socket**: Linux abstract namespace Unix socket，本产品中 native-offload 机制使用
- **SAF**: Storage Access Framework，`ACTION_OPEN_DOCUMENT_TREE` 系统目录选择器

## Requirements

### Requirement 1 — GLibc 完整 Linux 环境（proot 原生运行）

**User Story:** AS 移动端开发者, I want 在手机上运行一个完整 GLibc Linux 发行版, so that 获得与桌面 Linux 一致的终端和软件生态。

#### Acceptance Criteria

1. WHEN 用户首次启动沙箱, THE system SHALL 下载并解压 Ubuntu 24.04 (Noble) aarch64 base rootfs 到应用私有目录，并提供初始化进度反馈
2. WHEN 用户打开终端, THE system SHALL 通过 proot 启动完整 rootfs 的 bash 登录 shell，PS1、补全、历史与桌面 Linux 一致
3. WHEN 用户在沙箱内执行标准 glibc 动态链接程序, THE system SHALL 正常加载 glibc 运行时并执行
4. IF 设备存储空间不足以安装 rootfs, THE system SHALL 在安装前给出明确错误提示与所需空间大小
5. WHILE 沙箱运行, THE system SHALL 保持与原版 Alpine 沙箱相同的资源约束手段（可配置 CPU/内存友好模式）
6. WHEN 用户在沙箱内执行 apt 包管理命令, THE system SHALL 使用 Ubuntu 官方镜像源正常安装软件包

### Requirement 2 — 内置完整开发工具链（手机编译 APK）

**User Story:** AS 移动端开发者, I want 在沙箱内预置或一键安装 Python/GCC/Git/FFmpeg/JDK/Gradle/Android SDK, so that 手机上直接完成编码与 APK 构建。

#### Acceptance Criteria

1. WHEN 用户首次启动 Devstack Rootfs, THE system SHALL 预置 Python3/GCC/Git/FFmpeg/curl 等基础组件（混合交付：随 rootfs 内置）
2. WHEN 用户在设置中启用"开发工具链", THE system SHALL 按组件（jdk/gradle/android-sdk，可选 ndk）在线分阶段安装，并显示各阶段进度
2. WHEN 用户在沙箱内执行 `java -version`、`gradle -v`、`gcc --version`、`python3 --version`、`git --version`、`ffmpeg -version`, THE system SHALL 返回与桌面 Linux 相同的可用输出
3. WHEN 用户在沙箱内对标准 Gradle Android 工程执行 `./gradlew assembleDebug`, THE system SHALL 产出可安装的 APK 文件
4. IF aapt2/d8 等 Android 构建工具在 aarch64 JVM 下运行失败, THE system SHALL 启用预配置的 aapt2 替代方案或提供明确失败原因
5. WHEN JDK/Android SDK 安装完成, THE system SHALL 自动写入 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 及 PATH 环境变量到 `/etc/profile.d/`
6. WHEN 工具链安装体积超过阈值（如 3GB）, THE system SHALL 支持增量安装与按组件卸载

### Requirement 3 — AI Agent 深度集成 + 自定义工具/权限扩展 + root 直通

**User Story:** AS 高级用户, I want AI Agent 直接操作文件、执行命令、写代码、构建应用、读日志、处理音视频，并允许我通过自定义工具把 root 命令直通给 Agent, so that "手机上的 Linux"成为可执行智能体。

#### Acceptance Criteria

1. WHEN Agent 执行 `shell_execute`, THE system SHALL 在 Devstack 沙箱内执行并返回标准输出/错误/退出码
2. WHEN Agent 调用文件工具（file_read/file_write/file_edit）, THE system SHALL 通过沙箱路径解析直接读写 rootfs 与挂载目录内的文件
3. WHEN Agent 处理音视频任务, THE system SHALL 优先使用沙箱内 FFmpeg/原生 offload 完成并回传结果
4. WHEN 用户在设置中启用"root 直通"（默认自由模式：开启后 Agent 可执行任意 `su` 命令）, THE system SHALL 经手机本机 `su` 以 Android root 身份执行该命令并回传输出
5. IF `su` 不可用（未 root 或授权被拒）, THE system SHALL 返回明确的不可用状态与引导信息，Agent 侧表现为工具错误而非崩溃
6. WHEN root 直通被启用, THE system SHALL 在每次 root 命令执行前后记录审计日志（时间、命令、结果摘要）
7. WHEN 用户配置自定义工具扩展, THE system SHALL 支持以声明式配置（如 YAML/JSON）注册新工具给 Agent，含名称、描述、参数、执行方式（沙箱/su/原生 offload）
8. IF Agent 请求的工具属于危险类别（如 `pm`、包删除、系统设置写入）, THE system SHALL 按用户的权限策略（每次询问/白名单/放行）执行门禁

> 注：root 直通为独立通道，经手机本机 `su`；与现有 Shizuku 通道（`android-shizuku-cli`）并存，用户可任选。

### Requirement 4 — SAF 挂载外部目录

**User Story:** AS 用户, I want 通过系统 SAF 选择器授权后把手机目录挂载进 Linux 环境 `/var/minis/mounts/`, so that Agent 与终端可原生读写手机文件。

#### Acceptance Criteria

1. WHEN 用户在设置中添加挂载目录, THE system SHALL 拉起 `ACTION_OPEN_DOCUMENT_TREE` 选择器并持久化 URI 授权
2. WHEN 沙箱启动, THE system SHALL 将所有已授权目录以 proot bind 挂载到 `/var/minis/mounts/<显示名>`, 并在挂载点生成占位目录
3. WHEN 用户或 Agent 在挂载目录内写文件, THE system SHALL 直接写穿到手机真实目录（受用户"允许写入"开关控制）
4. IF 挂载目录被置为只读, THE system SHALL 在 guest 侧安装写保护守卫使写入操作返回明确错误
5. WHEN 挂载配置变化, THE system SHALL 在下一次 shell 启动时快照式生效，运行中会话保持冻结视图

> 注：现有实现（`MountedFoldersStore`、`PRootKernel.resolveTreeUriToHostPath`）已覆盖大部分场景，本需求以增强为主（devstack rootfs 下同样生效 + 写入体验优化）。

### Requirement 5 — 与原版 OpenMinis 共存

**User Story:** AS 已安装原版 OpenMinis 的用户, I want 本版本与原版同时安装、同时运行, so that 两版互不干扰。

#### Acceptance Criteria

1. WHEN 构建 APK, THE system SHALL 使用独立 applicationId（如 `app.openminis.devstack`），可与 `com.openminis.app` 同时安装
2. WHEN 本应用启动沙箱, THE system SHALL 使用包名限定的 abstract socket 名（如 `native-offload.<applicationId>`），两版同时运行时各自连接各自的 offload 服务
3. WHEN 两版同时运行, THE system SHALL 各自使用独立的 rootfs 目录、独立的数据目录，互不读写对方文件
4. IF proot 侧默认 socket 名未随包名更新, THE system SHALL 在构建期以宏/编译参数注入包名限定 socket 名
5. WHEN 本应用占用固定本地端口（debug server 等）与原版冲突, THE system SHALL 使用独立端口或动态端口并回写配置

## Non-Goals

- iOS 端等价能力（iSH 侧不在本 spec 范围）
- 真实内核级虚拟化（KVM/QEMU）
- 未经用户配置自动授予 root 权限
- 两版同时运行时的 OAuth 回调端口共享（OAuth 端口与服务端 redirect URI 绑定，冲突时用户需串行使用授权流程）

## Decision Record

| 决策点 | 结论 | 日期 |
|--------|------|------|
| 发行版选型 | Ubuntu 24.04 (Noble) aarch64 base rootfs，apt 包管理（yum 原话按 GLibc 生态等价理解） | 2026-09-15 |
| root 直通权限模式 | 自由模式为默认：开启开关后 Agent 可执行任意 `su` 命令，仅记录审计日志；风险自担 | 2026-09-15 |
| 工具链交付 | 混合模式：Python/GCC/Git/FFmpeg 随 rootfs 预置；JDK/Gradle/Android SDK（可选 NDK）首次启用时在线安装 | 2026-09-15 |
