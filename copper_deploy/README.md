# Copper Deploy

本轮继续收口进入 `0.4.0-rc.8`：构建、当前安全包、离线介质与整站安装恢复的执行入口见 [收口记录](docs/RC8_RELEASE_CLOSURE.md)。当前状态为 `NOT_RUN_WAITING_X86_HOST`；前端构建上下文、可选 OCR 与恢复隔离已修复，官方离线依赖已校验，不能据此宣称 Linux 镜像或整站恢复已通过。第三方及浏览器/手机延期沿用负责人已有决定。

本轮发布修复与源码冻结范围见 [2026-10-16 范围决定](docs/RELEASE_SCOPE_20261016.md)，构建来源见 [本地构建证据](docs/LOCAL_BUILD_PROVENANCE.md)，支持与生命周期见 [SUPPORT.md](SUPPORT.md)。历史里程碑报告保留当时结论。

`0.4.0-rc.7` 已完成本轮开发交付：见 [发布说明](releases/0.4.0-rc.7/RELEASE_NOTES.md) 与 [精确源码/校验值冻结记录](releases/0.4.0-rc.7/SOURCE_FREEZE.json)。该记录绑定已验收代码提交；后续冻结说明提交只补元数据，不改变绑定的运行代码。

M2 数据自主运维文档：

- [一致性整站备份](docs/M2_BACKUP.md)
- [旁路恢复与回滚](docs/M2_RESTORE.md)
- [本地、外接盘、NAS、S3 存储目标与容量治理](docs/M2_STORAGE_TARGETS.md)
- [客户持钥、任务状态与告警](docs/M2_KEY_LIFECYCLE.md)
- [客户恢复密钥交接与销毁清单](docs/M2_KEY_HANDOVER.md)

M3 发布治理文档：

- [M3 产品版本、发布节奏与兼容性决策](docs/M3_VERSIONING_AND_COMPATIBILITY.md)
- [M3 升级预检与备份准备](docs/M3_UPGRADE_PREPARATION.md)
- [M3-02 升级预检与备份准备验收](docs/M3_UPGRADE_PREPARATION_ACCEPTANCE.md)
- [M3 隔离迁移、失败保护与人工切换](docs/M3_UPGRADE_STATE_MACHINE.md)
- [M3-03 隔离迁移与失败保护验收](docs/M3_UPGRADE_STATE_MACHINE_ACCEPTANCE.md)
- [M3 联网/离线不可变发布物与升级介质](docs/M3_RELEASE_DELIVERY.md)
- [M3-04 联网/离线发布物验收](docs/M3_RELEASE_DELIVERY_ACCEPTANCE.md)
- [M3-05 正式 x86_64 升级与空白恢复流程](docs/M3_FINAL_X86_ACCEPTANCE.md)
- [M3-05 正式 x86_64 最终验收报告](docs/M3_FINAL_X86_ACCEPTANCE_REPORT.md)

M4 安全治理文档：

- [M4-03 生产秘密、日志脱敏、SBOM 与漏洞治理](docs/M4_SECURITY_SUPPLY_CHAIN.md)
- [M4-04 本地健康、容量、备份、证书与时间监控](docs/M4_MONITORING.md)
- [M4-04 x86_64 实机验收报告](docs/M4_MONITORING_ACCEPTANCE_REPORT.md)
- [M4-06 数据库、磁盘、备份、证书、时间与异常断电验收报告](docs/M4_FAULT_RECOVERY_ACCEPTANCE_REPORT.md)
- [M4-07 完全断网与空白 x86_64 最终验收规程](docs/M4_FINAL_X86_ACCEPTANCE.md)
- [私密安全漏洞报告入口](SECURITY.md)

M5 本地设备与多架构文档：

- [M5 多架构镜像发布](docs/M5_MULTIARCH_IMAGE_RELEASE.md)
- [M5 本地主机安装、启动与数据生命周期](docs/M5_LOCAL_HOST_INSTALLATION.md)
- [M5-03 Ubuntu 本地主机生命周期验收报告](docs/M5_LOCAL_HOST_ACCEPTANCE_REPORT.md)
- [M5-04 局域网 HTTPS、名称解析与防火墙](docs/M5_LAN_ACCESS.md)
- [M5-04 Ubuntu x86_64 局域网安全验收报告](docs/M5_LAN_ACCEPTANCE_REPORT.md)
- [M5 可选互联网远程访问：Cloudflare Tunnel](docs/M5_CLOUDFLARE_REMOTE_ACCESS.md)
- [M5-05 外接盘、NAS 备份恢复与换机迁移](docs/M5_STORAGE_BACKUP_MIGRATION.md)
- [M5-05 Ubuntu x86_64 工程验收报告](docs/M5_STORAGE_MIGRATION_ACCEPTANCE_REPORT.md)

M6 客户源码交付与公开开源准备文档：

- [M6-03 第三方权利、许可证清单与 SBOM 合同](docs/M6_THIRD_PARTY_RIGHTS_SBOM.md)
- [M6-03 第三方权利与 SBOM 工程验收报告](docs/M6_THIRD_PARTY_RIGHTS_ACCEPTANCE_REPORT.md)
- [M6-04 内部源码交付演练](docs/M6_SOURCE_REHEARSAL.md)

M7 自助部署与内部试点文档：

