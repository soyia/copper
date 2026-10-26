# Copper M4-03 生产秘密与供应链安全验收报告

> 验收日期：2026-07-15
>
> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/36>
>
> 验收候选：`0.3.0-rc.2`，状态 `NOT_RELEASED`
>
> 结论：生产秘密、日志脱敏、SBOM、漏洞治理、数据库秘密轮换、Schema 4 到 5 升级和最终空白安装门槛全部通过，M4-03 可以关闭并进入 M4-04。

## 1. 结论边界

本轮证明客户自有 Ubuntu 24.04 x86_64 服务器可以使用固定源码提交和 amd64 镜像，在不把客户秘密交给 Copper 的前提下完成空白安装、首次管理员交付与销毁、数据库口令轮换、日志脱敏、整站备份、隔离升级、回滚、SBOM 和漏洞扫描。

本报告不创建正式 Tag 或 GitHub Release。候选保留 `CANDIDATE-NOT-A-RELEASE.json`，三条 MySQL 官方镜像 Critical 发现只取得最长七天的 RC 例外，稳定版仍需使用官方修复镜像或重新完成限时复核。公网域名、证书和完全断网经营属于 M4 后续子项，不由本报告提前放行。

## 2. 主机与隔离边界

| 项目 | 实测值 |
| --- | --- |
| 验收机 | AWS Singapore 临时 Ubuntu 24.04 x86_64，业务数据均为可销毁测试数据 |
| CPU / 内存 | 2 vCPU / 7.63 GiB |
| Docker | Engine 29.1.3，Compose 5.1.4 |
| 验收前可用磁盘 | 38 GiB |
| 来源站 / 旁路站 | 仅映射 `127.0.0.1:18094` / `127.0.0.1:18095` |
| 最终空白站 | 仅映射 `127.0.0.1:18096` |
| 正式端口 | 宿主机 8191 未使用、未停止、未替换 |

开发者 Mac 没有运行 Docker、Colima 或 Docker Desktop。全部容器、镜像、Flyway、备份、轮换和故障恢复测试只在授权 x86_64 验收机执行；Git 操作只使用 GitHub，不使用 Gitee。

## 3. 不可变候选基线

| 角色 | 源码 / 工作流 | 发布身份 | 验收机内容 ID |
| --- | --- | --- | --- |
| Web | `425acf48f9679280199b657436f8cd74e67f9809` / `29356556313` | `ghcr.io/soyia/copper_web@sha256:4c7097fe8b75d64e696af8930186d9d9d01b7fe93f20825fffa29481593154c8` | `sha256:cdb833377a89e1a9efe30876024ee2a7a551b650b9eb97207ae1efaf36923e70` |
| Backend | `3e15d4200613facdb01fbcda6cd540898d79e47b` / `29356554556` | `ghcr.io/soyia/copper_backend@sha256:68115e2c0c01277fbc23d19795c1ad75e3a2b5e6dff09f9c9aaa8ac92d3a4e5f` | `sha256:dc873fdce6f4f6aa294b93690be6255dc5a9106b4666bf5982208a5dc542acb8` |
| MySQL | 官方 8.4.10 | `mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af` | `sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af` |
| Deploy 运行验收 | `f8745cd41b5d5a0bb592999e3bd4a2864288dd70` | 候选 Deploy 归档 | 同一提交生成 |

GitHub Actions 原生离线镜像工件的 `SHA256SUMS`、源码 revision、工作流编号、发布摘要、amd64 架构和本地内容 ID 全部重新校验。候选发布清单 SHA-256 为 `a520a4ba9345e14ae3d22ed5ae52d5bd28de64bebdd60799d202223528a73b26`，安全证据包 SHA-256 为 `4a750947da9afa5b2a406c4f98ddda43618b85e3d32d217e0e6f7220d54411c4`。

验收报告合入 `main` 后只增加文档，不改变 Web、Backend、MySQL、迁移或运行脚本。最终对外交付仍必须从合入后的精确 `main` 重新生成候选并记录新的 Deploy 提交和证据摘要，禁止复用本节的报告前候选冒充最终发布物。

## 4. 秘密、日志和首次管理员

- MySQL 业务和 root 口令只保存在 root-only 文件；Compose 只传 `_FILE` 路径。
- 官方 MySQL 入口脚本读取口令后，由失败关闭包装层在唯一最终 `exec` 前清除 `MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD`；真实进程环境审计通过。
- 首次管理员一次性凭据完成初始登录、强制改密、旧口令 HTTP 401、新口令登录、临时会话撤销和凭据文件销毁。
- `security_audit_log` 唯一记录 `BOOTSTRAP_ADMIN`、`CHANGE_PASSWORD` 和 `BOOTSTRAP_CREDENTIAL_DESTROYED` 成功事件。
- Caddy、Backend 和 `copperctl logs` 的三层脱敏合同覆盖口令、Token、Cookie、联系方式、证件号和银行账号；普通日志禁止记录客户业务正文。
- 最终候选空白站在首次凭据销毁及测试恢复私钥销毁后，两次 `security secrets audit` 均通过。

数据库秘密真实轮换成功路径验证了新 root/业务口令、0400 文件权限、三层健康、数据库 `SECRET_ROTATED` 审计和两条生命周期 `ROTATED` 事件。故障路径在第一次换密后的 Compose 重建处注入失败，旧数据库账户、旧秘密文件和三层服务全部自动恢复，且没有伪造成功审计。轮换审计显式执行 `USE copper_v2;`，避免无默认数据库时写审计失败。

## 5. SBOM、秘密扫描和漏洞治理

最终安全证据 `copper-security/v1` 独立复核结果：

