# 期末关账脏数据阻断验收

> INTERNAL_REHEARSAL_ONLY
>
> 最近验收日期：2026-07-22

## 验收结论

期末关账不能只验证干净数据。以下十类异常必须分别写入真实 MySQL 表，由
`FinancialCloseService.closePreview` 识别为 `BLOCKED`，并由
`FinancialCloseService.closePeriod` 拒绝关账。

| 异常场景 | 关账检查码 | 真实夹具 | 预期结果 |
| --- | --- | --- | --- |
| 未记账凭证 | `VOUCHER_STATUS` | `accounting_voucher.status=DRAFT` | 阻断 |
| 未制证过账 | `BUSINESS_POSTING` | 已过账但无会计凭证的 `business_posting_batch` | 阻断 |
| 未完成库存单 | `INVENTORY_DOCUMENTS` | 草稿库存成本调整单 | 阻断 |
| 库存差异 | `INVENTORY_RECONCILIATION` | 库存余额与批次汇总不一致 | 阻断 |
| 往来差异 | `PARTNER_BALANCE` | 应收累计发生额与末次余额不一致 | 阻断 |
| 税票异常 | `TAX_INVOICE` | 已确认但未查验、未勾稽的税票 | 阻断 |
| 银行未对账 | `BANK_RECONCILIATION` | 启用账户在期间内无已完成对账单 | 阻断 |
| 现金流勾稽不平 | `CASH_FLOW_RECONCILIATION` | 货币资金总账与现金流推算不一致 | 阻断 |
| 报表科目未映射 | `REPORT_MAPPING` | 有余额或发生额但无报表映射的科目 | 阻断 |
| 损益未结转 | `PROFIT_CARRY_FORWARD` | 有损益发生但无已记账结转凭证 | 阻断 |

每个测试先记录目标检查项的基线问题数，插入一条异常后必须精确增加一条；
仅出现其他阻断项不能代替目标检查项的断言。

## 执行方式

验收脚本只接受显式传入的验收库连接，不内置账号或密码：

```bash
COPPER_DIRTY_CLOSE_DB_URL='jdbc:mysql://127.0.0.1:3306/copper_dirty_close?...' \
COPPER_DIRTY_CLOSE_DB_USERNAME='验收账号' \
COPPER_DIRTY_CLOSE_DB_PASSWORD='由运行环境提供' \
bash tests/run-financial-close-dirty-data-mysql.sh
```

成功标记：

```text
DIRTY_CLOSE_ACCEPTANCE=PASS scenarios=10 rollback=verified
```

测试类使用 Spring 事务逐场景写入真实表，场景完成后回滚；`@AfterTransaction`
再次逐表确认期间、凭证、过账、库存、往来、税票、科目和银行夹具均为零残留。

## 2026-07-22 本地实库证据

- 数据库：MySQL 8.4，现有 `copper_v2` 结构，Flyway 31 个迁移校验通过。
- 结果：10 个场景，失败 0，错误 0，跳过 0。
- 业务结果：十个目标检查项均进入 `BLOCKED`，十次实际关账请求均被拒绝。
- 清场结果：验收事务回滚检查通过；随后从事务外复核九类标记及高位库存夹具，计数全部为 0。

## 持续门禁

GitHub Actions 的 `dirty-close-acceptance` 作业使用独立、临时 MySQL 8.4
数据库执行同一脚本。PR 的双架构镜像验证以及 `main` 的正式镜像构建均依赖该
作业成功，任何一类脏数据不再阻断关账时，发布链路必须失败。