- [Copper 一站式安装器](docs/M7_GUIDED_INSTALLER.md)
- [M7-02 零在线支持安装演练手册](docs/M7_02_ZERO_LIVE_SUPPORT_OPERATOR_GUIDE.md)
- [M7-02 首份样本与工程收口证据](docs/M7_02_SELF_SERVICE_EVIDENCE.md)
- [M7-06 多环境试点与首发支持范围决策](docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md)

Copper 客户自有服务器正式交付和本地部署候选资产仓库。

本仓库统一管理 Docker Compose、生产配置模板、发布清单、安装与运维工具。前端和后端应用源码及镜像定义分别归属 `soyia/copper_web` 与 `soyia/copper_backend`。

当前里程碑：M2、M3、M4 已完成；M5 本地实体范围经 M7-06 决定移出 `1.0.0` 首发，保持后续版本 `Deferred/Candidate`；M6 内部演练通过但正式门槛仍打开；M7-02 至 M7-06 已形成内部证据和范围决定。M6-03 已建立第三方权利、许可证清单与 SBOM 的失败关闭工程合同；宏泰商品资料快照和抓取脚本已从 Web 当前树移除，旧 Git 历史由 M6-02 评估。M6-04 可生成 `INTERNAL_REHEARSAL_ONLY` 源码演练包，以验证内部构建和空白环境链路；真实 M6 六角色 SBOM、逐组件权利台账、完整许可证文本、M6-02 平台历史处置和 M6-05 法律复核完成前，不得生成客户源码交付或公开发布成功结论。

M7-06 首发拟支持范围只包含客户自有公有云上的 Ubuntu Server 24.04 LTS x86_64。Ubuntu 本地常开主机、Windows 宿主 Ubuntu VM、Synology/NAS 主机、ARM64、macOS、扫码枪和针式打印均不在 `1.0.0` 标准兼容清单；相关工程资产保留供未来真实设备复验，不能据此对外承诺。

实施说明见 [docs/M1_COMPOSE.md](docs/M1_COMPOSE.md)。首次运行从 `config/copper.env.example` 创建客户服务器上的 `/etc/copper/copper.env`，不要在仓库内建立真实 `.env` 或密钥文件。

日常版本不重复执行整套首发验收；按风险选择检查项的规则见 [docs/RELEASE_VALIDATION_LEVELS.md](docs/RELEASE_VALIDATION_LEVELS.md)。

M7 内部演练从 Mac 安装时运行 `./copper-install-from-mac`，介质已在 Ubuntu 上时运行 `sudo ./copper-install`；安装完成后的服务器菜单为 `sudo copper-maintain`，Mac 日常维护入口为介质中的 `./copper-manage-from-mac`，数据保护、备份恢复、升级、退场和 Cloudflare 可选远程访问均优先通过菜单选择。`sudo copperctl` 只作为自动化和专业诊断底层接口，不要求普通用户复制子命令。安装与交接见 [docs/INSTALL.md](docs/INSTALL.md)，M5 本地主机生命周期见 [docs/M5_LOCAL_HOST_INSTALLATION.md](docs/M5_LOCAL_HOST_INSTALLATION.md)，M2 备份设计与操作见 [docs/M2_BACKUP.md](docs/M2_BACKUP.md)，旁路恢复与回滚见 [docs/M2_RESTORE.md](docs/M2_RESTORE.md)，M4 本地监控见 [docs/M4_MONITORING.md](docs/M4_MONITORING.md)，故障处理见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

M2-02 的 MySQL 8.4、加密包、失败恢复和保留策略实测见 [docs/M2_BACKUP_ACCEPTANCE.md](docs/M2_BACKUP_ACCEPTANCE.md)。
M2-03 的旁路恢复、失败保护、切换与回滚实测见 [docs/M2_RESTORE_ACCEPTANCE.md](docs/M2_RESTORE_ACCEPTANCE.md)。
M2-06 的密钥轮换、连续失败和告警实测见 [docs/M2_KEY_ALERT_ACCEPTANCE.md](docs/M2_KEY_ALERT_ACCEPTANCE.md)。
M3-02 的发布契约、失败保护和升级专属备份绑定实测见 [docs/M3_UPGRADE_PREPARATION_ACCEPTANCE.md](docs/M3_UPGRADE_PREPARATION_ACCEPTANCE.md)。

M3-03 的迁移失败、写脏拒绝、切换故障恢复、正常升级与回滚实测见 [docs/M3_UPGRADE_STATE_MACHINE_ACCEPTANCE.md](docs/M3_UPGRADE_STATE_MACHINE_ACCEPTANCE.md)。

M3-04 的联网回环、完全离线导入、摘要/镜像失败保护、幂等和主机隔离实测见 [docs/M3_RELEASE_DELIVERY_ACCEPTANCE.md](docs/M3_RELEASE_DELIVERY_ACCEPTANCE.md)。

M3-05 的正式 amd64 介质、真实 Flyway V4、切换故障恢复、继续记账、跨站点空白恢复、Tag/Release 和清理结果见 [docs/M3_FINAL_X86_ACCEPTANCE_REPORT.md](docs/M3_FINAL_X86_ACCEPTANCE_REPORT.md)。

M1-06 在 `local-database` 的隔离验证证据见 [docs/M1_OPERATIONS_ACCEPTANCE.md](docs/M1_OPERATIONS_ACCEPTANCE.md)。
M1-07 的候选发布物、空机检查和最终证据清单见 [docs/M1_END_TO_END_ACCEPTANCE.md](docs/M1_END_TO_END_ACCEPTANCE.md)。
