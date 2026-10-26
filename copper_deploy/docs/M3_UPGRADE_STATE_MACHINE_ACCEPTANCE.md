# M3-03 隔离迁移与失败保护验收

> 验收日期：2026-07-14
>
> 环境：`local-database`，ARM64 隔离补充运行时；MySQL 8.4.10 真实容器
>
> 结论：M3-03 状态机、隔离 Flyway、失败保护、人工切换和回滚通过

## 1. 验收命令

```bash
sudo ./tests/run-m3-upgrade-local-database.sh
```

脚本不访问镜像仓库。它从 `local-database` 已存在的正式应用镜像中提取精确应用载荷，在已存在的 ARM64 Java/Caddy 基础镜像上构造一次性测试镜像；成功场景追加 V4 建表迁移，失败场景追加语法错误 V4。所有测试镜像、容器、网络、密钥和 `/opt/copper-m3-02-*` 目录在退出时删除。

## 2. 通过项

1. M3-02 的发布契约、恢复密钥实证、低磁盘、待销毁首次凭据、备份失败和重复 PREPARED 门槛继续通过。
2. 错误 V4 由目标 Backend/Flyway 在旁路真实执行并失败，状态进入 `MIGRATION_FAILED`，Backend 日志保存在 root-only diagnostics；当前 schema 仍为 3，三层健康、经营余额和附件 SHA-256 不变。
3. 失败运行 discard 后，PREPARED、claim 与发布契约归档到该次运行目录，下一次 prepare 可以正常执行；同一 PREPARED 不能重复 claim。
4. 正确 V4 只在恢复出的旁路执行，目标最新 Flyway 为 4；来源 Flyway 前缀、MySQL 8.4、经营余额、附件、原表和外键全部通过。
5. 在 `MIGRATION_VALIDATED` 后向目标探针表写入数据，switch 在停止正式 Web/Backend 之前识别 record-counts 变化，升级和恢复两层状态均进入 `DIRTY`。
6. 在数据与配置交换后注入失败，旧 MySQL、附件和旧 `copper.env` 自动恢复；旧 Backend 重新启动并通过 schema 3、经营数据和附件验证后才恢复 Web，状态回到 `MIGRATION_VALIDATED`。
7. 正常 switch 同时切换目标 MySQL 数据目录、附件、Backend 镜像和 `COPPER_RELEASE_VERSION=0.2.0-rc.1`；schema 4、目标 Backend、三层健康和附件 SHA-256 通过后 Web 才恢复。
8. rollback 恢复 schema 3、来源 Backend/版本配置、经营数据和附件；V4 探针表不存在，状态进入 `ROLLED_BACK`。
9. 最终 discard 删除全部旁路运行数据，PREPARED 锁释放，root-only 运行证据保留。
10. 实测 MySQL 为 `8.4.10`，总运行时间 395 秒。

## 3. 主机隔离证据

- 宿主机 8191 监听状态测试前后完全一致；脚本未绑定 8191。
- `vm-mysql` 容器 ID 始终为 `cf8ab3860858acb595ad9852e6a8966b8d64d89d29704091395c216add75dcff` 且保持运行。
- `vm-valkey` 容器 ID 始终为 `73bbd1f0275fe0e356c71150156867ded5e070d82e1efca308e02110fa564282` 且保持运行。
- 验收结束后没有 `copper-m3-02-*`、`copper-restore-m303-*` 容器、网络或 `/opt` 测试目录残留。

## 4. 测试中发现并修复的问题

首次写脏测试发现 Bash 函数位于 `&&` 条件时，`set -e` 不会在内部 Python 比对失败后立即退出，后续成功的 Flyway 比对可能覆盖前一失败状态。`compare_verification` 已改为显式 `|| return 1`，静态契约固定该行为。修复后重跑证明写脏旁路在正式站停止前进入 `DIRTY`。

## 5. 未覆盖边界

本次按用户要求只在 `local-database` 运行容器，ARM64 镜像仅为离线测试载荷重组，不是可发布产物。Ubuntu 24.04/x86_64 的正式交付、在线/离线镜像安装和完整发布包升级仍由后续 M3 项验收，不能用本记录替代。
