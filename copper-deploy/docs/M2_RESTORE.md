# M2-03 一致性旁路恢复与回滚

> 状态：2026-07-24 已在 `local-database` 以 Backend `164cfb41a0d4b381080702bd7881c00f7a54d1a6` 完成 V55/ERP-18 期初迁移控制事实、V8-V24 ERP 模块、附件证据、负向阻断和切换回滚的 MySQL 8.4.10 真实恢复演练

M2-03 恢复本发布工具生成并通过校验的 `copper-backup/v1` 全量包，以及可选的连续 `copper-backup/v2` 增量父链。恢复始终先进入新的 restore-ID 目录和独立 Compose 项目；在完整验证通过、操作员明确确认之前，不修改当前 MySQL、附件或对外流量。

## 强制边界

- 生产基线仍是 Ubuntu Server 24.04 LTS x86_64、Docker Engine 29.x、Compose 5.x 和 MySQL 8.4.10。
- M2 只支持 `manifest.json` 中 Web、Backend、MySQL 三个不可变镜像与当前环境完全一致；跨版本恢复属于 M3 升级迁移，不得混用。
- 增量恢复必须从一个 v1 全量基础包开始，随后按 `chainSequence` 顺序提供每个 v2 包；缺少、重复、倒序、父 ID 不匹配或 binlog 边界不连续均在创建旁路前失败关闭。
- 旁路 Web 只绑定 `127.0.0.1:COPPER_RESTORE_PROXY_PORT`；MySQL 与 Backend 不发布宿主机端口，禁止使用 8191。
- age 私钥由客户离线保管，只在恢复命令执行时从受限普通文件读取；不会复制到恢复目录、日志、状态文件或容器。
- 切换仅交换 MySQL 数据目录和附件目录。当前配置、密钥、日志、Caddy 数据和备份目录不被备份包覆盖。
- 切换失败或回滚失败时，必须先恢复并验证上一份数据，再启动 Web。验证失败时 Web 保持停止，状态为 `RECOVERY_FAILED`，禁止人工绕过 readiness。

## 首次准备

在 `/etc/copper/copper.env` 设置：

```text
COPPER_RESTORE_ROOT=/var/lib/copper-restores
COPPER_RESTORE_PROXY_PORT=18080
```

然后运行：

```bash
sudo copperctl install
sudo copperctl doctor
```

`install` 会以 `root:root 700` 创建恢复根目录。生产模式不允许改变该目录；隔离测试只允许 `/tmp/copper-*` 或 `/opt/copper-*`。

空白目标站也必须先按安装手册完成首次管理员一次性凭据交付和改密，并确认 `sudo copperctl admin init` 显示没有待交付文件。该密码只用于切换失败或回滚后的空白原站，须保留到回滚窗口结束。`stage` 会拒绝仍留有一次性明文凭据的目标站，避免切换后把旧站凭据错误暴露给恢复数据。源站同样必须先完成首次改密，否则 `backup create` 会拒绝生成无法独立交付管理员凭据的备份。

## 1. 创建并验证旁路恢复

备份主包和同名 SHA 文件必须放在一起：

```text
copper-prod_20260713T010203Z_<backup-id>_manual.copperbak
copper-prod_20260713T010203Z_<backup-id>_manual.copperbak.sha256
```

执行：

```bash
sudo copperctl backup restore stage \
  /secure-media/copper-prod_20260713T010203Z_<backup-id>_manual.copperbak \
  --identity /secure-media/customer-age-identity.txt \
  --restore-id restore-20260713-a \
  --port 18080
```

恢复到某个每日增量点时，把全量基础包放在第一个位置，并按时间顺序重复传入增量包：

```bash
sudo copperctl backup restore stage \
  /secure-media/周日全量.copperbak \
  --incremental /secure-media/周一增量.copperbak \
  --incremental /secure-media/周二增量.copperbak \
  --identity /secure-media/customer-age-identity.txt \
  --restore-id restore-20260721-chain-a \
  --port 18080
```

每个 `.copperbak` 必须与自己的同名 `.sha256` 放在一起。不得跳过周一直接回放周二；若只需要恢复到周一，就只提供周一为止的连续链。

### 客户云主机没有可挂载安全介质

私钥仍由客户保存在管理电脑，禁止先 `scp` 到主机家目录或任何持久磁盘。先确认目标机 `/run` 是 tmpfs，再通过 SSH 标准输入写入 root-only 临时文件：

```bash
test "$(ssh -i 客户SSH密钥 ubuntu@server 'findmnt -n -o FSTYPE /run')" = "tmpfs"
ssh -T -i 客户SSH密钥 ubuntu@server \
  'sudo tee /run/copper-restore-identity.txt >/dev/null && sudo chown root:root /run/copper-restore-identity.txt && sudo chmod 0400 /run/copper-restore-identity.txt' \
  < /客户离线介质/copper-recovery-key-01/copper-age-identity.txt
```

只导出公钥核对，不得 `cat` 私钥：

