# M3-02 升级预检与备份准备验收记录

验收日期：2026-07-14（Asia/Shanghai）

## 结论

M3-02 的发布契约、来源版本匹配、只读预检、失败保护、升级专属备份和 `PREPARED` 证据绑定已通过 `local-database` 隔离运行时验收。M3-02 没有执行数据库迁移、镜像切换或版本发布；迁移状态机属于 M3-03。

## 环境和边界

- 主机：`local-database`，ARM64，仅使用隔离目录、隔离 Compose 项目和 `COPPER_TEST_MODE=1`。
- Docker Engine：29.5.3；Docker Compose：5.1.4。
- 数据库：MySQL 8.4.10，镜像 ID `sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`。
- 应用载荷：从 M2 已验收的正式 amd64 Backend/Web 镜像逐文件提取，再使用服务器已经存在的 ARM64 Java 17/Caddy 基础镜像构建一次性测试镜像。业务 JAR 和 Web 静态文件未改写；测试镜像在退出时删除。
- 主机端口仅使用 `127.0.0.1:18089`，没有监听、停止或替换宿主机 8191。
- 测试目标 `0.2.0-rc.1` 只用于验证未来版本契约，不代表创建了产品 Tag、GitHub Release 或正式发布。

## 实际通过证据

1. `copper-upgrade-matrix/v1` 和 `copper-release/v1` 的字段、SemVer 顺序、来源版本/schema、不可变镜像摘要、Git commit、平台、MySQL 8.4、资源下限、发布时间和发布物 SHA-256 均通过严格校验。
2. 当前 `0.1.0-m2-rc.2`、Flyway schema 3 只能匹配矩阵中的精确来源条目；不存在范围推断或跳级猜测。
3. 首次管理员一次性凭据已交付、改密并销毁；重新放置待交付凭据后，升级预检在创建备份前拒绝。
4. 客户恢复密钥没有真实离线解密回执时，预检拒绝；创建实际 `.copperbak`、在服务器外逻辑边界使用私钥验证并登记回执后，密钥审计通过。
5. 三层健康、MySQL 8.4、当前 schema、CPU、内存、数据与备份文件系统空间全部参与只读预检；注入可用空间为 0 时，预检拒绝且不创建备份。
6. `upgrade preflight --json` 只输出可机器解析的 JSON，测试模式平台提示写入标准错误，不污染契约输出。
7. 在 Web/Backend 停止后注入备份失败，三层自动恢复 `running/healthy`，`last-success.json` 未改变，且没有写入 `PREPARED`。
8. 最近一次备份任务为失败状态时，预检拒绝；重新完成一次正常备份后才允许继续。
9. 成功 `upgrade prepare` 产生新的 backupId 和 `last-success.json` 摘要，正式执行 `backup verify`，再把目标清单、升级矩阵、Deploy 包、发布说明、ACME 包和备份身份/SHA-256/字节数绑定到 root-only `copper-upgrade-preparation/v1` 记录。
10. `prepared.json` 权限为 `root:root 0400`，暂存契约文件为 `0400`；`upgrade status --json` 会重新校验所有暂存发布物和备份实体，而不是只信任记录文本。
11. 未消费现有 `PREPARED` 时重复执行 `prepare` 会在创建备份前拒绝，`last-success.json` 保持不变。
12. 全流程结束后 Flyway schema 仍为 3，三个业务容器身份在预检/准备期间未被替换。完整运行时间 94 秒，脚本退出码 0。

自动化入口：

```bash
sudo ./tests/run-m3-upgrade-preflight-local-database.sh
```

## 清理和共享资源复核

- 隔离容器、网络、`/opt/copper-m3-02-*` 工作目录、测试私钥、回执和一次性 ARM64 镜像均已删除。
- `vm-mysql` 保持运行且容器 ID 未变化：`cf8ab3860858acb595ad9852e6a8966b8d64d89d29704091395c216add75dcff`。
- `vm-valkey` 保持运行且容器 ID 未变化：`73bbd1f0275fe0e356c71150156867ded5e070d82e1efca308e02110fa564282`。
- 宿主机 8191 在验收前后均未监听；没有残留 `copper-m3-02-source-*` 容器或测试目录。

## 后续边界

M3-03 已按该边界消费精确 `PREPARED`，实施隔离迁移、失败恢复和人工切换状态机，详见 [M3-03 验收](M3_UPGRADE_STATE_MACHINE_ACCEPTANCE.md)。M3-02 的“准备完成”仍不得被解释为“升级已经完成”。正式 amd64 发布仍需按发布级别矩阵执行相应 x86_64 验收。
