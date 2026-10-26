# M3 隔离迁移、失败保护与人工切换

M3-03 只接受 M3-02 生成的同一份 `PREPARED` 证据。目标 Backend/Flyway 先在加密备份恢复出的隔离旁路上迁移，正式站点在 `MIGRATION_VALIDATED` 之前不会停止、不会改 schema、不会替换镜像。

## 1. 标准操作顺序

先完成 M3-02：

```bash
sudo copperctl upgrade prepare /安全发布目录/copper-release-VERSION.env
sudo copperctl upgrade status
```

使用客户离线保管的 age 私钥创建隔离迁移：

```bash
sudo copperctl upgrade migrate \
  --identity /离线介质/AGE-KEY.txt \
  --upgrade-id upgrade-20260714-a1b2c3d4 \
  --port 18090
sudo copperctl upgrade status upgrade-20260714-a1b2c3d4
```

`migrate` 会一次性 claim 当前 `PREPARED`，用其中绑定的精确备份恢复旁路，先验证来源数据，再只在旁路启动目标 Backend/Flyway。成功状态必须是 `MIGRATION_VALIDATED`。

在可信交互终端执行切换：

```bash
sudo copperctl upgrade switch upgrade-20260714-a1b2c3d4
```

终端要求输入：

```text
UPGRADE <site-id> upgrade-20260714-a1b2c3d4
```

切换成功后旧 MySQL、附件和旧 `copper.env` 仍保存在 root-only 回滚槽；`copperctl`、`copper-install` 和 `copper-maintain` 三个系统入口已经原子指向目标版本。需要回退时执行：

```bash
sudo copperctl upgrade rollback upgrade-20260714-a1b2c3d4
```

迁移失败、尚未切换、写脏或已经回滚，确认不再需要旁路后执行：

```bash
sudo copperctl upgrade discard upgrade-20260714-a1b2c3d4
```

`discard` 只接受迁移失败、未切换、写脏或已完成回滚的状态。`SWITCHED` 和 `RECOVERY_FAILED` 仍持有唯一恢复证据，禁止删除。完成 discard 后，原始 PREPARED、claim 和发布契约移入本次运行目录归档，才释放下一次 `upgrade prepare`。

切换成功后不能用 `discard` 删除旧版本。操作者必须先使用新版本登录并核对经营数据，再创建一份切换后的新整站备份；该备份必须下载到客户电脑、使用客户私钥离线验证、复制到第二位置，并把验证回执登记回服务器。随后在可信终端执行：

```bash
sudo copperctl upgrade accept upgrade-20260714-a1b2c3d4
```

确认文字为 `ACCEPT UPGRADE <site-id> <upgrade-id>`。命令会再次检查三层健康、当前版本、备份时间、备份实体 SHA-256，以及活动客户密钥下与该备份精确匹配的离线验证回执。全部通过后才删除切换前的旧数据库、旧附件和旧配置副本，归档升级证据并释放下一次升级。任何门槛失败都保留回滚副本。

## 2. 迁移验证合同

旁路迁移只有同时满足以下条件才进入 `MIGRATION_VALIDATED`：

- 目标数据库仍是 MySQL 8.4 LTS，最新成功 Flyway 版本精确等于发布清单的 target schema；
- 来源 Flyway 历史必须是目标历史的逐行原样前缀，禁止 repair、删行、改 checksum 或失败记录；
- 来源已有表不能无声明消失；新增表允许出现，既有表记录数变化写入验证报告供审计；
- 库存、应收应付、收付款、票据和调整等经营余额必须与迁移前一致；
- 附件路径、字节数、SHA-256 和数据库引用必须一致；
- 全部目标外键孤儿数为零；
- 目标 MySQL、Backend、Web 三层健康。

当前自动合同适用于向后兼容、保留来源表的迁移。需要删除/合并来源表或改变经营余额的版本，必须先扩展发布清单中的专项迁移验证合同，不能通过放宽本门槛上线。

## 3. 切换与恢复事务

