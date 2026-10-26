# 当前内部 ERP 基线完成矩阵

> 状态：`COMPLETED_CURRENT_INTERNAL_ERP_BASELINE`
>
> 基线日期：2026-07-24
>
> 适用范围：当前 Web、Backend、Deploy `main` 的内部 ERP 产品与恢复合同

## 1. 完成结论

下列 12 项建设验收和 2 个发布/恢复收口，已按当前代码、真实 MySQL、真实浏览器/岗位、GitHub Actions 与隔离整站恢复证据标记完成。这里的“完成”是指当前内部 ERP 基线已有可复核实现和验收结果，不是仅有表结构、接口、字符串合同、操作说明或待执行脚本。

冻结功能与验收证据基线：

| 仓库 | 已进入 `main` 的冻结提交 |
|---|---|
| `copper-web` | `bbf49917d8f48240bc027566c0f04f03204770f2` |
| `copper_backend` | `b44c12020420c9de7e474e2475f897c9fa579faf` |
| `copper_deploy` | `4774fb05b8b0a7f521795fb43811568cbae00bdc` |

当前采用产品负责人个人开发默认交付规则：不使用 GitHub Actions，Deploy 不创建或等待 Pull Request；只有产品负责人之后明确指定时才恢复对应门槛。2026-07-24 的 ERP-18 Deploy 收口在完成本地静态合同及真实 ARM64/MySQL 8.4.10 整站恢复验收后，将单一可快进提交直接推入 `main`。GitHub 因同一提交已经进入 `main`，自动把原草稿 [Deploy PR #140](https://github.com/soyia/copper_deploy/pull/140) 标记为 merged；该 PR 和未启动的 Actions 均不作为本次检查通过证据。个人开发规则不扩展为正式发布或客户交付证明；正式动作的额外门槛仍须由产品负责人明确指定。

## 2. 12 项建设验收

| # | 建设项 | 状态 | 主要证据 |
|---:|---|---|---|
| 1 | 老板可读的日常开单、错单冲正、月底关账、银行对账、发票勾稽、备份恢复和反结账 SOP | 已完成 | [老板日常经营与账务安全 SOP](OWNER_OPERATIONS_AND_FINANCE_SOP.md) |
| 2 | 发票 PDF/图片、号码代码校验、标准导入、查验来源、业务签字附件、后补传和电子凭证归档 | 已完成 | Backend `ElectronicEvidenceArchiveContractTest`、`TaxInvoiceReconciliationContractTest`；Web `EvidenceAttachments.vue` |
| 3 | 数据库与附件定时全量/增量备份、链完整性、保留、加密、验证和失败保护 | 已完成 | [M2 增量备份真实验收](https://github.com/soyia/copper_deploy/blob/main/docs/M2_INCREMENTAL_BACKUP_ACCEPTANCE_20260721.md) |
| 4 | 银行流水 CSV/Excel 模板导入、重复识别、金额/日期/对方/用途自动匹配及未匹配原因 | 已完成 | Backend `BankStatementImportContractTest`、`BankStatementFileParserTest`；Web `BankReconciliations.vue` |
| 5 | 业务事实与会计规则覆盖矩阵，覆盖资金、承兑、退款退货、调拨在途、成本调整、红蓝票及进项转出 | 已完成 | Backend `AccountingRuleCoverageContractTest` |
| 6 | 老板经营前端闭环：应收应付、逾期、库存风险、银行未匹配、税票未勾稽和关账阻断项 | 已完成 | Web `owner-operations-dashboard.test.mjs`；Backend `OwnerOperationsDashboardContractTest` |
| 7 | 库存/银行/税票/关账单据打印导出、审计说明及签字盖章扫描件回附原单 | 已完成 | Web `document-evidence-printing.test.mjs`、`useAuditedDocumentPrint.ts`；Backend `ElectronicEvidenceArchiveContractTest` |
| 8 | 老板、财务、库管、销售、采购默认岗位真实授权与高风险越权阻断 | 已完成 | [默认岗位权限验收](DEFAULT_ROLE_PERMISSION_ACCEPTANCE.md) |
| 9 | 关账脏数据阻断的十类真实异常数据验证 | 已完成 | [Backend 关账脏数据验收](https://github.com/soyia/copper_backend/blob/main/docs/FINANCIAL_CLOSE_DIRTY_DATA_ACCEPTANCE.md) |
| 10 | V8-V24 库存、过账、会计、总账、税票、银行、关账、CRM 的业务导出和恢复覆盖 | 已完成 | [Deploy PR #136](https://github.com/soyia/copper_deploy/pull/136)、Deploy `test_erp_backup_restore_coverage.py` |
| 11 | 15 类铜品类逐类完成建档、SKU、采购、入库、销售、出库、退货、库存和包装差异验收 | 已完成 | [Backend 15 类商品验收](https://github.com/soyia/copper_backend/blob/main/docs/15_PRODUCT_CATEGORY_ACCEPTANCE.md) |
| 12 | 采购到月结的真实事务端到端验收，包含退货、调拨、盘点、成本、承兑、税票、银行和结账 | 已完成 | [Backend ERP 月结真实端到端验收](https://github.com/soyia/copper_backend/blob/main/docs/ERP_MONTH_END_E2E_ACCEPTANCE.md) |

## 3. 两个收口

| # | 收口项 | 状态 | 主要证据 |
|---:|---|---|---|
| 13 | 15 类商品真实 MySQL 验收成为 PR 双架构验证和 `main` 镜像构建的强制 `needs` | 已完成 | [Backend PR #55](https://github.com/soyia/copper_backend/pull/55)，15 类 MySQL、amd64、arm64 检查全部通过 |
| 14 | 当前内部 ERP 基线的加密整站备份、空库旁路恢复、切换、回滚和失败关闭合同 | 已完成 | [Deploy 当前 ERP 基线恢复验收](https://github.com/soyia/copper_deploy/blob/main/docs/M2_RESTORE_ACCEPTANCE.md)、[Deploy PR #137](https://github.com/soyia/copper_deploy/pull/137) |

## 4. 不得扩大的结论

本矩阵只允许得出：**当前内部 ERP 基线恢复合同已经闭合，12 项建设验收和 2 个收口按当前代码与证据完成。**

下列事项不由本矩阵替代，也不得因上述完成状态自动勾选：

- [ ] 使用客户本人正式持有并离线保管的恢复私钥完成验收。
- [ ] 使用正式发布清单中的 Web、Backend、MySQL 不可变发布镜像完成验收。
- [ ] 在正式灾难恢复环境和客户交付条件下完成最终客户验收。

因此，不得把本状态表述成“正式客户灾备已经交付”“正式发布已经完成”或“所有未来业务变化无需再验收”。后续修改上述任一模块、数据合同、迁移、附件规则、镜像依赖、备份格式或恢复状态机时，必须重跑受影响的自动化、真实 MySQL 和恢复演练，并更新冻结提交与证据。

## 5. 后续覆盖性变更重新验收记录

| 日期 | 变更 | 受影响基线 | 重新验收证据 | 结论边界 |
|---|---|---|---|---|
| 2026-07-23 | ERP-12 电子凭证原件、外部验真/验签证据、业务关联和增值税复核工作底稿 | 第 1、2 项；第 10 项中的业务导出与会计电子归档覆盖 | [Backend PR #66](https://github.com/soyia/copper_backend/pull/66)：V1-V44 空库迁移、真实 MySQL 事务、同月抵扣转出、版本替代、归档原件/证明/底稿及零残留回滚；[Backend PR #67](https://github.com/soyia/copper_backend/pull/67)：EINV、BKER、BKRS 三类凭证在真实 MySQL 中完成外部验证证据、类型约束业务关联和会计归档验收；[Web PR #146](https://github.com/soyia/copper_web/pull/146)：生产构建及隔离真实浏览器验收 | 仅证明 ERP-12 受影响的数据、附件、业务导出和会计归档合同重新闭合；解析不等于验真/验签，工作底稿不等于申报表或回执，也不扩大为正式客户交付结论 |
| 2026-07-23 | ERP-15 轻量风险例外审批、不可变业务快照、单级授权与批准后独立执行 | 第 1 项；第 8 项中的默认岗位和高风险控制；第 10 项中的业务导出与审计恢复覆盖 | [Backend PR #68](https://github.com/soyia/copper_backend/pull/68)：V1-V45 空库迁移、4 项真实 MySQL 生命周期验收、批准单次消费、真实付款执行、失败零残留、ERP-07 风险回归、完整月结真实事务回归及 189 张业务表导出合同；[Web PR #148](https://github.com/soyia/copper_web/pull/148)：99 项生产测试、生产构建及隔离真实浏览器桌面/820px 响应式验收 | 仅证明特殊售价、超授信、较大付款、资金/库存调整和高风险冲正的内部单级例外控制重新闭合；批准不等于业务执行，不引入通用 BPM，也不扩大为正式客户交付结论 |
| 2026-07-24 | ERP-18 期初数据和旧系统迁移：来源工作簿预检、幂等应用、切换前整批撤销、正式切换、持续勾稽及备份恢复 | 第 1 项 SOP；第 8 项权限；第 10 项业务导出与恢复；第 11 项商品主数据；第 12 项完整月结回归；第 14 项整站恢复合同 | [Backend PR #69](https://github.com/soyia/copper_backend/pull/69)：V55 空库迁移、3/3 真实 MySQL 场景、完整单元测试、15 类商品、月结和期初迁移通过；[Web PR #149](https://github.com/soyia/copper_web/pull/149)：117 项生产测试、生产构建及隔离真实浏览器工作区/错误恢复验收；[Deploy ERP-18 恢复验收](https://github.com/soyia/copper_deploy/blob/main/docs/ERP18_OPENING_MIGRATION_RESTORE_ACCEPTANCE.md)：232 张表、6/6 迁移控制表、切换批次/逐行事实/历史差异/资金期初/审计链、负向失败关闭、最终切换和回滚在 MySQL 8.4.10 通过 | 期初来源、映射、校验、应用、撤销和切换已经进入当前内部 ERP 基线；切换前可按原快照撤销，切换后只能使用调整或冲正，禁止覆盖历史。当前个人开发默认不使用 Actions、Deploy 不走 PR；本结论不得外推为正式发布或客户迁移验收 |
| 2026-07-24 | 轻量商贸经营能力包：其他出入库、透明补货与库龄、贡献毛利、移动扫码、批次质保、交付签收、采购报价、文件导入及最小外部只读协同 | 第 1 项 SOP；第 5 项会计覆盖；第 6 项老板经营；第 8 项岗位权限；第 10 项业务导出；第 12 项采购销售库存资金链路 | [轻量商贸经营能力内部验收](LIGHTWEIGHT_TRADING_OPERATIONS_ACCEPTANCE.md)：Web 121 项生产测试与生产构建通过，Backend 265 项测试通过，V1-V56 空库迁移与八项真实 MySQL 场景通过，并完成 1280px 桌面端、390px 手机端和公开只读失效链接的隔离浏览器验收 | 以上能力均为可选助手或原单据旁的附加动作，不增加采购、销售、发货、收付款和关账前置步骤；补货建议不得自动下单，交付签收不得代替收款，分享/API 不是完整门户或第三方双向集成。本结论只覆盖当前内部基线，不扩大为正式发布或客户交付 |
| 2026-07-24 | 供应商直发客户的直运购销闭环：联动采购与销售、部分发出、客户实收、差异原因、应收应付过账、退货及冲正 | 第 1 项 SOP；第 5 项会计覆盖；第 6 项老板经营；第 8 项岗位权限；第 10 项业务导出与恢复；第 12 项采购销售资金链路 | [Backend PR #75](https://github.com/soyia/copper_backend/pull/75)：270 项测试、V57 空库迁移和真实 MySQL 建单至冲正闭环通过；[Web PR #158](https://github.com/soyia/copper_web/pull/158)：124 项生产测试、生产构建及 1280px/820px 隔离真实浏览器验收；[直运购销内部验收](DIRECT_TRADE_OPERATIONS_ACCEPTANCE.md)；[Deploy 提交 c2b9c08](https://github.com/soyia/copper_deploy/commit/c2b9c086fd353da89492d591067c2527c15f4646)：18 个直运外键进入恢复元数据 | 仅客户实收形成应收应付，供应商发出不入账，全流程不生成库存事实；当前仅支持单供应商直运。MySQL 9.7 独立空库逻辑恢复证明当前数据合同可恢复，不替代正式 MySQL 8.4.10 镜像、加密整站包、客户持钥或正式灾难恢复验收 |
| 2026-07-24 | 四项轻量经营协同：报价/客户对账/交付摘要外部确认、采购跟催、7/30 天现金窗口和单据识别候选 | 第 1 项 SOP；第 5 项会计和资金边界；第 6 项老板经营；第 8 项岗位权限；第 10 项业务与附件导出恢复；第 12 项采购销售资金链路 | [Backend PR #76](https://github.com/soyia/copper_backend/pull/76)：274 项测试、V58、2 项隔离 MySQL 事务及零残留通过；[Web PR #159](https://github.com/soyia/copper_web/pull/159)：128 项生产测试、生产构建及 1280px/390px 隔离真实浏览器验收；[四项轻量经营协同内部验收](OPERATIONAL_LITE_SUITE_ACCEPTANCE.md)；[Deploy 提交 09ca28c](https://github.com/soyia/copper_deploy/commit/09ca28c)：新增六张表及六条外键进入恢复元数据，Deploy 287 项全量测试通过 | 四项能力均为原主流程旁的可选助手。供应商已发不等于入库，客户确认不等于收付款或核销，现金窗口不创建资金计划，人工确认识别候选仍不写入正式业务；本结论只覆盖当前内部源码与隔离恢复合同，不扩大为门户、自动 OCR 入账、可靠电子签名、正式发布或客户交付 |
| 2026-07-24 | Mobile Fast Lite：手机登录自动分流至一个权限驱动的手机工作台，覆盖查货、报价/下单草稿、收款、扫码盘点草稿和拍照归单 | 第 1 项 SOP；第 2 项电子凭证附件；第 6 项老板经营入口；第 8 项岗位权限；第 12 项采购销售库存资金链路 | [Mobile Fast Lite 内部验收](MOBILE_FAST_LITE_ACCEPTANCE.md)：137 项生产测试、生产构建及 390px 手机端、1280px 电脑版隔离真实浏览器验收通过 | 复用同一 ERP 的用户、权限、API、状态机和审计，不建设多套角色 App；手机默认进入统一工作台，强制修改初始密码仍优先；PWA 只缓存静态壳层，写操作必须在线，报价/订单和盘点只保存草稿，扫码/拍照不自动生成库存、应收应付、资金或会计事实。本结论不扩大为正式设备兼容、互联网远程访问、应用商店交付、离线记账、正式发布或客户交付 |
| 2026-10-16 | `0.4.0-rc.7` 开发交付：冻结既有 Web 工作树并修复数量六位精度 | 第 11 项中的单位/包装/商品；第 12 项中的采购、销售、收发货、退货、直运和盘点数量；来源包含帮助中心与强制改密快照 | [Web 候选与数量验收](RELEASE_CANDIDATE_0.4.0_RC7_WEB_ACCEPTANCE.md)：175 项生产测试、13 项数量及 LAN 合同专项、TypeScript 与生产构建通过；数据库及恢复以三仓同轮证据为准 | 原工作树完整保留，候选独立提交；本次负责人明确跳过浏览器与手机/设备，不作为开发交付和冻结阻断，第三方待办继续记录但不阻断本轮；不伪造浏览器、设备或外部结论，不扩大为公开发布 |
