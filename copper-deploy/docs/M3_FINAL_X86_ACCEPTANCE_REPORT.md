# Copper M3-05 正式 x86_64 最终验收报告

> 验收日期：2026-07-14
>
> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/25>
>
> 正式 RC：<https://github.com/soyia/copper_deploy/releases/tag/v0.2.0-rc.1>
>
> 结论：`0.1.0-m2-rc.2 / schema 3` 到 `0.2.0-rc.1 / schema 4` 的正式离线交付、真实升级、故障恢复、继续业务和独立空白站恢复全部通过，M3 完成。

## 1. 结论边界

本轮证明了客户自有 Ubuntu 24.04 x86_64 服务器能够使用不可变 amd64 离线介质安全升级，且升级失败不会把半迁移站点放行业务；成功升级后可以继续记账，并能把当前版本加密整站备份恢复到站点 ID、Compose 项目、数据目录、密钥目录和运行状态均独立的空白替换站。

中国大陆临时验收机没有已备案接入域名。经产品负责人确认，本轮不测试公网域名暴露；Web 使用回环 `external-proxy` 完成三层健康、登录和业务验收。该例外不影响数据库迁移、备份恢复和数据不变量结论，但正式客户上线仍必须完成备案域名、TLS 和外部访问验收。

## 2. 不可变发布基线

| 项目 | 固定值 |
| --- | --- |
| Deploy 标签 | `v0.2.0-rc.1` |
| Deploy 标签提交 | `fc6da6d3f869b8499b7b67813b06ad6ba5cde192` |
| Backend 源提交 | `26939c113c5c224ccec013100e91686c87520d87` |
| Backend Actions Run | `29319634754` |
| Web 发布摘要 | `ghcr.io/soyia/copper_web@sha256:098ad42a89de5c3890ab78548c966adcf0b2f4ebe98ef3c569ba3539ab3d153c` |
| Backend 发布摘要 | `ghcr.io/soyia/copper_backend@sha256:3ec8083df0b8dd1ffe12f23e283095b807a41c8524b0ab6cbe38a8f4aadfce04` |
| MySQL 发布摘要 | `mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af` |
| Web 离线内容 ID | `sha256:e744dbdaf09cd4a195c968a4a090cfdf3d73660d96b0675c93d4189007b5094c` |
| Backend 离线内容 ID | `sha256:88709ca892cef9994141d4ad7eb1e8a8265d52d6c357750b4607e804d7d86b09` |
| MySQL 离线内容 ID | `sha256:ef9038553b7ea407704f16770e407ffd32f5566f125d0d94f63ff736af1d43f8` |

发布摘要用于仓库身份，离线内容 ID 用于 `docker save/load` 后的本地运行身份；二者都由 `copper-delivery/v1` 绑定，不能互相替代。

正式 Release 资产均由同一标签提交生成：

| 资产 | SHA-256 |
| --- | --- |
| `copper-deploy-0.2.0-rc.1.tar.gz` | `d4c96c9d38c3ac89abc2a53f7b06f24cc78db847fec31b54aa842e702ed9ecd8` |
| `copper-release-0.2.0-rc.1.env` | `c23cfd9d5a9bba4f77874964c7fd7e757e898d8033e38a5fe68fe960b09b66c2` |
| `copper-upgrade-matrix-0.2.0-rc.1.json` | `78623eccef3c3fcfc3f9cbf397c6e9e508a1d62af747967f9f7a0622e4ff2880` |
| `copper-release-notes-0.2.0-rc.1.md` | `8f6ed0dbfcd02d0330151aafb60ecd74f053bd4becabe24a61f52f77b46c7803` |
| `acme.sh-3.1.3.tar.gz` | `efd12b265252f8875269960b6b31830731ccce2b3e6ff8e7ecfbee21fde35ab4` |
| `SHA256SUMS` | `0c7967c2c335b5a6de37b59ec521fb94c0aa0586b56274d46b5ae8906ac18c1d` |
| 联网交付索引 | `5bcf0640a207fbb2d7377b8487764a6bfaf5e2e401b755735d1b52c7a38052b1` |
| 离线交付索引 | `db1c85056d67f5c951d9309efa0311b1c622b542f648ee65746ab578c951f3c0` |
| amd64 离线镜像包 | `c1cc5de10f1af836f6fdbf8526a077276ad7bb12572d8b6f060af9344602bd8e` |