```bash
ssh -i 客户SSH密钥 ubuntu@server \
  'sudo age-keygen -y /run/copper-restore-identity.txt'
```

输出必须与服务器登记的活动 `age1...` 公钥完全一致。随后在同一次受控操作中执行恢复并无条件删除临时文件：

```bash
ssh -i 客户SSH密钥 ubuntu@server '
cleanup_identity() { sudo rm -f /run/copper-restore-identity.txt; }
trap cleanup_identity EXIT HUP INT TERM
status=0
sudo copperctl backup restore stage /var/backups/copper/完整原文件名.copperbak \
  --identity /run/copper-restore-identity.txt --restore-id restore-验收编号 --port 18080 || status=$?
cleanup_identity
sudo test ! -e /run/copper-restore-identity.txt && echo VOLATILE_IDENTITY_DESTROYED
trap - EXIT HUP INT TERM
exit "$status"
'
```

命令用 shell trap 在正常结束、断开和常见中断信号下清理临时私钥；仍不得主动中断。若连接异常且没有看到 `VOLATILE_IDENTITY_DESTROYED`，重新连接后的第一步必须是 `sudo rm -f /run/copper-restore-identity.txt` 并确认路径不存在，再调查恢复状态；不得把临时文件留到主机重启。Copper 恢复流程只读取该文件，不把它复制进恢复目录、容器、日志或状态。此方式证明的是客户云内部演练；正式客户交付仍应优先使用客户控制的只读安全介质和双人操作。

`stage` 按顺序完成以下门槛：

1. 检查外层 SHA-256、tar 成员类型与路径、envelope、密文大小和摘要。
2. 使用 age 私钥解密；限制解压最大写入量，避免压缩炸弹耗尽磁盘。
3. 检查内层全部文件 SHA-256、外内层 backup ID、站点 ID、镜像版本、MySQL/结构版本、附件清单和无秘密配置允许清单；有增量时另外验证 base/parent/sequence、binlog 起止边界和附件墓碑链。
4. 拒绝服务器级 SQL、系统数据库写入及 `copper_v2` 之外的数据库；确认目标数据库为空后先导入 v1 逻辑全量，再用同版本 `mysqlbinlog` 依次回放每个 v2 增量。
5. 在独立 MySQL 中恢复数据库，在 restore-ID 附件目录先恢复全量原文件，再逐包应用新增/变化文件和删除墓碑；使用当前机器新生成/当前有效的数据库密钥，不恢复旧密钥。
6. 逐项比较数据库结构、全部表记录数、外键孤儿、库存、成本、应收、应付、预收占用、资金收付、调整、结算与承兑指标；并强制核对 V8-V24 的 56 张 ERP 表，以及 ERP-18 的 6 张期初迁移控制表、切换状态、逐行原始事实、历史差异、资金期初和审计链。
7. 逐项比较附件相对路径、文件数、字节数、SHA-256、数据库引用和孤儿文件数，包括业务签字证据、税票导入原件、银行流水导入原件和会计电子归档。
8. 数据验证通过后才启动 Backend；再次验证无迁移漂移后才启动旁路 Web。

成功状态为 `VALIDATED`，当前生产数据尚未修改。可查看：

```bash
sudo copperctl backup restore status
sudo copperctl backup restore status restore-20260713-a
curl --fail http://127.0.0.1:18080/readyz
```

需要在管理电脑核对界面时，使用 SSH 回环隧道，不要开放公网端口：

```bash
ssh -L 18080:127.0.0.1:18080 ec2-user@server
```

然后只在该电脑访问 `http://127.0.0.1:18080`，核对库存、往来余额、收付款、审计记录和附件。

登录本身会依法留下会话和安全审计记录，因此“用于人工登录核对的旁路实例”不能直接切为生产。核对完成后先 `discard`，再用同一备份创建一个新的 restore ID；新实例达到 `VALIDATED` 后不要再登录，直接执行 `switch`。`switch` 会在停止当前站点之前重新比较旁路的全部表数、余额、外键和附件；发现任何登录或其他写入时状态变为 `DIRTY`，当前站点完全不停止。

## 2. 人工确认后切换

```bash
sudo copperctl backup restore switch restore-20260713-a
```

命令要求可信交互终端输入以下完整文本：

```text
SWITCH <site-id> restore-20260713-a
```

切换过程先停止当前 Web/Backend，等待事务归零，保存切换前完整验证合同，再停止当前 MySQL 和旁路栈。只有两个数据目录位于同一文件系统时，才使用目录重命名交换 MySQL 与附件。随后只启动 MySQL/Backend并复核恢复合同；通过后才启动 Web。成功状态为 `SWITCHED`，原 MySQL 与附件仍保留在 restore-ID 的 `rollback/` 下，旁路运行所用的数据库密钥副本立即销毁。

## 3. 回滚到切换前数据

```bash
sudo copperctl backup restore rollback restore-20260713-a
```

交互确认文本为：

```text
ROLLBACK <site-id> restore-20260713-a
```