切换前会再次捕获旁路的 schema、全表记录数、经营余额、附件、外键和完整 Flyway 历史。任何写入都会进入 `DIRTY`，此时正式 Web/Backend 尚未停止。

人工确认后才执行以下事务：

1. 停止当前 Web/Backend并等待 MySQL 活跃事务归零；
2. 捕获当前站完整验证合同；
3. 停止当前 MySQL 与旁路栈；
4. 在同一文件系统用目录重命名交换 MySQL 与附件；
5. 从发布合同绑定的部署包暂存目标程序，并原子交换 Web/Backend/MySQL 镜像配置以及 `copperctl`、`copper-install`、`copper-maintain` 三个系统入口；
6. 只启动目标 MySQL/Backend，复核完整目标合同；
7. 通过后才启动 Web。

任一步失败都会反向恢复旧数据目录、旧运行配置和旧维护程序入口，再用旧镜像启动 MySQL/Backend 并验证旧合同。验证通过才恢复 Web；目标旁路也能重新健康时状态回到 `MIGRATION_VALIDATED`，目标旁路无法恢复时旧站仍保持健康但状态进入 `MIGRATION_FAILED`，必须 discard 后重做。旧站验证不通过才进入 `RECOVERY_FAILED`，此时 Web 保持停止，禁止人工绕过 readiness。

回滚使用对称事务：恢复旧数据、旧配置和旧维护程序入口后先验证，再恢复 Web；回滚中途失败时重新恢复目标数据、配置和维护程序入口并验证目标站。成功状态为 `ROLLED_BACK`。

## 4. 状态与证据

| 状态 | 含义 | 是否对外切换 |
| --- | --- | --- |
| `CLAIMED` | 精确 PREPARED 已被一次性占用 | 否 |
| `RESTORING` | 正在把准备备份恢复到隔离旁路 | 否 |
| `MIGRATING` | 目标 Backend/Flyway 只作用于旁路 | 否 |
| `MIGRATION_FAILED` | 迁移或验证失败，诊断已保留 | 否 |
| `MIGRATION_VALIDATED` | 目标旁路通过全部合同，等待人工切换 | 否 |
| `DIRTY` | 验证后旁路发生写入，必须 discard 后重做 | 否 |
| `SWITCHING` | 已进入人工切换事务 | 切换中 |
| `SWITCHED` | 目标站通过验证并恢复 Web | 是 |
| `ROLLING_BACK` | 正在执行对称回滚 | 回滚中 |
| `ROLLED_BACK` | 来源站通过验证并恢复 Web | 否 |
| `RECOVERY_FAILED` | 自动恢复未通过，Web 强制停止 | 否 |
| `ABORTED` | 失败或未切换旁路已删除，证据已归档 | 否 |
| `COMPLETED` | 新版本已接受，或回滚旁路已清理；证据已归档 | 按最终站点状态 |

运行状态位于：

```text
${COPPER_VAR_DIR}/upgrades/runs/<upgrade-id>/state.json
```

旁路、诊断、迁移前后合同和回滚槽位于：

```text
${COPPER_RESTORE_ROOT}/<upgrade-id>/
```

状态转换和恢复操作共用非阻塞锁，非法、重复或并发命令均拒绝执行。禁止手工编辑状态 JSON、Flyway 历史、回滚目录或 `copper.env`。

## 5. 边界

- M3-03 不负责从仓库或离线包交付目标镜像；执行 migrate 前目标不可变镜像必须已存在，Compose 固定使用 `--pull never`，不会隐式访问镜像仓库。交付属于后续 M3 项。
- M3-03 不创建真实 `0.2.0` 标签或发布；测试清单和注入迁移只用于隔离验收。
- `COPPER_UPGRADE_TEST_TARGET_*` 只在 `COPPER_TEST_MODE=1` 的隔离验收中允许，生产环境拒绝使用。
- 本流程不能替代发布批准、变更窗口、业务负责人验收和备份私钥离线保管。