标签前候选包含 `CANDIDATE-NOT-A-RELEASE.json`。最终验收通过后才创建标签；正式构建的 Deploy 包、发布清单、矩阵、发布说明、ACME 归档和 `SHA256SUMS` 与候选逐文件一致。GitHub Release 为预发布，不覆盖或移动标签。

## 3. 主机与离线交付

| 项目 | 实测值 |
| --- | --- |
| 验收机 | AWS 中国区临时 x86_64 主机，全部业务数据均为可销毁测试数据 |
| 操作系统 | Ubuntu 24.04，`x86_64` |
| Docker | Engine 29.6.1，Compose 5.3.1 |
| 根盘 | 29 GiB；验收运行时使用 7.0 GiB、可用 22 GiB |
| 交付方式 | 在 `local-database` 准备并验证正式 amd64 离线介质，中国主机只导入，不执行 `docker pull` |
| 网络边界 | 来源站只映射 `127.0.0.1:18081`，替换站只映射 `127.0.0.1:18082`；宿主机 8191 始终未使用 |

开发电脑没有运行 Docker、Colima 或 Docker Desktop；`local-database` 的共享 `vm-mysql`、`vm-valkey` 未被读写、停止或替换。

## 4. 来源站与真实故障恢复

来源站 `m305-source` 使用 Compose 项目 `copper-m305-source`，验收入口确认产品版本 `0.1.0-m2-rc.2`、schema 3、三层健康、经营汇总、附件、外键和 Flyway V1/V2/V3 基线。

故障演练 `m305-failure-audit-v6` 在正式离线介质和真实隔离恢复副本上执行 Flyway V4，并于目标升级审计短暂进入 `SWITCHED` 后、Web 放行前注入 `after-switch-audit` 中断：

- 演练从 `2026-07-14T11:52:21Z` 到 `11:54:15Z`，约 114 秒；
- 目标旁路恢复为 `MIGRATION_VALIDATED`，`lastError` 为 `switch failed; original verified`；
- 原来源站自动恢复 `0.1.0-m2-rc.2 / schema 3` 三层健康；
- 来源经营汇总、附件、表数量和 V1/V2/V3 Flyway 前缀与故障前完全一致；
- 半迁移目标没有成为当前站，Web 未在未知状态下放行。

这证明恢复门槛依赖可验证状态和完整来源站，而不是简单换回旧 Backend 镜像或执行逆向 SQL。

## 5. 正常升级与继续业务

最终升级 `m305-final-upgrade-v6` 使用新的升级前加密整站备份：

- backup ID：`beb6bdce02238bb08f9f64559b0ce9d5`；
- 备份归档 SHA-256：`fe98c607081078020f504083cadebb9d70c3f30cca718f03548ae7d54f879194`；
- 从开始隔离恢复到人工切换完成约 117 秒；
- 切换结果：`SWITCHED`，产品版本 `0.2.0-rc.1`，schema 4。

升级后 Flyway 只有成功的 V1/V2/V3/V4，前三个迁移的历史前缀保持不变，只追加 `V4__system_upgrade_audit.sql`。`system_upgrade_audit` 唯一记录来源/目标版本与 schema、升级前备份 ID、发布清单摘要和切换时间。

随后使用真实登录和业务 API 新增两笔客户预收，业务记录 ID 为 2、3，金额分别为 1.11、1.12；账户审计链从 0 变化到 1.11，再到 2.23。登录 HTTP 200，资金余额、资金流水和审计链一致，证明升级结果可继续写业务，不是只读静态副本。

## 6. 当前版本备份与独立空白恢复

继续业务后创建并验证新的 `copper-backup/v1`：

- backup ID：`d5ed24246d916a2a2f97bb25673c3c74`；
- 归档 SHA-256：`a67665cd7ccae37e11af37e7d93d24e4112382e82896926a27205036027c1462`。

该备份恢复到逻辑独立的 `m305-replacement`：Compose 项目、配置目录、数据目录、备份目录、恢复目录、密钥和回环端口均不复用来源站。恢复 `m305-replacement-final-v6` 从创建到切换约 62 秒，最终状态为 `SWITCHED`。

跨站点恢复保持两类身份分离：目标 `siteId=m305-replacement`，备份来源 `sourceSiteId=m305-source`，来源指纹 `040852fb5eeea6b2`。envelope 指纹仍严格绑定备份来源 manifest，不通过冒充来源站点绕过校验。

