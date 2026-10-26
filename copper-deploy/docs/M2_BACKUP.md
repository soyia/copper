# M2-02 一致性整站全量与增量备份

> 状态：`copper-backup/v1` 全量已完成原验收；`copper-backup/v2` 增量链已实现，并于 2026-07-21 通过隔离 MySQL 8.4 ROW binlog 与附件差异恢复演练；2026-07-22 又完成 V8-V24 新增 ERP 模块和附件的全量恢复验收
> 格式：每条链以 `copper-backup/v1` 全量包为基础，后接一个或多个 `copper-backup/v2` 增量包
> 恢复边界：任一增量包都不能独立恢复，必须同时持有全量基础包及其后连续的全部父链

## 1. 备份内容

每个成功备份由一个 `.copperbak` 文件和同名 `.sha256` 外层校验文件组成。`.copperbak` 是标准 POSIX pax tar，包含：

```text
envelope.json
payload.tar.zst.age
checksums.sha256
README.txt
```

全量包解密后的载荷包含：

```text
manifest.json
database/copper.sql.zst
attachments/
config/runtime-allowlist.json
verification/schema.json
verification/record-counts.json
verification/business-totals.json
verification/attachments.json
checksums.sha256
```

- 数据库使用 MySQL 逻辑备份，不复制运行中的 MySQL 数据目录。
- 全量 manifest 同时固化逻辑快照对应的 binlog 文件和位置；第一个增量必须从该位置开始，不能只凭本机链状态猜测衔接点。
- 附件保存原始字节和稳定相对路径，并逐项核对数据库记录中的大小与 SHA-256。
- 配置只保存明确允许的非秘密字段，不包含数据库口令、TLS 私钥、会话密钥或第三方密钥。
- `record-counts.json` 保存每张基础表的精确记录数，不使用 `information_schema.TABLES.TABLE_ROWS` 估算值。
- `business-totals.json` 使用数据库十进制定点字段记录库存、成本、往来账户、应收应付流水、收付款和承兑汇总，并覆盖调拨、盘点、采购退货、成本调整、业务过账、会计凭证、总账、税票、银行对账、期末结账和 CRM；不使用前端显示值或分页结果。
- V8-V24 的 56 张 ERP 表由 `config/erp-v8-v24-tables.txt` 固定为恢复合同；全量记录数仍动态覆盖数据库的每张基础表。业务附件、税票导入原件、银行流水导入原件和会计电子归档也进入附件引用、大小与 SHA-256 核对。

增量包沿用相同外层结构；解密后的核心内容为：

```text
manifest.json
database/binlog/binlog.000NNN
attachments/                         # 仅新增或内容变化的原件
config/runtime-allowlist.json
verification/schema.json
verification/record-counts.json
verification/business-totals.json
verification/attachments.json        # 截点的完整附件预期清单
verification/attachment-delta.json   # 新增/变化及删除墓碑
checksums.sha256
```

- 数据库增量使用 MySQL 8.4 `ROW`/`FULL` binary log；每次停写后轮换日志，只把父边界到新边界之间已关闭的日志装入加密包。
- 附件增量比较稳定相对路径、字节数和 SHA-256，不按“本周修改时间”猜测；删除使用墓碑清单，避免恢复后残留已删除附件。
- `/var/backups/copper/chain-state.json` 是 root-only 的本机续链状态。它不包含密钥，但丢失、损坏或与父归档摘要不一致时禁止继续增量，必须重新创建全量基础包。

## 2. 一致性边界

创建备份时：

1. 要求 MySQL、Backend、Web 三层先处于健康状态，且首次管理员一次性凭据已经完成交付、改密并自动销毁。明文一次性凭据不进入备份，未改密站点会在停写前被拒绝。
2. 停止 Web 接入和 Backend，等待 MySQL 活跃事务归零；MySQL 仅保留内部访问。
3. 全量任务先轮换 binlog，再捕获逻辑数据库和全部附件；增量任务捕获完整验证快照、附件差异并轮换到新的 binlog 边界。
4. 原始快照捕获完成后立即启动三层服务并等待健康。
5. 在服务恢复后压缩、加密、校验并原子发布备份，缩短业务不可写时间。

任何步骤失败或收到 `INT`/`TERM` 时，脚本都会清理临时目录并尝试恢复三层服务。恢复后数据库未健康时，整站 readiness 不能绕过；命令以失败退出并要求运行 `copperctl doctor`。

## 3. 客户持钥配置

正式备份使用公开 `age` X25519 格式。私钥必须由客户在其控制的离线设备生成和保管，Copper 服务器只保存 `age1...` 公钥：

```text
COPPER_BACKUP_AGE_RECIPIENT=age1...
```

匹配的 `AGE-SECRET-KEY-...` 不得写入服务器持久磁盘、Git、镜像、工单或普通日志。日常运行和备份创建只需要公钥。执行真实恢复时，私钥只能从客户控制的只读安全介质直接使用；客户云没有可挂载介质时，可按 `M2_RESTORE.md` 通过 SSH 标准输入临时写入 `/run` tmpfs，并必须在恢复命令结束后立即删除和复核不存在。私钥丢失后无法解密历史备份，Copper 不保留后门。

