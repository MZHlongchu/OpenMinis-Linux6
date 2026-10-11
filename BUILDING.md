# 构建 Minis Ultra

本仓库只构建 **Android arm64** 应用（启动器名 Minis Ultra，包名 `com.openminis.linux`）。没有 iOS 工程。第一次构建要先编 PRoot 并准备 Ubuntu rootfs，大约 30–60 分钟；之后产物会缓存在磁盘上。

## 环境

| 工具 | 版本 |
|---|---|
| JDK | **17** |
| Android SDK | compileSdk 36，targetSdk 35，minSdk 26 |
| Android NDK | **r29 `29.0.14206865`**，设置 `ANDROID_NDK_HOME`。已有 r28 不算可用 |
| CMake | **3.22.1 或更高**（DSL 写的是 `3.22.1+`）。aarch64 上 SDK Manager 未必装得到，`build_apk_aarch64.sh` 会自己解析并通过 `cmake.dir` 指定 |
| 其它 | `curl`、`tar`、`make`、`sed`，以及 **`gawk`**（不能只是任意 awk，见下）|
| Go + gomobile（可选） | **Go 1.26+**——`deps/rclone-mobile/go.mod` 声明 `go 1.26.0`，CI 钉 `1.26.8`——然后 `go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e && gomobile init`。只有要做远端备份目标（SMB / WebDAV / SFTP / S3 / FTP）时才需要，见下 |

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

### `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`

只有用 **Claude OAuth 凭据**（而不是 Anthropic API Key）登录时才需要它。走 OAuth 的请求，Anthropic 端点要求系统提示以 Claude Code 自己发送的那行标识开头，否则整条请求会被拒；构建时那行就是从这个值注入的，所以留空时 OAuth 登录会在运行时失败。

