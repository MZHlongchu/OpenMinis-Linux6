# Minis Ultra 1.36.3-linux

- versionCode **56**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

热修 1.36.2：发消息时崩溃 `Flow invariant is violated`（collect 在一条 `Dispatchers.IO` 协程，emit 在闸门 `withContext` 后的另一条协程）。限流闸门逻辑不变，只把流封装改成允许跨协程发送。

## 变更

### 崩溃：Flow invariant is violated

1.36.2 给 `streamMessage` 套了「同一限流桶同时只打一路 HTTP」。实现里用普通 `flow { emit }` 去转发内部 SSE 流，同时又在 `ProviderKeyGate.withPermit` 里 `withContext(HeldKeys)` 记下已持有的桶。Kotlin 规定 `flow { }` 必须在 **同一条协程** 里 emit；闸门换协程后（调度器仍是 IO）就会抛：

`Flow was collected in [..., Dispatchers.IO], but emission happened in [..., Dispatchers.IO]`

一发消息、一走流式接口就会炸，1.36.2 的 429/模型组修复等于没法用。

**修复**：改成 `channelFlow { send }`。permit 仍按 host + 密钥 + 模型名持有整段 HTTP；chunk 可以从闸门那条协程送出来。嵌套 `sendMessage` → `streamMessage` 仍然不会自己卡死。

### 未改动的 1.36.2 行为

- 429：无渠道 / 额度不足不当瞬时限流连打
- 同桶排队，不同 key 的同名模型是不同桶
- 只有会话选中「设置 → 模型组」才按组序换人
- 只选提供商列表里的某个模型时绝不自动切换

---

**未变更**：数据库版本（Room 14）、签名、包名。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.2（versionCode 55）会被 56 覆盖。
