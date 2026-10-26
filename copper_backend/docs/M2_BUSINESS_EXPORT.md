# M2-04 完整业务导出

> 状态：2026-07-22 已在 `local-database` 完成 V30 / MySQL 8.4.10 真实导出验收；真实恢复证据见 `copper_deploy` 的 M2-03 演练
>
> 格式版本：`copper-export/v1`

## 1. 使用边界

- 业务导出用于让客户不运行 Copper、不依赖私有工具即可长期读取数据；它不能代替 `copper-backup/v1` 灾难恢复包。
- 导出由后端在 REPEATABLE READ 只读事务中完成，前端只创建、查询和下载任务，不分页拼接业务数据。
- M2-04 只开放 `FULL` 完整导出，任务清单和 `manifest.json` 均明确记录 `modules=ALL` 且日期范围为空。当前没有局部导出端点，因此不会把筛选结果误称为全量。
- 只有拥有 `data.export` 权限，且业务单据、往来单位、商品、仓库、资金账户数据范围全部为 `ALL` 的角色可以执行。
- `ADMIN` 和 `OWNER` 预置角色在 V2 迁移中获得该权限；自定义角色必须由管理员显式授权。

## 2. 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/business-exports` | 创建完整导出，正文为 `{"confirmFullExport":true}` |
| `GET` | `/api/business-exports` | 查看最近 100 个任务和当前活动任务 |
| `GET` | `/api/business-exports/{id}` | 查看进度、阶段、记录数、附件数和失败原因 |
| `POST` | `/api/business-exports/{id}/retry` | 重新生成失败任务，正文为 `{"confirmRetry":true}` |
| `GET` | `/api/business-exports/{id}/download` | 下载完成的 ZIP；响应头包含 `X-Content-SHA256` |

同一时刻只允许一个 `PENDING` 或 `RUNNING` 任务。并发重复请求返回同一任务，不会重复消耗数据库和磁盘。进度按字段字典、当前迁移创建的全部业务表、关键余额、附件、清单和打包校验真实推进，不使用伪造的固定动画。应用重启会把中断任务标记为失败并清理临时文件，用户可创建保留原失败记录的新重试任务。

## 3. 导出包

```text
README.html
manifest.json
schema/tables.csv
schema/fields.csv
schema/enums.csv
schema/business-totals.csv
master-data/*.csv
purchasing/*.csv
inventory/*.csv
sales/*.csv
receivables-payables/*.csv
cash-and-settlement/*.csv
accounting/*.csv
tax-invoices/*.csv
financial-close/*.csv
crm/*.csv
audit/*.csv
attachments/manifest.csv
attachments/**
checksums.sha256
```

- CSV 为 UTF-8 with BOM、RFC 4180 引号规则和 CRLF 行尾，可由 Excel、LibreOffice 和通用程序读取。
- 十进制值使用普通字符串，不使用科学计数法；时间为带 UTC 偏移量的 ISO 8601，同时保留数据库中的业务日期字段。
- `fields.csv` 给出类型、精度、小数位、是否可空、主键和外键；`enums.csv` 给出实际出现的机器值和中文说明。
- 附件保持原始字节，以业务 ID 和附件 ID 形成稳定相对路径；清单包含原文件名、业务关联、大小和 SHA-256。除收货附件外，还覆盖跨单据签字证据、税票导入原件、银行流水导入原件和会计电子归档包。
- `checksums.sha256` 覆盖 ZIP 内除清单自身之外的全部文件；服务端完成后重新打开 ZIP 验证条目和校验清单，再原子发布下载文件。

`manifest.json` 核心字段示例：

```json
{
  "formatVersion": "copper-export/v1",
  "scopeType": "FULL",
  "filters": {
    "modules": "ALL",
    "businessDateFrom": "",
    "businessDateTo": "",
    "dataScope": "ALL"
  },
  "timeZone": "Asia/Shanghai",
  "schemaVersion": "30",
  "tableCount": 151,
  "decimalEncoding": "plain-string-no-scientific-notation",
  "timestampEncoding": "ISO-8601-with-offset",
  "checksumAlgorithm": "SHA-256"
}
```

