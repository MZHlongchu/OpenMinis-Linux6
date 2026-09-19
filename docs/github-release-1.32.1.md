# Minis Ultra 1.32.1-linux

- versionCode **47**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

热修：1.31 把 Room 升到数据格式 **14**（代码图 + 看板），却忘了把启动前的版本守卫 `CODE_DB_VERSION` 从 12 一起改掉。第一次打开会把库迁到 14，第二次启动守卫看见 14 > 12，就弹出「数据来自更新的版本 / 当前版本支持的数据格式为 12」。会话其实都还在，只是被误拦。

1.32.1 把守卫改成 **14**，与 `@Database(version=14)` 对齐。装上即可继续用原来的会话，不用清数据。

1.32 的 cronjob / 插件市场 / 协作角色都还在。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)