升级站与空白恢复站逐项一致：

| 不变量 | 双方证据 SHA-256 |
| --- | --- |
| 经营汇总 | `e4c9c45b4d7c802d43ec7e41d68bce1d3896f018b6f069f628c2388d22819de5` |
| 附件清单 | `06bfbaafb38d0084f5a42c3811a5f2d579b36081b4764fa814c0c54bd3871d2a` |
| Flyway 历史 | `ce9cefa73c60defbe2981ae64b427cbf35b59965886ae8c3c46989baf4158f8b` |
| 升级审计 | `2a10fe37fe5031cdae1d03f7572d0cdd5218c6ef4e2a526ca58366295d860d19` |
| 表数量 | `4393447bd3c1d55ea7f97417ecb1b36a691ccaacaaf2ebd21c59a5acf825fb7b` |

## 7. 实测发现与修复

真实链路没有绕过失败，而是按门槛停止并修复后从新的候选重新验收：

1. `PREPARED` 漏绑定 root-only READY，修复见 Deploy PR #28；
2. ARM 构建机多架构缓存可能选错平台，修复见 Deploy PR #27；
3. 正式目录故障演练的双层路径校验不一致，修复见 Deploy PR #30；
4. 灾备恢复错误要求来源与目标使用同一站点 ID，修复见 Deploy PR #31；
5. 跨站点来源字段未进入状态白名单，修复见 Deploy PR #32。

最终非容器合同全部通过；恢复元数据回归测试 11/11 通过。所有失败均发生在当前站切换前或由完整来源站自动恢复，没有覆盖正式来源数据。

## 8. 发布、证据与清理

Backend PR #13 和 Deploy PR #26–#32 已合入各自 `main`。正式标签与 Release 只在最后一轮候选通过后创建。

验收机证据归档在清理前下载到受控操作端：

- 文件：`copper-m3-05-x86-evidence.tar.gz`；
- SHA-256：`c981054e82b6b47e5003d36bf9aa38cb3b5c61bb9558100f6273388ae266ebe1`；
- 来源、故障恢复后、升级后继续业务、空白恢复和最终状态目录的内部 `SHA256SUMS` 全部通过。

证据归档不作为公开 Release 资产，避免把运行日志和测试业务内容对外发布；正式 Release 只包含客户交付所需的不可变发布物。

两套站点均使用产品 `permanently-delete-data.sh`，通过精确站点 ID 和人工二次确认永久删除。随后删除验收介质、发布目录、临时凭据、备份、证据副本和四个 Copper/MySQL 测试镜像。最终复核结果：

- 0 容器、0 镜像、0 卷；
- 0 Copper 网络、0 Copper 相关监听；
- 0 Copper 数据、配置、备份、恢复、发布和验收目录；
- 根盘使用量降为 2.8 GiB，可用 26 GiB。

## 9. 完成判定与保留事项

- [x] 正式 `linux/amd64` 镜像、发布清单、Deploy 包和交付索引相互绑定。
- [x] 来源 schema 3 使用新生成且验证通过的加密整站备份。
- [x] 隔离副本真实执行追加式 V4，V1/V2/V3 不变。
- [x] `after-switch-audit` 故障恢复不放行半迁移 Web，原站自动恢复。
- [x] 正常升级完成可信终端人工确认和 `SWITCHED` 审计。
- [x] 升级后可登录、查询并继续真实资金业务。
- [x] 当前版本新备份恢复到独立空白站，来源身份和目标身份可审计。
- [x] 经营汇总、附件、Flyway、升级审计和表数量一致。
- [x] 正式候选与标签后构建字节一致，Tag/Release 不可变。
- [x] 测试数据、容器、镜像、网络、卷和监听已清零。
- [ ] 已备案域名的公网 TLS 与客户浏览器访问；按产品负责人确认留待备案后补验，不阻断 M3 数据升级与恢复结论。

`v0.2.0-rc.1` 仍是试点 RC，不是稳定版，不允许无人值守强制升级。后续普通版本按 [发布验证级别](RELEASE_VALIDATION_LEVELS.md) 选择门槛，不需要重复本次首个真实 schema 变化的全部发现过程，但涉及 schema、备份格式、恢复逻辑、镜像平台或升级状态机变化时，仍必须执行相应 L3/L4 验收。