Ubuntu 联网安装入口会安装 `age`、`zstd` 和 `python3`。离线交付在正式启用 M2 前必须从可信 Ubuntu 24.04 介质准备这三个工具，不得在客户服务器临时执行未知 `curl | sh`。

## 4. 操作命令

创建手工备份：

```bash
sudo copperctl backup create
```

手工强制创建新的全量基础包，或在已有连续链上创建增量包：

```bash
sudo copperctl backup create --type full
sudo copperctl backup create --type incremental
```

`--type auto` 默认只用于计划任务：`COPPER_BACKUP_FULL_WEEKDAY=7` 表示周日全量，其余日期增量；首次没有有效链状态时自动创建全量。

校验外层文件、结构、`envelope.json` 和密文载荷（内层清单需在 M2-03 使用客户私钥解密后校验）：

```bash
sudo copperctl backup verify /var/backups/copper/文件名.copperbak
```

预览保留策略将删除的文件：

```bash
sudo copperctl backup prune --dry-run
```

执行保留策略：

```bash
sudo copperctl backup prune
```

完成公钥双人交接、一次手工全量备份和客户离线解密验证后，再启用每日计划任务：

```bash
sudo copperctl backup schedule install
sudo copperctl backup schedule status
```

禁用计划任务不会删除已有备份：

```bash
sudo copperctl backup schedule disable
```

## 5. 原子成功与保留规则

- 所有内容先写入备份目录内的 `.backup-*` 临时目录，校验通过后才发布最终文件。
- `.copperbak`、同名 `.sha256`、`chain-state.json` 和 `last-success.json` 全部完成后，任务才算成功。
- 失败任务不更新 `last-success.json`，也不执行保留删除。
- 默认保留最近 7 个每日基础点、4 个每周基础点和 6 个每月基础点，并且永远保留最新完整链。
- 自动清理以“全量基础包及其连续增量”作为不可拆分单位；只要任一保留增量依赖某个全量包，该全量包及中间父包都不能删除。断链、孤儿或未知格式不自动删除，必须人工调查。
- 只有同时存在格式正确且摘要真实匹配 `.sha256` 的 `manual`/`scheduled` 成功备份才进入自动保留计算；未知文件、损坏文件和未来恢复安全备份不自动删除。

可在 `/etc/copper/copper.env` 调整：

```text
COPPER_BACKUP_KEEP_DAILY=7
COPPER_BACKUP_KEEP_WEEKLY=4
COPPER_BACKUP_KEEP_MONTHLY=6
COPPER_BACKUP_FULL_WEEKDAY=7
```

设置为 `0` 表示不为该周期额外保留，但最新成功备份仍不会被删除。修改后必须先运行 `backup prune --dry-run`。

密钥登记、轮换、任务状态和告警的完整操作见 `M2_KEY_LIFECYCLE.md`；服务器不持久保存恢复私钥，真实恢复时的受控临时使用见 `M2_RESTORE.md`。

## 6. 运行边界

- `backup restore stage` 支持单独恢复 v1 全量包，也支持通过重复 `--incremental` 按顺序恢复连续 v2 增量链；它仍先恢复到隔离旁路，不直接覆盖当前站点。
- 旧发布中直接使用 Oracle 官方最小 MySQL 镜像的版本不含 `mysqlbinlog`，不能启用 v2 恢复。必须随本版本交付并固定新的 `ghcr.io/soyia/copper_mysql@sha256:...` 镜像；不得只更新脚本而沿用旧 MySQL 镜像。
- M2-05 已增加服务器本地、系统挂载 NAS 和 S3 兼容目标；配置、责任边界和容量保护见 `M2_STORAGE_TARGETS.md`。
- M2-06 已实现密钥交付、轮换、销毁记录、任务状态和通知告警；在 `M2_KEY_ALERT_ACCEPTANCE.md` 填入真实运行证据前不对外标记验收完成。
- 备份成功不等于灾难恢复已经验收。只有 M2-03 和 M2-07 在空白服务器完成恢复与关键余额核对后，才能对外承诺完整恢复可用。

## 7. V8-V24 新增模块验收证据

2026-07-22 在 `local-database` 的隔离 Docker 栈中，以 MySQL 8.4.10 执行了一次完整的“造数 -> 加密全量备份 -> 空库旁路恢复 -> 数据与附件复核 -> 故障切换回滚 -> 最终切换”演练：

- `config/erp-v8-v24-tables.txt` 固定的 56 张新增表全部存在并进入恢复合同；恢复完成后共核对 153 张基础表。
- 调拨、盘点、采购退货、成本调整、业务过账、会计凭证、总账、税票、银行对账、期末结账和 CRM 的代表性记录及业务合计均保持一致。
- 业务签字证据、税票导入原件、银行流水导入原件和会计电子归档均按数据库引用、稳定路径、大小与 SHA-256 恢复。
- 错误私钥、归档篡改、空间不足、镜像不兼容、附件缺失和切换中断均失败关闭；切换中断后原站点自动恢复。
- 全量加密归档为 81,920 bytes，完整演练耗时 314 秒；正式端口 8191 未使用，共享 `vm-mysql` 与 `vm-valkey` 容器 ID 保持不变。
