# Minis Ultra 1.25-linux

- versionCode **37**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

原生 Kotlin **进化层**（对照 metano 的 Observe→提案→审批闭环，不 vendor 其 Python 运行时）。设置 → 进化，**默认关闭**。批准前不改 prompt；永远不写 `SOUL.md` / `GLOBAL.md`。

1. **P1 骨架** — 提案（学习规则 / 技能补丁 / 撤回）、设置页批准/拒绝/推迟/回滚；批准写入 `LEARNED.md` 标记区并注入系统提示（12 条 / 2KB 帽）。
2. **Be-ACTIVE** — 会话结束扫描「不对 / 不要再 / remember」等纠正，生成 1 条待审规则。
3. **闲时收割** — 充电且 30 分钟冷却后扫会话；同一信念至少命中 2 次才提案；跳过任务日记用词（继续/调研/TODO…）。
4. **技能补丁** — 同一 skill 路径连续失败 3 次才提案；内置技能不改文件，改写落到 LEARNED。
5. **P2 信念** — draft→established→core；近重复合并；21 天陈旧、42 天衰减。超额注入先留 CORE。
6. **P2 周反思** — 最多每周一次：被后来对话打脸则提案撤回，能抽出新规则则再提案收紧；闲置 42 天也可撤回。启发式为空才动当日 LLM 额度（8 次/天，连失败 3 次熔断）。
7. **P2 场景注入** — `[backend]` / `[workflow]` / `[writing]`；无标签规则始终注入。按会话分类、标题、最近用户原文选择场景，不占用 `session.category` 字段。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog（含历史版本）：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)
