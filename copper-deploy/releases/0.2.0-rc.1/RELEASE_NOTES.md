# Copper 0.2.0-rc.1 发布说明

> 渠道：内部验收 / 明确同意的试点 RC

## 功能变化

- 数据库结构从 Flyway schema 3 升级到 schema 4。
- 仅追加 `V4__system_upgrade_audit.sql`，新增 `system_upgrade_audit` 表，记录升级 ID、来源/目标产品版本和 schema、升级前备份 ID、发布清单 SHA-256、迁移验证与切换时间。
- Web 没有变化，继续复用已经验收的不可变 amd64 镜像。
- Backend 包含 V1/V2/V3 原始迁移和追加式 V4，不修改任何已发布迁移。
- 升级审计表纳入客户可自由读取的 `copper-export/v1` 审计目录。

## 数据库影响

- V4 只创建新表，不 `ALTER`、`UPDATE`、`DELETE`、`DROP` 或 `TRUNCATE` 既有业务表。
- V4 不改变库存、批次、应收、应付、收付款、票据、附件或历史单据数据。
- 目标 Backend 只在隔离恢复副本上执行 Flyway；迁移、经营余额、附件、外键和历史迁移前缀验证全部通过后，才允许人工切换。

## 支持的直接升级

- 仅支持 `0.1.0-m2-rc.2 / schema 3` 直接升级到 `0.2.0-rc.1 / schema 4`。
- 其他来源版本、schema 或跳级升级均未声明支持，预检会拒绝。
- 停机等级为维护窗口；回退只能使用完整保留的旧站旁路或升级前已验证的 `copper-backup/v1`，禁止逆向 SQL 或只换回旧 Backend。

## 升级步骤

1. 校验并暂存联网或离线 `copper-delivery/v1`，得到 root-only `READY`。
2. 执行 `copperctl upgrade preflight` 和 `upgrade prepare`，生成并验证本次专属加密整站备份，同时将 `READY` 收据及其 SHA-256 绑定到只读 `PREPARED` 证据。
3. 执行 `upgrade migrate`，在隔离副本完成 V4 和数据合同验证。
4. 在可信交互终端人工执行 `upgrade switch`。
5. 验收当前版本、schema、三层健康、升级审计、库存/应收应付/资金、附件和继续记账；确认后再处理旧站旁路。

## 停机与回退

- 升级停机等级为维护窗口；正式切换必须由可信终端人工确认。
- 失败时优先恢复完整保留的来源站；需要重建时只允许使用升级前已验证的 `copper-backup/v1`。
- 禁止执行逆向 SQL、删除 Flyway 历史或只把 Backend 镜像换回旧版。

## 已知问题

- 这是 RC，不作为无人值守生产自动升级目标，不强制客户升级。
- 中国区公网域名访问不属于本次数据库升级结论；无备案环境使用回环 external-proxy 完成三层和业务验收。
- 新版本备份不能交给旧版本代码猜测恢复；必须使用清单声明支持的同版本工具。