本仓库不附带该值——需要这条路径就自己提供：其它对接同一端点的开源项目声明了同样的标识，例如 [claude-relay-service](https://github.com/Wei-Shaw/claude-relay-service)，可以去那里确认确切措辞。

其余情况——Anthropic API Key，以及所有其它 provider——不设置它就完全正常。

国内网络可以先选镜像，不要把某一个镜像地址提交进仓库：

```sh
python scripts/pick_build_mirrors.py
set MINIS_BUILD_MIRRORS=cn
```

## 编原生依赖和安装包

aarch64 宿主机直接跑一键脚本即可：

```sh
./scripts/build_apk_aarch64.sh
```

它会初始化 `deps/proot` 子模块、编 PRoot、准备沙箱资产（含完整性校验与自动修复）、
编 libunwind.a、解析 NDK/CMake/aapt2 并写入 `local.properties`，最后 `assembleRelease`
把产物复制到 `dist/openminis-aarch64.apk`。

也可以手工分步执行：

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
# 可选：远端备份需要 Go 1.26+ 与 gomobile；缺这一步也能编译（见下）
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/
cd src/android
./gradlew :app:assembleRelease
```

- `build_proot.sh` 用 NDK 交叉编译 PRoot，并放入 `assets/` 和 `jniLibs/arm64-v8a/`。`libproot-loader.so` 与 `libproot-loader32.so` 必须在 APK 里，否则客户机命令会报 `[Shell not running]`。
  这两个 loader 不是可选项：Android 10+ 对 `untrusted_app` 强制 W^X，凡是标成 `app_data_file` 的东西都不能执行——包括解包到应用 `files/` 下的整个 Ubuntu rootfs——只有从 APK 的 `lib/**/*.so` 解出来、落在 `nativeLibraryDir` 里的才可执行。所以 loader 必须住在那里，由它自己映射客户机二进制，而不指望内核的 `execve`（proot 本来能在运行时释放内嵌 loader，但在 Android 上它写进应用临时目录，撞的是同一堵 W^X 墙）。它们带 `.so` 后缀也正是这个原因：只有 `*.so` 会被解包。
  产物在不同 NDK 版本之间不是逐字节相同的（loader 的代码随工具链代次变化），功能等价，别拿别人的校验和来对。
- `build_proot.sh` **需要 gawk**：proot 的 `loader-info.awk` 用了 `strtonum()`，那是 gawk 扩展，而 Debian/Ubuntu 默认的 `/usr/bin/awk` 是 mawk。脚本会自己找一个支持 `strtonum` 的 awk 并在 make 期间遮蔽默认的。
- `prepare_android_sandbox.sh` 下载 Ubuntu 24.04 arm64 的 `ubuntu-base.tar.gz`（不进 git），并准备 aarch64 aapt2 与精简 sdkmanager。它会校验每个 blob：`android-sdk-tools-aarch64.zip` 来自第三方 release，35.0.2 那份的偏移量就是坏的，脚本用 `repair_vendor_zip.py` 就地重建。
- 在 aarch64 宿主机上编译时，AGP 默认从 Maven 取 **x86_64** 的 aapt2，会以 `AAPT2 Daemon startup failed` 失败。`build_apk_aarch64.sh` 自动加 `-Pandroid.aapt2FromMavenOverride=` 指向一个能在本机跑的 aarch64 aapt2。同样地，在 aarch64 客户机里不要用 Google 的 x86_64 `build-tools` 覆盖 aapt2。见 [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)。
- `build_rclone_android.sh` 用 gomobile 把 rclone 绑成 `deps/build/rclone/rclone.aar`，给备份功能的远端目标用；需要先设好 `ANDROID_NDK_HOME`（或 `ANDROID_HOME`）。Gradle 是从 `src/android/app/libs/rclone.aar` 读的，所以要照上面的 `cp` 复制过去；这个 AAR 不进 git（约 15MB）。**没有它也能编译**：Gradle 会改编译 `src/rcloneStub`，APK 照常产出，只是远端备份目标在 RPC 阶段返回 501（错误里写明要去跑这个脚本），本地备份不受影响。绑定的后端清单在 `deps/rclone-mobile/backends/backends.go`。一键脚本 `build_apk_aarch64.sh` **不含**这一步（它只做 PRoot + 沙箱资产 + libunwind + assembleRelease），要远端备份就得自己补跑并复制 AAR。
- Release 签名见 [docs/SIGNING.md](docs/SIGNING.md)。仓库里有 `src/android/release.keystore` 和 `signing.properties` 时，`:app:assembleRelease` 用这套证书。

<!-- [T-building-1.14] 上游 1.14 对 BUILDING.md 的新增分两类：
     · 与本移植无关的 iOS 部分——Metal Toolchain、build_rclone_ios.sh、Rclone.xcframework，以及
       Xcode/iOS 专属排错条目。本仓库没有 iOS 工程（见本文开头），故不收录，这不是遗漏。
     · 与 Android 相关的 rclone 部分——Go + gomobile 前置条件、build_rclone_android.sh 及其产物路径、
       `Could not find :rclone:` 排错——已按本移植的实际情况补回（Go 版本以 deps/rclone-mobile/go.mod
       的 `go 1.26.0` 与 CI 钉的 1.26.8 为准，不用上游写的 1.25+）。
     另：上游 1.14 把「Both write into src/android/app/src/main/, and their outputs are gitignored」
     改成了「All of these outputs are gitignored」。这句在本仓库**不成立**，故不引用：两个 proot loader
     （jniLibs/arm64-v8a/libproot-loader{,32}.so）是刻意入库的 vendored 二进制，.gitignore 用
     `!` 白名单放行，deps/build_proot.sh 还会校验它们的 sha256——被忽略掉就再也构建不出能跑客户机命令的
     APK。见上面 build_proot.sh 那条与排错里的 `[Shell not running]`。 -->

`src/android/app/src/main/cpp/` 里的 JNI 由 Gradle 的 CMake 一起编译，不用单独跑。

## 排错

- `deps/proot` 是空的：执行 `git submodule update --init --recursive`（`build_apk_aarch64.sh` 会自动做）。
- `awk: loader/loader-info.awk: line 9: function strtonum never defined`：默认 awk 是 mawk。装 gawk（`apt-get install -y gawk`）后重跑；`build_proot.sh` 会自己找到它。
- `AAPT2 Daemon startup failed` / `Bundled aapt2 not found`：在 aarch64 宿主机上十有八九是 AGP 取了 x86_64 的 aapt2。用 `build_apk_aarch64.sh`，或手工加 `-Pandroid.aapt2FromMavenOverride=<能跑的 aarch64 aapt2>`。
- `Android NDK not found`：把 `ANDROID_NDK_HOME` 指到 NDK r29（`29.0.14206865`）。不要指到 r28。
- CMake 相关的 `does not match ... required version`：DSL 要的是 `3.22.1+`，任何 ≥3.22.1 都行。`local.properties` 里的 `cmake.dir` 由脚本解析写入；手工构建时若 AGP 找不到 SDK 里的 CMake（手装的目录没有 `package.xml`，AGP 枚举不到），就自己写一行 `cmake.dir=`。不要在 `local.properties` 里写 `ndk.dir`，它已废弃（AGP 报 CXX5106），项目用 `ndkVersion` 定位。
- 客户机里没有 aapt2/zipalign：说明捆绑的 `android-sdk-tools-aarch64.zip` 没解出来。跑 `python3 scripts/repair_vendor_zip.py --verify src/android/app/src/main/assets/android-sdk-tools-aarch64.zip` 看它是否损坏，再用 `prepare_android_sandbox.sh` 修复。App 侧会在 `/opt/android-sdk/.minis-sdk-tools-error` 留下失败原因。
- `Could not find :rclone:`，或远端备份（SMB / WebDAV / SFTP / S3 / FTP）报 501：`src/android/app/libs/rclone.aar` 不在。跑 `./deps/build_rclone_android.sh`（需要 Go 1.26+ 与 gomobile），再把 `deps/build/rclone/rclone.aar` 复制到 `src/android/app/libs/`。
- 应用能开、终端不能跑：重跑 `./deps/build_proot.sh` 和 `./scripts/prepare_android_sandbox.sh` 后再编译。
- 每条命令都是 `[Shell not running] (exit code: -1)`：检查 `src/android/app/src/main/jniLibs/arm64-v8a/` 里是否有 `libproot-loader.so` 和 `libproot-loader32.so`。只看到沙箱启动日志不够，要实际跑一条命令并确认退出码为 0。也可以用 `unzip -l app-debug.apk | grep libproot` 核对三个 `.so` 都在。
- 单元测试 `VendoredAssetIntegrityTest` 失败：说明 `assets/` 下有 blob 损坏或缺关键成员。这是故意的护栏——它拦住的正是"能打包但装到手机上跑不起来"那一类问题。

## 许可

本仓库以 **GPLv3** 分发，因为链接了 PRoot（GPLv2）。见 [LICENSE](LICENSE) 和 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。