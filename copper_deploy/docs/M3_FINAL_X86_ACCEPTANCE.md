# M3-05 正式 x86_64 升级与空白恢复验收

> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/25>
>
> 目标版本：`0.2.0-rc.1 / schema 4`
>
> 唯一允许来源：`0.1.0-m2-rc.2 / schema 3`

## 1. 验收结论边界

本验收必须同时证明：

1. 正式 amd64 组件、发布清单和离线交付介质互相绑定；
2. 来源站在升级前生成并验证新的加密整站备份；
3. Backend 在隔离恢复副本上真实执行追加式 Flyway V4；
4. 故障或中断不把半迁移数据切为当前站，不重复库存、应收、应付或资金；
5. 正常人工切换后，V1/V2/V3 历史前缀不变、只增加 V4；
6. 当前版本新备份可以恢复到完全独立的空白站点并继续业务；
7. 只有全部门槛通过后，才能在同一 Deploy 提交创建不可变 `v0.2.0-rc.1`。

公网域名、备案和公网 HTTPS 不是本次数据库升级结论。中国区验收机使用回环 `external-proxy`，但 MySQL、Backend、Web 三层健康、登录、业务和数据验证不得省略。

## 2. 固定资产

- Backend 源码提交：`26939c113c5c224ccec013100e91686c87520d87`
- Backend GitHub Actions Run：`29319634754`
- Backend amd64 镜像：`ghcr.io/soyia/copper_backend@sha256:3ec8083df0b8dd1ffe12f23e283095b807a41c8524b0ab6cbe38a8f4aadfce04`
- Web amd64 镜像：`ghcr.io/soyia/copper_web@sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c`
- MySQL amd64 镜像：`mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`
- 迁移集合清单：`releases/0.2.0-rc.1/migrations.sha256`
- 迁移集合清单 SHA-256：`b295ce8dd1e900127f7c99dc47c0a5d58010038411071386c85cf49dee08f6d3`

上述镜像只能按摘要使用。Backend Actions 工件中的 `image-evidence.json` 和 `SHA256SUMS` 必须先通过，再允许导入交付构建机。正式离线索引必须同时绑定三个仓库发布摘要和归档内三个 amd64 内容 ID；目标升级环境只允许使用 root-only `READY` 收据记录的内容 ID。

## 3. 标签前字节冻结

版本规则禁止在最终演练通过前创建 `v0.2.0-rc.1`。因此先在已经同步到 GitHub `origin/main`、工作区干净且正式标签尚不存在时生成字节冻结候选：

```bash
COPPER_ACME_ARCHIVE_SOURCE=/secure/input/acme.sh-3.1.3.tar.gz \
  scripts/build-release.sh --candidate 0.2.0-rc.1 /secure/candidate/0.2.0-rc.1
```

候选目录必须包含 `CANDIDATE-NOT-A-RELEASE.json`，禁止对外交付。候选的 Deploy 包、发布清单、矩阵、说明和 ACME 摘要是本次真实演练的冻结输入。

全部验收通过后，在同一个 Deploy 提交创建标签并执行正式构建。正式构建产生的所有绑定文件 SHA-256 必须与候选完全一致；不一致即停止发布并重新验收：

```bash
git tag -s v0.2.0-rc.1 <已经验收的-main-提交>
COPPER_ACME_ARCHIVE_SOURCE=/secure/input/acme.sh-3.1.3.tar.gz \
  scripts/build-release.sh 0.2.0-rc.1 /secure/release/0.2.0-rc.1
```

## 4. 中国区离线交付

在能够访问镜像源且运行 Docker 29.x / Compose 5.x 的 Linux 构建机执行，不在开发电脑运行 Docker：

```bash
sudo scripts/build-release-delivery.sh offline \
  /secure/candidate/0.2.0-rc.1/copper-release-0.2.0-rc.1.env \
  /secure/delivery/copper-0.2.0-rc.1-offline
```

将目录、交付索引 SHA-256 和 Backend Actions 工件摘要通过独立渠道交接。中国区主机只允许 `release stage offline`；禁止 `curl`、`docker pull`、测试镜像覆盖或 `COPPER_TEST_MODE` 进入正常成功演练。

## 5. 来源站准备

来源站必须是新装或已验证的 `0.1.0-m2-rc.2 / schema 3`，首次管理员凭据已经交付、改密并销毁。准备一组同时覆盖以下口径的测试业务数据：

- 库存数量与成本；
- 客户应收、供应商应付；
- 实收、实付和核销状态；
- 至少一个附件及其 SHA-256；
- 可登录账户与继续记账条件。

完成客户备份公钥登记、离线恢复实证和最近备份成功状态。用下列入口固化来源证据：

```bash
sudo tests/run-m3-final-x86-acceptance.sh source \
  /etc/copper/copper.env \
  /var/lib/copper/releases/0.2.0-rc.1/copper-release-0.2.0-rc.1.env \
  /var/lib/copper-acceptance/source
```

## 6. 真实故障恢复