回滚同样先停写，再交换目录；MySQL/Backend 启动后必须与切换前验证合同完全一致，之后才恢复 Web。成功状态为 `ROLLED_BACK`，从备份恢复出的数据保留在旁路目录，便于调查。

## 4. 删除旁路数据

只有 `FAILED`、`VALIDATED`、`DIRTY` 或 `ROLLED_BACK` 可以删除：

```bash
sudo copperctl backup restore discard restore-20260713-a
```

交互确认文本为：

```text
DISCARD restore-20260713-a
```

`SWITCHED` 和 `RECOVERY_FAILED` 持有唯一回滚数据，命令会拒绝删除。不得手工 `rm -rf /var/lib/copper-restores`。

升级切换产生的 `SWITCHED` 也不能通过普通 `discard` 删除。只有上层 `copperctl upgrade accept <upgrade-id>` 在确认新版本健康、当前版本匹配且升级后备份已经由客户私钥离线验证后，才会调用专用验收收口并释放旧版本回滚副本；普通恢复没有该入口。

## 状态含义

| 状态 | 含义 | 当前流量 |
|---|---|---|
| `STAGING` | 正在解密、导入或验证 | 当前站点不变 |
| `FAILED` | 旁路恢复失败，容器与敏感暂存已清理 | 当前站点不变 |
| `VALIDATED` | 旁路三层健康且数据合同完全匹配 | 当前站点不变 |
| `DIRTY` | 验证后发生登录或其他写入，必须重新 stage | 当前站点不变 |
| `SWITCHED` | 恢复数据已切为当前，原数据保留 | 恢复数据对外 |
| `ROLLED_BACK` | 已恢复切换前数据并验证 | 原数据对外 |
| `RECOVERY_FAILED` | 自动恢复未通过验证 | Web 保持停止 |

每个 restore-ID 目录包含权限为 600 的 `state.json` 与追加式 `events.jsonl`。事件只记录 ID、动作、结果和非秘密错误摘要，不记录私钥、数据库口令或管理员凭据。

如果已创建旁路 Compose 栈后失败，恢复命令会先把 `docker compose ps` 以及 MySQL、Backend、Web 日志保存到 `diagnostics/`，再清理旁路容器、密钥副本、解密文件和数据目录。诊断目录为 `root:root 0700`，文件为 `0600`，只保留到执行 `discard`；其中可能含业务异常上下文，交给外部人员前必须脱敏。恢复私钥、数据库密码、管理员密码和 Compose 环境展开结果不会写入该目录。

## V55/ERP-18 真实恢复证据

2026-07-24 的隔离演练使用两份摘要不同的 Web 镜像，以及从已合并 Backend 主线提交 `164cfb41a0d4b381080702bd7881c00f7a54d1a6` 在验收机本地构建的 ARM64 镜像。测试镜像只用于 `INTERNAL_REHEARSAL_ONLY`，不进入 Release 或客户交付。验收结果如下：

| 项目 | 结果 |
|---|---|
| MySQL | 8.4.10 |
| 恢复后基础表 | 232 张 |
| V8-V24 显式合同 | 56 张，逐表存在性通过 |
| ERP-18 显式合同 | 6 张期初迁移控制表，逐表存在性通过 |
| 期初迁移锚点 | `CUTOVER` 批次、2 条逐行原始事实、1 条状态事件、2 次一致性检查、1 条历史阻断差异、1 条资金期初事实，切换后全部一致 |
| 新增模块 | 库存新单据、业务过账、会计凭证、总账、税票、银行对账、关账、CRM 的锚点记录和合计通过 |
| 新增附件 | 业务签字证据、税票导入、银行流水导入、会计电子归档的路径、大小和 SHA-256 通过 |
| 失败关闭 | 错误私钥、篡改、低空间、镜像不兼容、附件缺失、执行中断通过 |
| 切换安全 | 故障注入后自动恢复原站；最终无故障切换通过 |
| 归档与耗时 | 122,880 bytes；343 秒 |
| 环境隔离 | 未使用 8191；共享 `vm-mysql`、`vm-valkey` 容器不变 |

演练由 `tests/run-m2-restore-local-database.sh` 调用 `tests/m2-restore-runtime.sh` 完成；成功标记为 `v8_v24_restore_modules=passed`、`erp18_opening_migration_restore=passed` 与 `m2_restore_runtime=passed`。完整证据边界见 `docs/ERP18_OPENING_MIGRATION_RESTORE_ACCEPTANCE.md`。该结果证明当前 V55/ERP-18 的内部恢复合同，不替代 M2-07 在全新服务器上的正式灾难恢复验收。

## 旧 M1 备份

旧 `.tar.gz` 同版本基础备份仍兼容原命令：

```bash
sudo copperctl backup restore /path/to/legacy-backup.tar.gz
```

旧格式是覆盖式恢复，仅用于已有 M1 灾备，不具备 M2-03 的旁路验证与可回滚切换能力。新的备份链必须使用 `copper-backup/v1` 全量基础包和可选的连续 `copper-backup/v2` 增量包。