V8-V24 的 56 张 ERP 新表是显式合同，不依赖模糊前缀碰巧命中。迁移建表集合、目录映射、导出阶段和模块数量由 `BusinessExportCatalogTest` 固定；迁移新增或改名时，测试会要求同步评审导出与恢复。模块覆盖包括库存新单据、业务过账、会计凭证和总账、税票、银行对账和结账、独立 CRM。

当前 V55 迁移集合共有 231 张应用表；排除 `system_user_session` 与瞬时并发锁表 `inventory_atp_lock` 后，229 张应用业务/审计表进入导出（Flyway 历史表另行明确排除）。ERP-18 新增的六张期初迁移表统一进入 `audit/`，并在 `business-totals.csv` 提供批次总行数、通过/错误/应用行数、现金银行期初金额和一致性差异数。下方 V30 数字是当时真实验收快照，不改写为当前表数。

## 4. 安全和审计

以下运行秘密不进入业务导出：

- `system_user.password_hash`
- `system_user_session` 整表（会话令牌）
- 数据库、Cookie、对象存储等运行密钥
- 附件表中的 `file_content` BLOB（原始字节已单独输出）

申请、开始、完成、失败、重新生成、中断恢复和下载均写入 `security_audit_log`。正式日志只记录任务 ID、阶段和错误类型，不记录密码、Cookie、业务行内容或附件内容。

导出文件仍包含完整经营数据和个人信息，下载后应由客户自行控制访问、复制、留存和销毁。磁盘低于 128 MiB 时，系统在任务开始前拒绝生成大文件。

## 5. 真实验收证据

验收脚本：`tests/run-m2-business-export-local-database.sh`

环境和结果：

| 项目 | 结果 |
| --- | --- |
| 主机 | `local-database`，Linux aarch64，Java 21 |
| 数据库 | MySQL 8.4.10，共享 `vm-mysql` |
| 自动化测试 | Maven 全量测试通过；M2-04 格式、表覆盖和迁移专项测试通过 |
| 迁移后表数 | 153（V30，含 Flyway 历史表） |
| 导出业务表 | 151（排除 Flyway 历史表和会话令牌表） |
| 导出记录 | 11,681 |
| 原始附件 | 5 个，字节数和 SHA-256 一致 |
| 导出包 | 168,395 bytes |
| 运行时间 | 18 秒（含构建、全量测试、启动、造数、成功/失败/重试验收和清理） |

脱敏数据覆盖采购、入库、批次、库存、销售、出库、应收、应付、收款、付款、往来余额、附件和 10,000 条扫描记录，并增加调拨、盘点、采购退货、成本调整、业务过账、会计凭证、总账、税票、银行对账、期末结账和 CRM。验收同时确认：

- 并发重复请求只生成一个活动任务；
- 缺失附件时整包失败，不发布半成品；
- 删除故障数据后可重新生成，原失败任务和审计记录保留；
- CSV 十进制精度、时区、关键库存/应收汇总、秘密排除和 ZIP 路径安全均通过；
- V8-V24 的 56 张表均存在对应 CSV，模块业务合计可复核且 `manifest.json.rowCounts` 包含代表性业务事实；
- 8191 未被占用，共享 `vm-mysql`、`vm-valkey` 容器 ID 不变；
- 测试数据库、测试账号、8193 进程和临时数据均已清理。

## 6. 运维配置

可通过 `COPPER_EXPORT_PATH` 单独指定导出目录；未设置时默认使用 `${COPPER_DATA_ROOT}/exports`，兼容现有部署。该目录必须位于客户持久存储中并只授予 Copper 运行账号访问权限。

本项不需要 x86_64 云服务器。x86_64 空白服务器上的备份、恢复、迁移和自由导出最终组合验收统一在 M2-07 执行。