先完成一次独立故障演练，再进行最终成功升级。允许在隔离验收中启用测试故障点，但正式 READY、正式镜像、来源备份和经营数据必须保持不变。

推荐故障点为 `after-switch-audit`：隔离 V4 已通过，目标审计已短暂写入 `SWITCHED`，但在放行 Web 前中断。必须观察到：

```bash
sudo env \
  COPPER_TEST_MODE=1 \
  COPPER_PERSISTENT_REBOOT_TEST=1 \
  COPPER_RESTORE_TEST_CONFIRM=1 \
  COPPER_RESTORE_TEST_FAIL_AT=after-switch-audit \
  copperctl upgrade switch m305-failure-audit
```

该组合只用于已授权的空白 x86_64 验收机。路径校验仍要求 `/etc/copper`、`/var/lib/copper`、`/var/backups/copper`、`/var/lib/copper-restores` 和 `/etc/copper/copper.env` 完整成组，缺少持久化测试开关时拒绝正式目录；生产升级禁止设置这些测试变量。

- 旧站 MySQL、Backend、Web 自动恢复且重新验证；
- 升级状态回到 `MIGRATION_VALIDATED` 或明确的安全失败状态；
- 目标旁路的升级审计回退为 `MIGRATION_VALIDATED`，`switched_at/completed_at` 清空后才允许重试；
- Web 只在旧站验证通过后恢复；验证失败则保持停止；
- 来源 schema 仍为 3，V4 目标只留在隔离旁路；
- 来源经营汇总、附件和单据数量与故障前一致。

失败演练的 PREPARED、状态 JSON、事件、Backend/Flyway 日志和恢复比较结果必须保留到验收报告。不得把故障旁路直接用于最终成功切换。

## 7. 正常升级

重新生成 PREPARED，按标准命令执行：

```bash
sudo copperctl upgrade preflight /var/lib/copper/releases/0.2.0-rc.1/copper-release-0.2.0-rc.1.env
sudo copperctl upgrade prepare /var/lib/copper/releases/0.2.0-rc.1/copper-release-0.2.0-rc.1.env
sudo copperctl upgrade migrate --identity /secure/offline/AGE-KEY.txt --upgrade-id m305-final-upgrade --port 18090
sudo copperctl upgrade switch m305-final-upgrade
```

`switch` 必须在可信交互终端输入完整确认串。成功后执行：

```bash
sudo tests/run-m3-final-x86-acceptance.sh target \
  /etc/copper/copper.env \
  /var/lib/copper/releases/0.2.0-rc.1/copper-release-0.2.0-rc.1.env \
  /var/lib/copper-acceptance/target
```

人工复核 Flyway 只有成功的 V1/V2/V3/V4，V1–V3 的脚本和 checksum 与来源一致；`system_upgrade_audit` 必须唯一记录 `SWITCHED`、备份 ID 和发布清单摘要。随后完成至少一笔新的完整业务或等价受控记账，证明不是“只能查、不能继续用”。

## 8. 当前版本空白恢复

升级后创建并验证新的 `copper-backup/v1`。在同一物理验收机复用硬件时，必须使用完全清空的独立站点根目录、站点 ID、Compose 项目、密钥目录和恢复目录；不得复用原站 MySQL、附件、凭据、容器或网络状态。

恢复达到 `VALIDATED` 并人工切换后执行：

```bash
sudo tests/run-m3-final-x86-acceptance.sh replacement \
  /etc/copper-replacement/copper.env \
  /var/lib/copper-replacement/releases/0.2.0-rc.1/copper-release-0.2.0-rc.1.env \
  /var/lib/copper-acceptance/replacement
```

备份中的来源 `siteId` 与 envelope `siteFingerprint` 必须继续严格绑定；空白恢复站使用自己的目标 `siteId`，不得为了绕过校验冒充来源站点。恢复状态必须同时记录 `siteId`（目标站点）、`sourceSiteId` 和 `sourceSiteFingerprint`（备份来源），使跨服务器灾备既可执行又可审计。来源与目标使用同一 `siteId` 的原机恢复仍受支持。

目标站与空白恢复站的经营汇总、附件清单、表数量、Flyway、升级审计和继续业务结果必须一致。逻辑隔离证据必须列出两套不同的站点 ID、项目名、数据目录和密钥目录。

## 9. 发布与清理门槛

只有故障恢复、正常升级、继续业务和空白恢复全部通过后，才允许：

1. 在已验收 Deploy 提交创建并推送 `v0.2.0-rc.1`；
2. 正式构建并逐文件比对候选 SHA-256；
3. 生成正式 online/offline 交付索引和 root-only READY；
4. 创建 GitHub Release，附发布说明、清单、矩阵、校验文件和交付索引；
5. 更新 Issue、路线图和最终验收报告。

验收数据无保留价值时，使用产品危险删除流程清理。最终必须复核：没有 Copper 数据目录、容器、镜像、网络、卷或监听端口；宿主机 8191 始终未使用。
