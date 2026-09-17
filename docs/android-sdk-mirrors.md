# Android SDK：中国大陆镜像与 aarch64 注意事项

面向 **Minis Ultra** 的 Ubuntu 24.04 arm64 PRoot 客户机，以及在中国大陆编译本仓库 APK 的开发者。应用内技能 `android-sdk-mirrors` 与本文同步。

## 先用内置工具

客户机里已经有：

- `minis-android-sdk-setup`：解压 APK 内置的 **aarch64 aapt2 / zipalign / adb**，以及精简版 Java `sdkmanager`
- SDK 根目录默认 `/opt/android-sdk`
- `sdkmanager` **只用来拉** `platforms;android-35`（android.jar）

```
minis-android-sdk-setup
echo "$ANDROID_SDK_ROOT"
ls /opt/android-sdk/build-tools
```

## 严禁安装 Google 的 linux build-tools

Google cmdline-tools 里的 `build-tools;<version>` 是 **linux x86_64** 包。在 aarch64 客户机上执行 `sdkmanager "build-tools;35.0.0"` 会把 `aapt2` **覆盖成 x86_64 ELF**，之后所有 Android 资源编译失败。

正确做法：

- 继续用 APK 捆绑的 aarch64 `android-sdk-tools-aarch64.zip`
- 需要新版 aapt2 时，从 [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools/releases) 下载 **aarch64** 包
- 不要用 `sdkmanager "build-tools;…"`，也不要解压 Google 的 `build-tools_r*-linux.zip`
- `platform-tools` 同样以 x86_64 为主，客户机请用捆绑的 aarch64 `adb`

## 中国大陆访问 Google 仓库

`https://dl.google.com/android/repository/` 在境内经常超时或被重置。优先走镜像。

| 镜像 | repository 根 |
|---|---|
| 腾讯云 | https://mirrors.cloud.tencent.com/android/repository/ |
| 清华 TUNA | https://mirrors.tuna.tsinghua.edu.cn/android/repository/ |
| 中科大 USTC | https://mirrors.ustc.edu.cn/android/repository/ |
| 华为云 | https://mirrors.huaweicloud.com/repository/android/repository/ |
| 阿里云（文档入口） | https://developer.aliyun.com/mirror/ |

把 Google 原址 URL 的 host 换成上表对应路径即可，例如：

```
https://dl.google.com/android/repository/platform-35_r02.zip
https://mirrors.cloud.tencent.com/android/repository/platform-35_r02.zip
```

### 如何自己找镜像

1. 打开高校镜像站首页，搜索 `android` / `android-sdk` / `google android`
2. 常用入口：
   - https://mirrors.tuna.tsinghua.edu.cn/help/android-sdk/
   - https://mirrors.cloud.tencent.com/help/android-sdk.html
   - https://mirrors.ustc.edu.cn/help/android-sdk.html
3. 用浏览器或 `curl -I` 探测 `…/android/repository/repository2-1.xml` 是否 200
4. 镜像滞后时对照 Google 的 `repository2-1.xml` 文件名，不要混用不同日期的 platform zip
5. GitHub Release（如 lzhiyong/android-sdk-tools）可用 ghproxy 等加速；不要把它们当成 Google SDK 仓库

## 本仓库云构建

- `ubuntu-base.tar.gz` 太大，不进 git；CI 必须现拉
- 不要 vendor 完整 Google cmdline-tools（约 146MB）；只要精简 sdkmanager
- NDK / CMake / platforms 在 **x86_64 编译主机** 上用官方 sdkmanager 安装是安全的
- 客户机里永远不要装 Google linux build-tools

## 验收

- `file /opt/android-sdk/build-tools/*/aapt2` 必须是 **ARM aarch64**，不是 x86-64
- `aapt2 version` 能运行
- 只有在镜像或直连成功后才去拉 `android.jar` / platforms
