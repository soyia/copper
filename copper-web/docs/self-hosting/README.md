# Copper 私有化部署规则与架构文档

> 状态：M0 至 M4 已完成，M5 本地范围经 M7-06 移出 `1.0` 首发且无本地 Certified，M6 内部演练通过但正式退出受阻；M7-07 内部总审计已完成，产品负责人已签署 `NO_GO`；公开开源、正式收费、跨境和高风险业务继续失败关闭
> 建立日期：2026-07-13
> 对应里程碑：M0-M7
> 权威路线图：`docs/SELF_HOSTING_AND_DATA_CONTROL_ROADMAP.md`

本目录用于固化 Copper 私有化部署路线中的产品承诺、经营主体隔离、责任边界、价格服务、生产架构、备份恢复和源码开放策略。路线图和每个里程碑的真实验收结果共同决定哪些能力可以对外承诺，单份设计文档不代表实现已经完成。

## 文档清单

- [`../OWNER_OPERATIONS_AND_FINANCE_SOP.md`](../OWNER_OPERATIONS_AND_FINANCE_SOP.md)：老板和一线人员可直接执行的日常开单、错单冲正、发票勾稽、银行对账、月底关账、反结账及备份恢复 SOP。
- `TENANCY_AND_SITE_POLICY.md`：经营主体、站点、账套和数据隔离规则。
- `DEPLOYMENT_RESPONSIBILITY_MATRIX.md`：客户、Copper 与第三方责任矩阵。
- `PRICING_AND_SERVICE_CATALOG.md`：单一产品能力下的建议价格和服务边界。
- `SOURCE_DELIVERY_AND_OPEN_SOURCE_POLICY.md`：客户源码交付与公开开源策略。
- `CHINA_TRIAL_TERMS.md`：可用于中国境内低风险试运营的订单、税务、数据和源码授权基线。
- `M0_CONSISTENCY_REVIEW.md`：M0 一致性检查、退出结论和 M6/M7 后续门槛。
- `M1_PRODUCTION_BASELINE.md`：M1 标准服务器支持矩阵、正式拓扑、部署资产归属、数据库 V1 和里程碑边界。
- `M2_BACKUP_EXPORT_ARCHITECTURE.md`：M2 备份包、数据一致性、旁路恢复、人可读导出、客户持钥、存储目标和三仓责任边界。
- `M4_SECURITY_MONITORING_DR_ARCHITECTURE.md`：M4 身份、安全、审计、监控、容灾、故障不变量和完全离线运行合同。
- `M5_LOCAL_DEVICE_LAN_NAS_ARCHITECTURE.md`：M5 本地设备支持分级、候选平台、LAN/TLS、存储、外设、休眠、真实设备晋级和服务价格边界。
- `M5_06_LAN_EXPERIENCE_ACCEPTANCE.md`：M5-06 局域网多人、打印、扫码、上传、短断网工程验收证据，以及仍需实体设备完成的退出门槛。
- `ACCEPTANCE_TOOL_LIFECYCLE_POLICY.md`：M1-M7 开发与验收工具的运行隔离、进程拥有、超时/中断收尾、宿主前后基线和零残留退出规范。
- `M6_SOURCE_DELIVERY_OPEN_SOURCE_ARCHITECTURE.md`：M6 完整核心范围、精确版本源码包、客户自主权、审计/许可证门槛、GitHub 权威源和退出顺序。
- `M6_02_SECURITY_PRIVACY_AUDIT.md`：三仓当前树/完整历史脱敏审计、PR 新增泄漏门禁、严格 Release 门禁、首次阻塞摘要和待处置项。
- `M7_INTERNAL_PILOT_AND_RELEASE_ARCHITECTURE.md`：M7 内部演练、真实试点、价格成本、多环境样本和 `1.0.0` Go/No-Go 规则。
- `M7_03_FIRST_DAY_BUSINESS_ACCEPTANCE_REPORT.md`：M7-03 首日经营闭环、新镜像切换、真实浏览器/数据库证据、问题复验矩阵和剩余定向门槛。
- `M7_05_PRICE_SERVICE_COST_RECALCULATION.md`：M7-02 至 M7-04 真实样本驱动的目标价、服务额度、税费、风险和正式发布阻断复算。
- [`copper_deploy` M7-06 多环境首发范围决定](https://github.com/soyia/copper_deploy/blob/main/docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md)：客户云单一首发环境、M5 延期项处置、兼容清单和价格/责任边界。
- `M7_07_FINAL_GO_NO_GO_AUDIT.md`：从三个最新 `origin/main` 重新冻结的 M0-M7 总审计、`NO_GO` 建议、正式阻断清单和产品负责人决定栏。
- `evidence/M7_07_FINAL_AUDIT_SNAPSHOT.json`：M7-07 三仓提交、发布清单、安全扫描和决定状态的无秘密机器可读摘要。
- `evidence/Copper_M7-05_价格服务成本复算_内部演练.xlsx`：可调参数、公式、敏感性、来源和7项自动检查的内部成本工作簿。
- `evidence/Copper_M7-05_首次收费与交付单据_内部演练.docx`：用虚构主体和虚构订单填写现有11页首次收费/交付模板的内部演练件。
- `templates/Copper_中国境内首次收费与交付单据模板.docx`：首次收费可直接填写、打印和签署的订单、验收、账号密钥交接、数据授权及权限撤销单据包。
- `../../scripts/docs/build_m0_service_forms.py`：上述 Word 单据包的可重复生成脚本。

## 当前已经生效的内部决策

1. Copper 只有一套完整业务能力，不用功能版本、用户数或人工存储配额制造收费层级。
2. 默认一个独立经营主体对应一个 Copper 站点和一个独立数据库。
3. 同一经营主体的部门、仓库和直营网点在同一站点内管理；独立经营主体不得混用数据库。
4. 多站点不收软件功能费，但标准部署、维护和代运维按生产环境核算实际服务成本。
5. 客户始终持有服务器/云账号、数据库管理员凭据、备份文件和备份加密密钥。
6. 服务停止不锁定现有系统，不扣留客户数据。
7. 在正式公开开源前，中国境内低风险试运营客户源码交付按 `CHINA_TRIAL_TERMS.md` 执行；AGPL公开、外部贡献、跨境和高风险授权在 M6/M7 专项复核。
8. M5 本地平台只有完成精确真实设备全生命周期验收后才能标为正式支持；M7-06 已将全部本地/LAN/NAS 目标移出 `1.0` 首发，当前没有本地 Certified，也没有可售的本地/NAS标准部署。

## 尚未对外生效

- 本目录价格已于 2026-07-13 由产品负责人确认并冻结为当前目标定价；首次收费和开票前必须按 `CHINA_TRIAL_TERMS.md` 核对实际服务主体和纳税人身份。
- `SOURCE_DELIVERY_AND_OPEN_SOURCE_POLICY.md` 是长期开放源码策略；试运营客户订单和源码使用以 `CHINA_TRIAL_TERMS.md` 为当前基线。
- M2/M3/M4 的备份恢复、离线升级、安全和离线自主能力已经完成既定验收；M5 完成前不得宣传“支持所有电脑/NAS”，M6 完成前不得宣传“已经开源”。
