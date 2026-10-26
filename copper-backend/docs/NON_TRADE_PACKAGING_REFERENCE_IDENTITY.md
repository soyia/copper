# V68 非购销包装引用身份保护

2026-10-16。V1–V67 原文件保持不变；本修复使用新 V68，不重写历史单据、包装使用、包装流水或既有正引用。

## 问题与前向合同

V65 把旧单据迁移为明细时，`packaging_ref_id` 沿用旧单据 ID；后来创建的明细使用自己的自增 ID。旧单据自增存在回滚或删除空洞时，新明细 ID 可能追上一个已使用的旧引用。同一批次的两个单据若因此共享引用，冲正可能同时恢复两单包装，库存却只恢复一单。

V68 在业务表 DDL 前先检查：

- 已持久化的 `packaging_ref_id=0`：`V68_PACKAGING_REF_ZERO_BLOCK`。
- 两个明细共用引用：`V68_PACKAGING_REF_DUPLICATE_BLOCK`。
- 下一个引用超过 Java Long 正值范围：`V68_PACKAGING_REF_JAVA_LONG_EXHAUSTED`。

失败时禁止自动猜测归属、重编号、删除或冲正历史；应结合原单、批次、具体包装和时间线形成受控修正方案，再在恢复的隔离副本验证。

通过预检后，在一条原子表 DDL 中添加 `UNIQUE(packaging_ref_id)`，并把明细自增值推进到以下高水位以上：既有明细 ID、既有包装引用、非购销包装 usage、movement、包装实例 operation 引用。已有更高的自增计数器保留，不因 MAX(id) 较低而回退。该值始终不超过 `9223372036854775807`。

新建服务仍先以 `0` 插入，再在同一事务中以生成的明细 ID 替换。唯一索引中的临时 `0` 可能让另一个创建事务等待；第一个创建事务在返回前已取得全部批次锁、完成引用替换，不再等待第二个事务才会释放的资源。成功提交后不存在重复引用；回滚消耗的号码不复用。空洞是正常现象，不能回退自增值来追求连续编号。

包装冲正的 usage、movement 选择同时限定批次和商品；实重 usage 还核对实例当前批次和商品。它们是防止跨批次误路由的第二层保护，不替代全局唯一引用，也不自动修复已有碰撞。

## 本轮验证入口

两个实库测试类均使用 `COPPER_PACKAGING_REF_E2E=true`：

- `NonTradePackagingReferenceMigrationMySqlAcceptanceTest`：另需 `COPPER_PACKAGING_REF_DB_URL`、`COPPER_PACKAGING_REF_DB_USERNAME`、`COPPER_PACKAGING_REF_DB_PASSWORD`，账号须能创建、删除 UUID 命名的 `copper_pkref_*` 数据库并执行迁移 DDL/存储过程。覆盖 V64 旧单据空洞经 V65–V68 升级、各引用来源与较高自增计数器、零/重复/Long 耗尽失败关闭、临时 0 的两个并发创建，以及 CREATE TABLE/INSERT 逻辑转储中的自增元数据往返。
- `NonTradePackagingReferenceBehaviorMySqlAcceptanceTest`：复用当前隔离 `COPPER_DB_*` 数据源，事务回滚并核对临时商品、仓库零残留。验证同批次两单只冲正所选单据，以及实重和普通包装恢复不会套用其他批次的 usage/movement。

```bash
COPPER_PACKAGING_REF_E2E=true \
COPPER_PACKAGING_REF_DB_URL='<隔离实例 JDBC URL>' \
COPPER_PACKAGING_REF_DB_USERNAME='<可建删隔离库的账号>' \
COPPER_PACKAGING_REF_DB_PASSWORD='<由环境注入>' \
mvn -B -ntp -Dtest=NonTradePackagingReferenceMigrationMySqlAcceptanceTest,NonTradePackagingReferenceBehaviorMySqlAcceptanceTest test
```

测试源已通过 `mvn -q -DskipTests test-compile`；真实 MySQL 结果以本轮发布汇总中的当前执行报告为准。逻辑转储元数据回归仅证明引用计数器合同，不替代整站备份与客户灾难恢复验收。
