# OpenMinis-Linux 1.36.7-linux

- versionCode **60**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

一批小刀口的性能与健壮性收口（来源：两轮源码分析报告），不含行为层面的新功能。核心三项：agent 工具列表与 provider 实例的记忆化（消除每回合的重复构建）、会话列表数据库索引（Room 15→16）、主线程看门狗冻结伪影不再计入断路器。

## 变更

### 性能

1. **`agentTools` 列表记忆化** — 此前是裸计算属性，每次访问都重建全部工具定义 + JSON 参数 schema + 插件注册表扫描；三个访问点（每次流式尝试 / 每次工具执行 / 每次工具预检）意味着一个 10 工具回合至少重建 21 次。现在按「视觉能力 · Vision Group · 记忆开关 · 多智能体开关 · 插件商店变更戳」组合键缓存。
2. **provider 实例记忆化** — `ProviderFactory.create` 每次调用都新建实例，每个实例自带一个 OkHttpClient（Dispatcher 独立）；14 人分组的回退链一回合最多新建 14 个。现在按「实例 id + 实例全量状态 + 模型状态 + 密钥指纹」记忆化（上限 64；配置写入与密钥轮换整体失效）。
3. **会话列表 `updated_at` 索引** — `SELECT * FROM sessions ORDER BY updated_at DESC` 此前全表扫描 + 排序。Room 15→16，含降级迁移 16→15（`DatabaseVersionGuard.CODE_DB_VERSION` 同步）。

### 健壮性

4. **看门狗冻结伪影不再计数** — 心跳为墙钟，进程被冻结 / 深睡的时长全额计入 gap；实测两日 6 起 stall 事件全部为解冻伪影。gap > 30s 的事件照常落 `stall-*.log`（解冻栈有诊断价值）但不再喂断路器（渲染降级 ≥2 / 强制首页 ≥3），并打印 `freeze artifact` 标记。
5. **非流式调用整体 deadline（900s）** — 标题生成、压缩、oneShotAsk、vision group、快速测试等 `sendMessage` 路径此前无整体上限；超时以 `TransientError` 抛出，进入既有重试/回退分类，不会被误判为用户取消。
6. **`SessionConcurrencyManager` 快路径并锁** — acquire 的 check-and-add 与 release 的 `@Synchronized` 统一监视器，消除并发 acquire 越过容量上限的竞态窗口。

### 安全

7. **`AlarmReceiver` 收紧为不可导出** — 此前 `exported="true"` 且通知文案取自外部 intent：任意应用可借 Minis 身份弹出内容可控的通知、并清除任意 ONCE 闹钟记录。`BOOT_COMPLETED` 为受保护广播、闹钟触发走自家 PendingIntent，均不需要 exported。
8. **429 摘要脱敏** — 进入 UI 横幅的响应体摘要对 `sk-…` / `Bearer …` / `api_key=` / `token=` 值 / 32 位以上 hex 串打码。

### 429 行为微调

9. **永久容量标记分级** — 强标记（`无可用渠道` / `no_available_providers` / `insufficient_quota` / `负载已饱和`…）维持原行为：`ProviderError`，不同桶重试；弱标记（`无可用` / `余额` / `billing`…，可能出现在瞬时限流文案里）在 provider 层保持 `RateLimited`，由既有的「还有候选就换人、末位候选重试 1 次」逻辑自然分级。
10. **`Retry-After` 补全** — 支持 RFC 7231 HTTP-date；显式值上限从 120s 放宽到 3600s（退避阶梯仍封顶 120s）。此前 `Retry-After: 86400` 会被压成 2 分钟重锤。

## 兼容性

- 数据库 15→16 为纯索引新增，含降级迁移；旧版本构建可正常打开新库。
- provider 记忆化不改变请求语义：密钥轮换 / 配置修改都会使缓存失效。
