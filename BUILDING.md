# 构建 Minis Ultra

本仓库只构建 **Android arm64** 应用（启动器名 Minis Ultra，包名 `com.openminis.linux`）。没有 iOS 工程。第一次构建要先编 PRoot 并准备 Ubuntu rootfs，大约 30–60 分钟；之后产物会缓存在磁盘上。

## 环境

| 工具 | 版本 |
|---|---|
| JDK | **17** |
| Android SDK | compileSdk 36，targetSdk 35，minSdk 26 |
| Android NDK | **r28+**，设置 `ANDROID_NDK_HOME` |
| CMake | 3.22.1（用 SDK Manager 安装） |
| 其它 | `curl`、`tar`、`make`、`awk`、`sed` |

Gradle 使用仓库里的 wrapper（Gradle 8.11.1，AGP 8.7.3，Kotlin 2.1.0），不要另外安装。只打 `arm64-v8a`。

## 取得源码

```sh
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init --recursive
```

Android 沙箱用的是子模块 `deps/proot`。`deps/ish` 是历史子模块，本仓库的安装包不编译它。

构建前复制一份可空的配置即可编译运行：

```sh
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

用 API Key 登录不需要填这项。只有走 Claude OAuth 时，才需要自己提供 `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`；本仓库不内置该值。

国内网络可以先选镜像，不要把某一个镜像地址提交进仓库：

```sh
python scripts/pick_build_mirrors.py
set MINIS_BUILD_MIRRORS=cn
```

## 编原生依赖和安装包

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
cd src/android
./gradlew :app:assembleRelease
```

- `build_proot.sh` 用 NDK 交叉编译 PRoot，并放入 `assets/` 和 `jniLibs/arm64-v8a/`。`libproot-loader.so` 与 `libproot-loader32.so` 必须在 APK 里，否则客户机命令会报 `[Shell not running]`。
- `prepare_android_sandbox.sh` 下载 Ubuntu 24.04 arm64 的 `ubuntu-base.tar.gz`（不进 git），并准备 aarch64 aapt2 与精简 sdkmanager。
- 在 aarch64 客户机里不要用 Google 的 x86_64 `build-tools` 覆盖 aapt2。见 [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)。
- Release 签名见 [docs/SIGNING.md](docs/SIGNING.md)。仓库里有 `src/android/release.keystore` 和 `signing.properties` 时，`:app:assembleRelease` 用这套证书。

`src/android/app/src/main/cpp/` 里的 JNI 由 Gradle 的 CMake 一起编译，不用单独跑。

## 排错

- `deps/proot` 是空的：执行 `git submodule update --init --recursive`。
- `Android NDK not found`：把 `ANDROID_NDK_HOME` 指到 NDK r28+。
- 应用能开、终端不能跑：重跑 `./deps/build_proot.sh` 和 `./scripts/prepare_android_sandbox.sh` 后再编译。
- 每条命令都是 `[Shell not running] (exit code: -1)`：检查 `src/android/app/src/main/jniLibs/arm64-v8a/` 里是否有 `libproot-loader.so` 和 `libproot-loader32.so`。只看到沙箱启动日志不够，要实际跑一条命令并确认退出码为 0。

## 许可

本仓库以 **GPLv3** 分发，因为链接了 PRoot（GPLv2）。见 [LICENSE](LICENSE) 和 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
