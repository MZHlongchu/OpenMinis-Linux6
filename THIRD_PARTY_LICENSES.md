# 第三方许可

本仓库（Minis Ultra）安装包实际链接、随包分发或在构建时下载的第三方组件如下。本安装包不包含 iOS 应用，也不链接 iSH、FFmpeg 或 Alpine rootfs。

<!-- [T-lic-1.14] 合并上游 1.14 时，本侧对本文档的整体重写覆盖掉了上游新增的两处许可信息，此处补回；
     后续合并或精简请勿再次删除：

     (1) 「proot ELF loaders (Termux build)」整行。它是随 APK 分发的 **GPL-2.0** 二进制
         （jniLibs/arm64-v8a/libproot-loader.so / libproot-loader32.so，git 跟踪，sha256 钉在
         deps/build_proot.sh），许可信息缺失属于实质性合规缺陷。该行与上游 1.14 **逐字一致**
         （含对应源码 termux/proot@4dba3afb 与 termux-packages@0ff4d49d 的钉法）——它是法律声明，
         改写措辞或拆分字段都等于改变声明内容，只能整行照抄。

     (2) RealTimeCutVADLibraryForAndroid 行补回 Silero VAD（**MIT**）。上游同一段里该库捆绑三项，
         本侧重写时只留下了 ONNX Runtime 与 WebRTC audio processing，漏了第三项。

     未收录的上游 1.14 条目仍是 iOS 专属（iSH / FFmpeg / LAME / Alpine rootfs 与 SwiftPM 段落）：
     本移植没有 iOS 工程、不链接这些组件，维持不列是**刻意的**，不是遗漏。 -->

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) |
| proot ELF loaders (Termux build) | Termux package `proot` 5.1.107-70, vendored as `src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so` and `libproot-loader32.so` (sha256-pinned in `deps/build_proot.sh`) | **GPL-2.0** | Corresponding source: [termux/proot@`4dba3afb`](https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f), as pinned by [termux-packages@`0ff4d49d`](https://github.com/termux/termux-packages/blob/0ff4d49dad73ed7a62375504cf96326bb835e132/packages/proot/build.sh). Shipped as binaries because they carry Termux's Android loader patches |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | Android `jieba_jni` | **MIT** | 中文分词 |
| Ubuntu 24.04 base (noble arm64) | downloaded by `scripts/prepare_android_sandbox.sh` | Canonical / Ubuntu package licenses (glibc **LGPL-2.1**, apt **GPL-2.0**, etc.) | Android PRoot guest; not stored in this repo |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) aarch64 (aapt2, zipalign, adb, aidl) | 35.0.2 static | **Apache-2.0** (AOSP) | Vendored as `assets/android-sdk-tools-aarch64.zip`; unpacked to `/opt/android-sdk` |
| Android SDK Command-line Tools (`sdkmanager`) | 12.0 (`commandlinetools-linux-11076708`) | **Apache-2.0** (Google / AOSP) | Slimmed to the sdkmanager classpath as `assets/android-cmdline-tools.zip`; lint/R8/kotlin-compiler omitted |
| [rclone](https://github.com/rclone/rclone) | v1.75.0（`deps/rclone-mobile/go.mod`），由 `deps/build_rclone_android.sh` 构建 | **MIT**（其 Go 模块依赖为 MIT / BSD / Apache-2.0） | 备份远端目标：`rclone.aar` → `libgojni.so`。AAR 不进 git，缺失时编译 `src/rcloneStub` |

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |
| Mozilla Rhino (`org.mozilla:rhino`) | 1.7.14 | **MPL-2.0** |
| RealTimeCutVADLibraryForAndroid (helloooideeeeea) | 1.0.5 | **MIT** — 内含 [ONNX Runtime](https://github.com/microsoft/onnxruntime)（**MIT**）、[WebRTC audio processing](https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing)（**BSD-3-Clause**）与 [Silero VAD](https://github.com/snakers4/silero-vad) 模型（**MIT**） |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | Android `assets/jieba/` | **MIT**（随 cppjieba 分发） |
| [tiktoken](https://github.com/openai/tiktoken) `cl100k_base` BPE | Android `data/BPETokenizer.kt` 由 tiktoken 移植 | **MIT**（本仓库默认只带启发式计数器；`cl100k_base.tiktoken` 词表不随包分发，需要时可经 `loadVocabularyFromAssets` 放入） |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.

## Adapted Kotlin (not vendored as a module)

| Component | License | Notes |
|---|---|---|
| [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) | **GPL-3.0-or-later** | Selected Android agent pieces adapted into OpenMinis: `cronjob`/`CronScheduler`, online OpenAPI plugin registry + `OnlineApiTool`, `CollabRoles` from PresetTeam, `SecurityGate`/`PermissionIntersection`/`ShellArgv`, `dispatch_agents`/`wolfpack_run`/`agent_plan`/`execute_code` (Rhino)/`invoke_skill`/`skill_manage`/`ask_reasoning`, `list_dir`/`glob`/`grep`/`web_fetch`/`multi_edit`, SubAgentTypeStore. Memory ranking stays on OpenMinis `MemoryRecallEngine`. Not a wholesale copy of the XINCODE app. |
