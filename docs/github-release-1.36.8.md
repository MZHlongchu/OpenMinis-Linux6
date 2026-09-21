# OpenMinis-Linux 1.36.8-linux

- versionCode **61**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

工程与运行时收口：看门狗用单调时钟并在 API 35 解冻时重置心跳；流式刷新从 ChatViewModel 抽出；debug/headless 不再直接依赖 UI 层；模型目录 gzip 压缩；Release 开资源压缩并归档 mapping / native symbols。产品行为相对 1.36.7 不变。

## 变更

### 健壮性

1. **看门狗单调时钟 + 解冻重置** — 心跳改 `elapsedRealtime`；API 35+ 监听 UID unfreeze 立即重置心跳。gap > 30s 的冻结伪影仍落 `stall-*.log` 但不计入断路器。
2. **无障碍 offload 等待** — 滚动/稳定/抽取轮询改为事件 condition wait，双击 80ms sleep 保留。
3. **5xx 识别** — 流错误详情用 companion `HTTP_5XX_STATUS_RE`（`[5xx]`）识别，避免每次分配 Regex。

### 流式与架构

4. **`StreamSessionController`** — 双路径 flush / 节流阶梯从 `ChatViewModel` 抽出，附节流单测。
5. **`ChatSessionPort` / `ChatRuntime`** — headless RPC 与 `chat.*` 变更经端口绑定同一套 VM，debug 层不再 import `ui.chat`。
6. **`:core:model`** — 18 个 `data.model` 类型迁到独立 JVM 模块。
7. **ThinkingRule Room 映射** — `toEntity`/`toRule` 离开 provider 包，落在 `data.db`，切断 data↔provider 循环。

### 构建与体积

8. **models.dev 目录 gzip** — Android assets `models-dev-api.json.gz`（约 4.2MB → 424KB），加载失败回退明文 json；iOS 仍用明文。
9. **`shrinkResources`** + `res/raw/keep.xml`；NDK `debugSymbolLevel=SYMBOL_TABLE`。
10. **CI** — `:app:testDebugUnitTest` 与 `assembleRelease` 一起跑；归档 `mapping.txt` 与 native symbols。Gradle 开 configuration/build cache（problems=warn），Xmx 4g。
11. **`:benchmark`** — Baseline Profile 生成模块（需真机跑 `connectedReleaseAndroidTest`）。

### 日志

12. **RAW SSE** — 仅 `adb shell setprop log.tag.ToolChain[Provider] VERBOSE` 时打印，默认关闭。

## 兼容性

- 数据库仍为 Room 16，无迁移。
- 429 分级与 1.36.7 相同：强配额标记 → `ProviderError`；billing 弱标记仍为 `RateLimited`。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.7（versionCode 60）会被 61 覆盖。
