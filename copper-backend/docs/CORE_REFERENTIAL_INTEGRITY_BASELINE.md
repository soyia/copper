# V67 核心引用完整性与旧账来源审计

2026-10-16。V1–V66 保持原文件和校验和；旧 PR73 的 V57 方案重新基于当前版本落为 V67，禁止将两个 V57 一起发布或修改已应用的直运 V57。

V67 在第一条外键 DDL 前，预检全部 54 条确定性关系；存在孤儿时以 `V67_ORPHAN_BLOCK:<表>.<字段>:<数量>` 失败，尚未增加任何本轮外键。每条约束均为 `ON DELETE RESTRICT ON UPDATE RESTRICT`，不级联删除历史。

54 条包含旧方案的 47 条购销、库存、退货、核销、会计分录、税票、银行对账与轻量经营关系，另增加：

| 子表 | 字段 | 父表 |
|---|---|---|
| inventory_package_instance_usage | package_instance_id | inventory_package_instance |
| inventory_package_instance_usage | batch_id | inventory_batch |
| inventory_package_instance_usage | product_id | product |
| inventory_non_trade_item | document_id | inventory_non_trade_document |
| inventory_non_trade_item | product_id | product |
| inventory_non_trade_item | warehouse_id | warehouse |
| inventory_non_trade_item | batch_id | inventory_batch |

V57 直运与 V58 协同表已有确定性外键，继续保留。V59–61、V63–64、V66 主要调整现有字段、包装分配 JSON、签字及状态，不把其中的多态来源、附件选择和 `0` 哨兵字段直接约束为错误父表。V65 的逐行商品、仓库、批次和来源单据关系现已加入。当前 SQL 声明 V66 共 38 个外键，V67 新增 54 个后共 92 个。

## 当前库只读审计与旧账处理

`tests/audit-current-business-references.sql` 使用只读一致性事务，输出数据库身份、Flyway 版本、54 条引用孤儿数量，以及会计制证结果、凭证、制证问题到业务过账批次的缺失引用详情。通过现有安全 MySQL 客户端配置执行，例如：

```bash
mysql --login-path=copper-audit --database=<明确选择的数据库> \
  --batch < tests/audit-current-business-references.sql > <私有审计结果路径>
```

不能把新空库的零差异当成现有经营库的证据。旧 PR73 在 2026-07-24 记录的“2 条制证结果、2 张草稿凭证缺失业务过账批次”只属于当时只读检查结果；必须重新在目标库执行以上审计再判断是否仍存在。

发现缺失来源时，暂停该账套发布/切换，逐项形成修复清单：缺失批次 ID、凭证与制证结果 ID、主体/期间、单据类型/主键/编号、借贷金额、分录和总账行数、原业务事实、过账规则版本、备份与审计证据。对当前来源存在且证据可复算的情形，先在备份恢复的隔离库验证正式服务的重建或冲正流程，核对库存、往来、资金、凭证和总账；保留旧记录、原因及新旧映射。来源无法证明时保持异常状态并提交经营/财务负责人判定，不能以自动删除、改为 0/NULL 或补造空父批次掩盖。已记账/已关闭期间还须使用正式反向凭证或后续期间调整。

本轮迁移不会自动修复这些旧会计引用，也不声称全部多态业务来源已完成外键化。只有取得实际目标库的新审计、可复核修复方案和复核结果后，才能关闭已有数据问题。

## 隔离 MySQL 验收

```bash
COPPER_FK_DB_URL='jdbc:mysql://127.0.0.1:<隔离端口>/<任意现有验收库>?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true' \
COPPER_FK_DB_USERNAME='<隔离账号>' COPPER_FK_DB_PASSWORD='<由环境提供>' \
bash tests/run-core-referential-integrity-mysql.sh
```

该测试在同一隔离实例创建并删除 UUID 命名的 `copper_fk_*` 专用数据库，账号需要对应建库、删库、表 DDL 和存储过程权限。覆盖新装、含已存业务事实的 V66 升级、父记录删除/孤儿写入拒绝，以及旧交付事件、V62 包装使用和 V65 非购销明细三类脏数据在第一条外键 DDL 前阻断。当前实际执行记录由本次发布验收文件保存。
