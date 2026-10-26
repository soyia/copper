# M2-03 旁路恢复验收记录

## 结论

M2-03 的实现与 `copper-backup/v1` 合同已在隔离环境完成真实 MySQL 8.4.10 验收：备份可恢复到独立 Compose 栈，数据库、关键经营余额、附件和外键验证一致后进入 `VALIDATED`；人工切换后原数据仍可完整回滚。任何验证失败都不会覆盖当前数据。

生产发布仍必须遵守 Ubuntu 24.04 LTS x86_64 门槛。本次 `local-database` 是 aarch64，只用于用户指定机器上的恢复逻辑与 MySQL 8.4 补充验收；Backend 由兼容层运行，不替代 M1 已完成的空白 x86_64 云服务器验收。

## 自动合同测试

执行：

```bash
bash tests/cli-contract.sh
bash tests/backup-contract.sh
bash tests/static-contract.sh
```

覆盖：

- `copperctl 0.3.0-m2` CLI 路由和旧 M1 恢复兼容入口。
- 外层与内层安全路径、文件集合、SHA-256、backup ID 和格式版本。
- 附件路径、大小、摘要、引用来源、引用 ID、重复引用和孤儿数。
- SQL 数据库范围、服务器级语句拒绝、UTF-8/NUL 检查。
- 外键验证 SQL、业务验证结果比较和受限状态文件。
- stage/switch/rollback/discard 状态与“验证前不放行 Web”静态门槛。

结果：6 组既有备份元数据单测、5 组恢复元数据单测、CLI 与静态合同全部通过。

## local-database 真实运行时验收

入口：

```bash
sudo ./tests/run-m2-restore-local-database.sh
```

环境：

- Docker Engine 29.5.3
- Docker Compose 5.1.4
- MySQL 8.4.10
- age 1.3.1
- zstd 1.5.7
- 全部测试目录使用 `/tmp/copper-m2-03-*`
- 源站回环端口 18083，旁路回环端口 18084，未使用 8191

通过项：

- 新建隔离源站，创建一致性加密整站备份并验证外层合同。
- 恢复 88 张业务表、数据库附件和非零财务测试值。
- 非零指标覆盖往来账户余额、客户收款、供应商付款、销售预收占用、直接出库预收占用、预收退款、账户动作、账户流水和应收调整。
- 旁路 MySQL、Backend、Web 依次启动；管理员登录接口返回 200。
- 管理员登录产生会话/审计写入后，切换前复核在停止当前站点之前拒绝该旁路，状态进入 `DIRTY`；重新 stage 是唯一放行方式。
- 错误 age 私钥被拒绝，状态为 `FAILED`，当前站点不变。
- 损坏主包被外层 SHA-256 拒绝，当前站点不变。
- 可用空间为 0 的故障注入在解密前被拒绝。
- 镜像版本不兼容在启动旁路容器前被拒绝。
- 数据库引用的附件原件缺失时恢复失败并清理旁路 MySQL。
- 数据库导入后强制终止时，旁路容器和解密暂存被清理，当前站点保持健康。
- MySQL/附件目录完成交换后注入失败时，原目录被恢复；原数据库与附件重新验证通过后才恢复 Web，状态回到 `VALIDATED`。
- 正常切换后，备份时间点之后新增的数据库附件记录和原文件均不可见，证明流量使用的是恢复数据。
- 正常回滚后，该记录和附件原文件恢复，SHA-256 与切换前一致。
- 所有旁路 restore-ID、测试容器、网络、明文 SQL、临时私钥和测试目录最终清空。
- 共享 `vm-mysql` 与 `vm-valkey` 的容器 ID 和运行状态在测试前后完全不变。

最终完整复验耗时 483 秒，恢复表数 88，测试备份主包 51,200 字节。脚本直接输出 `restored_table_count=88`、`archive_bytes=51200`、`runtime_seconds=483`、`mysql_version=8.4.10` 和两项共享容器不变证明。

## 2026-07-22 当前 ERP 基线现场复验

本轮没有用业务导出测试或静态脚本合同代替恢复结果，而是在 `local-database` 重新执行 `tests/run-m2-restore-local-database.sh` 的完整整站路径。输入源码固定为：

| 仓库 | 提交 |
|---|---|
| `copper_deploy` | `28e438a3fda674bd455b61c002c41ebcf5102ada` |
| `copper_backend` | `c691efe248d0564a4ba9726727f1be2041f8ba0b` |
| `copper-web` | `1fc34e7f6a6a58deed86f5128417f6390a9c3245` |

现场为 Ubuntu 24.04 aarch64、Docker 29.5.3、Compose 5.1.4、MySQL 8.4.10、age 1.1.1、zstd 1.5.5。Web 与 Backend 均由上述源码在现场构建为一次性 ARM64 `INTERNAL_REHEARSAL_ONLY` 镜像；两个 Web 镜像使用不同内容 ID，避免把同一镜像伪装成兼容性差异。

本次结果：

| 项目 | 结果 |
|---|---|
| 备份与恢复 | 加密整站备份、旁路空库恢复、正常切换、回滚全部通过 |
| 数据库 | `restored_table_count=153`，V8-V24 的 56 张显式 ERP 表与非零业务合计通过 |
| 附件 | 业务签字、税票导入、银行流水导入、会计电子归档的引用、大小和 SHA-256 通过 |
| 负向阻断 | 错误私钥、主包篡改、低空间、镜像不兼容、附件缺失、执行中断全部失败关闭 |
| 切换安全 | 目录交换后故障自动恢复原站；登录污染阻断和重新 stage 通过 |
| 备份包 | `archive_bytes=81920` |
| 耗时 | `runtime_seconds=416` |
| 共享服务 | `shared_vm_mysql_unchanged=passed`、`shared_vm_valkey_unchanged=passed` |
| 最终标记 | `v8_v24_restore_modules=passed`、`m2_restore_runtime=passed` |

退出后确认：18083/18084 均未监听，旁路 restore-ID、测试容器、网络、明文 SQL、临时私钥和 `/tmp/copper-m2-03-*` 均为零；本轮源码目录和三张一次性演练镜像也已精确删除。该结果证明上述当前源码组合的整站恢复合同，仍不替代正式 Ubuntu 24.04 x86_64 发布镜像、客户实际备份介质和客户持钥条件下的正式灾难恢复验收。

## 人工发布门槛

- [ ] 正式服务器 `copperctl doctor` 通过 x86_64、镜像摘要、恢复目录权限和端口检查。
- [ ] 客户本人确认 age 私钥已有至少两份离线副本，且恢复服务器上没有常驻副本。
- [ ] 使用最新正式备份完成一次 `stage`，并由业务负责人核对库存、应收应付、资金、预收预付、审计和附件。
- [ ] 在约定维护窗口执行 `switch`，记录 restore ID、备份 ID、操作人和时间。
- [ ] 切换后完成登录、关键查询与附件下载；回滚目录在观察期结束前不得删除。
- [ ] 如需回滚，只使用 `copperctl backup restore rollback`，不得手工移动 MySQL 或附件目录。
