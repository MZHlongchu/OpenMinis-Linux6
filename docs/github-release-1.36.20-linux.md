# 1.36.20-linux

versionCode **73**。安装包 `minis-ultra-com.openminis.linux.apk`。

## 权限

- 设置 → 权限顶部增加工具模式：询问 / 全部允许 / 只读 / 计划 / 全部拒绝。
- 这和下面的无障碍、Shizuku、存储不是同一层。
- 「本会话全部允许」与全局「全部允许」走同一闸门。拒绝规则仍然优先。
- `rm -rf /` 在全部允许下改为弹确认，不再静默拒绝。

## 会话隔离

- 文件工具不能用 `..` 读到别的会话或别的项目。
- 同一项目的共享工作区、自己的日记、全局技能、rootfs 仍可用。
- `search_sessions` / `read_session` 不受影响。

## 客户机证书

- 证书目录 0755、文件 0644，非 root 也能读。
- 补上 OpenSSL subject-hash 的 `.0` 文件。
- `SSL_CERT_FILE` 写入 `/etc/environment` 和 bash 启动脚本，不依赖本次进程环境变量。