| 门槛 | 结果 |
| --- | --- |
| Web / Backend / Deploy Git 历史及发布归档秘密扫描 | 4 份报告，0 发现 |
| Web / Backend / Deploy 源码 SBOM | 3 份 CycloneDX 1.6 |
| Web / Backend / MySQL amd64 镜像 SBOM | 3 份 CycloneDX 1.6 |
| Grype 漏洞报告 | 6 份，可重复验证 |
| 漏洞发现合计 | 361：Critical 3、High 63、Medium 255、Low 39、Unknown 1 |
| 未处置发布阻断项 | 0 |
| 安全证据包状态 | `PASS` |

三条 Critical 均来自固定 MySQL 8.4.10 镜像内的 gosu Go 标准库：`CVE-2025-68121`、`CVE-2026-27143`、`GO-2026-4337`。例外证据精确绑定角色、包、安装版本、责任人、补偿控制和 `govulncheck` 原始分析，统一于 `2026-07-21T12:00:00Z` 到期；到期后自动重新阻断。

公开安全报告入口固定为 GitHub Private Security Advisory。公开 Issue 只允许请求安全联系人，禁止粘贴秘密、利用细节、客户数据或运行日志。

## 6. Schema 4 到 5 与恢复门槛

来源站以 `0.2.0-rc.1 / schema 4` 空白安装并完成首次管理员销毁和恢复密钥实证。目标 `0.3.0-rc.2 / schema 5` 的资源预检在旧 3.74 GiB 主机上正确失败；主机扩容到 7.63 GiB 后才允许继续，证明最低 4 GiB 门槛不是文档提示。

升级专属一致性备份：

- backup ID：`50099eadb66109f1fe24c13373b6beb8`；
- 归档 SHA-256：`a0bfeb5c668769b35c84a40b11c27aa19ec33a1f4fceea4a374300333385c67c`；
- 外层、envelope、加密载荷和离线私钥恢复验证全部通过。

隔离旁路达到 `MIGRATION_VALIDATED` 后复核：来源仍为 schema 4，目标为 schema 5，V1-V4 Flyway 行和 checksum 逐行相同，只追加成功 V5；不存在失败迁移和 `c_` 前缀表；V5 的 15 个会话、密码与审计字段全部存在；三层健康。

可信终端切换后，当前站为 `0.3.0-rc.2 / schema 5`，升级审计唯一记录 `SWITCHED`，秘密审计和 HTTP 健康通过。随后对称回滚恢复 `0.2.0-rc.1 / schema 4`，三层健康且目标 V5 不再是当前数据。旁路 `discard` 后释放 PREPARED，测试恢复私钥销毁，密钥扫描再次通过。

真实回滚还发现测试目标使用三个本地不可变镜像 ID 时，运行配置仍错误标记为 `registry`。PR #47 将全本地 ID 自动标记为 `offline`，禁止与 registry 引用混用，并补充回归测试；这不放宽任何生产镜像身份校验。

## 7. 最终候选空白安装

从安全候选中的 `copper-deploy-0.3.0-rc.2.tar.gz` 独立解包，在全新目录和 Compose 项目完成空白安装：

- MySQL、Backend、Web 均为 `running/healthy`，HTTP 健康通过；
- Flyway 最新版本为 5，失败迁移为 0，`c_` 前缀表为 0；
- V5 15 个字段全部存在；
- 首次管理员交付、改密、旧口令拒绝、新口令登录和凭据销毁通过；
- MySQL 入口清密、数据库轮换审计数据库选择和升级离线镜像修复均实际包含在候选归档；
- 最终秘密审计通过，测试私钥已销毁。

本轮非容器回归包括静态契约和 64 项 Python 测试，全部通过。真实容器门槛只在授权 x86_64 主机执行。

## 8. 实测发现与合入记录

真实验收按失败关闭处理问题，没有绕过后继续：

1. PR #44：发布契约固定 schema 5；
2. PR #45：清除 MySQL 官方入口遗留的长期进程口令环境；
3. PR #46：数据库轮换审计显式选择 `copper_v2`，并保留失败阶段；
4. PR #47：测试升级使用本地内容 ID 时生成可持续切换、状态检查和回滚的 `offline` 配置。

上述 PR 均已 squash 合入 GitHub `main`。没有修改已执行的 V1-V5，也没有建立 Gitee 同步、许可证心跳、强制遥测或 Copper 托管密钥依赖。

## 9. 完成判定与后续门槛

- [x] 密码、数据库凭据、证书私钥、备份私钥、会话和第三方密钥不进入镜像、仓库、argv 或普通日志。
- [x] 秘密生成、权限、交付、轮换、撤销、销毁和泄露处置形成可执行流程。
- [x] 普通日志完成统一脱敏并禁止客户业务正文进入诊断日志。
- [x] 六份可追溯 SBOM 与具体产品候选绑定。
- [x] 六份漏洞报告、严重度、责任人、修复时限、例外证据和发布阻断规则可重复验证。
- [x] GitHub Private Security Advisory 作为公开安全报告入口。
- [x] 真实发布物、秘密、轮换故障、Schema 4 到 5、回滚和空白站全部通过。
- [x] 实现和回归测试通过 PR 评审合入 GitHub `main`。
- [ ] `0.3.0-rc.2` 正式 Tag / Release：M4-03 不单独发布，待 M4 总体验收决定。
- [ ] 三条 MySQL Critical 限时例外：到期前必须换官方修复镜像或重新复核，不允许静默续期。

M4-03 的“机制可用和证据闭环”已完成。下一项是 M4-04 应用、数据库、磁盘、备份、证书与系统时间监控；M4 总里程碑仍需完成 M4-04 至 M4-07，不能因本报告通过而提前宣称 M4 完成。
