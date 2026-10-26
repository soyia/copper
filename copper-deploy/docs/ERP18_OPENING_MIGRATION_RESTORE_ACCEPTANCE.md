# ERP-18 期初数据与旧系统迁移恢复验收

> 状态：`COMPLETED_CURRENT_INTERNAL_ERP_BASELINE`
>
> 验收日期：2026-07-24
>
> 边界：`INTERNAL_REHEARSAL_ONLY`，不是正式客户发布、正式灾难恢复或客户交付验收

## 冻结来源

| 仓库 | 已合并主线提交 | 用途 |
|---|---|---|
| `copper_backend` | `164cfb41a0d4b381080702bd7881c00f7a54d1a6` | V55 迁移、期初预检/应用/撤销/切换和 MySQL 验收 |
| `copper-web` | `16091796a16eb76f313fa063f2407e40b113de44` | 期初迁移工作区、权限入口和操作边界 |
| `copper_deploy` | 本验收 PR 合并提交 | 备份恢复显式合同、恢复锚点和证据文档 |

后端和 Web 镜像均从上述已合并源码在隔离验收机本地构建，没有向验收机传输 GitHub 令牌。镜像仅用于本次内部演练：

| 角色 | 本地镜像 ID |
|---|---|
| Backend | `sha256:c77c9e5234dda168c8229f622b4f423f01a9bdc7e044bb2a18d8ccc1d0ad7ee9` |
| Web 当前版本 | `sha256:c65d4612798f6c389cb575fb3887da589450182875f2a2aac2821b4a8532df52` |
| Web 不兼容负向版本 | `sha256:3aaec1d8caa13293b00fc29012ba63f7571db0243925d4d1cf4774b122db37a2` |

## 正式内部基线新增合同

备份恢复必须显式覆盖以下 6 张 ERP-18 表：

1. `data_migration_batch`
2. `data_migration_row`
3. `data_migration_event`
4. `data_migration_check_run`
5. `data_migration_check_issue`
6. `data_migration_fund_opening`

真实恢复锚点必须同时证明：

- 批次保持 `CUTOVER`，不能被恢复成可整批撤销状态；
- 两条逐行原始事实、目标映射和应用前快照保持不变；
- 状态事件、历史失败检查、阻断差异和最终通过检查均保留；
- 资金期初事实保持 `ACTIVE`，金额及会计科目引用未丢失；
- 恢复切换后的全表记录数、外键、业务合计和附件 SHA-256 与备份时点一致；
- 回滚后恢复备份时点之后的原站数据，不能把旁路数据错误留在当前站。

## 真实验收结果

执行入口：

```bash
sudo env \
  COPPER_M2_03_WEB_IMAGE=copper-web:erp18-current \
  COPPER_M2_03_OTHER_WEB_IMAGE=copper-web:erp18-other \
  COPPER_M2_03_BACKEND_IMAGE=copper-backend:erp18-164cfb4 \
  ./tests/run-m2-restore-local-database.sh
```

结果：

| 检查 | 结果 |
|---|---|
| MySQL | 8.4.10 |
| V55 恢复后基础表 | 232 张 |
| V8-V24 ERP 表合同 | 56/56 |
| ERP-18 迁移表合同 | 6/6 |
| ERP-18 迁移恢复锚点 | 1 个切换批次、2 条逐行事实、1 条状态事件、2 次检查、1 条历史差异、1 条资金期初，全部通过 |
| 负向恢复 | 错误私钥、包篡改、空间不足、镜像不兼容、附件缺失、执行中断，全部失败关闭 |
| 切换故障注入 | 自动恢复原站并重新达到三层健康 |
| 最终切换与回滚 | 通过 |
| 归档 | 122,880 bytes |
| 总耗时 | 343 秒 |
| 宿主隔离 | 未使用 8191；共享 `vm-mysql`、`vm-valkey` 身份及运行状态不变 |
| 清场 | 测试 Compose、旁路目录、18083/18084 监听均为 0 |

成功标记：

```text
v8_v24_restore_modules=passed
erp18_opening_migration_restore=passed
restored_table_count=232
archive_bytes=122880
m2_restore_runtime=passed
runtime_seconds=343
mysql_version=8.4.10
shared_vm_mysql_unchanged=passed
shared_vm_valkey_unchanged=passed
```

首轮演练发现部署旧断言仍按 `1.250` 比较库存调拨数量，而当前后端完整定点口径输出 `1.250000`。验收合同已改为后端当前 6 位数量精度，并从空环境重新执行完整链路；不得把首轮中途失败当作完成证据。

## 结论与边界

ERP-18 期初数据和旧系统迁移已进入当前内部 ERP 基线的备份恢复合同。原始来源、逐行校验、目标映射、切换状态、历史差异、资金期初和审计事件不会在备份恢复中被降级为备注或汇总余额。

该结论不代表正式客户迁移已完成。正式迁移仍要求客户确认冻结时点、字段映射、来源文件摘要、会计和税务复核、正式密钥持有、正式发布清单不可变镜像、客户环境恢复演练及签字验收。
