# Copper 0.4.0-rc.11 实际验收回执 — 2026-10-26

本轮内部发布工程收口完成，结果为 `INTERNAL_ENGINEERING_ACCEPTANCE_PASS`。适用范围是已授权服务器 `18.142.134.26` 上的 `INTERNAL_REHEARSAL_ONLY`；真实客户、非开发者、专业复核与签署事项按负责人决定备注延期，不作为本轮内部交付阻挡条件。它们没有被记录为通过。没有创建公开 Release、产品标签或客户生产交付。

本回执是验收完成后的新增证据。原计划、失败日志、冻结输入和介质保持原字节；原 `PACKAGE_STATUS.json` 的打包时“验收待执行”状态不改写，本回执记录后续实际结果。

| 收口项目 | 实际结果 |
|---|---|
| 三处原始业务问题 | 已修复并包含于当前主干：停用主体及对应身份禁止新增经营过账（保留历史查询、已完成幂等和安全冲正）；数量六位、金额仍两位；附件引用由六类补齐到十类，新增四类原件缺失及同长度篡改拒绝成功 |
| 版本与介质冻结 | rc.11 input revision 2；三镜像真实构建、导出、删除再导入、身份及实际运行镜像一致；最终介质生产校验通过 |
| 默认安装与公网 | 实际空白默认路径安装完成，三层 healthy；公网首页与 `/healthz` HTTP 200，独立 Mac curl 证书校验结果 0，IP SAN 为本机地址 |
| 当前实库 | Flyway 68，92 外键（核心 78），无外键孤儿、全 InnoDB；库存、成本、应收应付、凭证与两份附件摘要逐阶段核验通过 |
| 全量及增量恢复 | 实际 v1 全量、追加收付款、v2 增量、正式 stage 导入与 binlog 回放均通过；真实 TTY 确认 switch，恢复后业务核对通过 |
| 恢复后连续经营 | 新销售、出库、收付款与凭证通过；实际 down/up 重建三容器，容器 ID 全部变化，业务、附件、持久目录身份与回滚槽保持 |
| 恢复后独立备份 | 新 v1 全量、第二次真实 Mac 离线解密、新 full 单独 stage 通过；stage2 正式 discard，第一次 SWITCHED 回滚保留 |
| 临时访问与密钥 | 专用 GitHub agent 在授权一小时内关闭；日常 agent 未改；服务端合成私钥与输入回执副本已清理，正式 backup key audit findingCount=0；Mac 合成原密钥受控保留 |
| 权利材料工程核对 | 新 M4 与源码/实际三镜像绑定；291 OS/JRE 身份核对，799 条内容摘要加 CHECKSUMS 共 800 普通文件闭合通过 |

最终经营事实：库存 **7.999999 kg**、库存成本 **400.98**、应收 **55.14**、应付 **280.42**。均来自真实 API 操作与实库读取，未用 SQL 直接造业务数据；业务预期没有为通过验收而调整。本轮新机业务样本不替代既有十五类商品回归，也不宣称本轮重新逐类跑完十五类。

版本身份：Backend `ecd7ee7001bf01368504c98e579f8e9c3cbe6af9`，Web `d497d7958e6148cd31a47248fc36c85f2e0c24ed`，Deploy 运行时代码构建来源 `f74b32c01360e68a8b2fc843bec95c4999be9893`，输入冻结提交 `10b01725e653fffa5c2113f8cb49d4dbbcb9c048`，真实构建 run `50e31e28136b`。revision 2 只修正尚未公开的 notes 必填章节及其摘要，没有声称重新构建镜像。

| 不可变材料 | SHA256 |
|---|---|
| 最终安装介质 `rc11-guided-final-r2.tar.gz` | `e8f2407121fef052523240b5418dcafa665650d2517637fb346cce0b1860ecd3` |
| 最终介质根 SHA256SUMS | `710a1af86baed98502dd8971274298c2297ce408286abc8bca98635b448d9b17` |
| 实际应用 M4 包 | `1f8d276ae06031afc8cd6d1e92a53899d044c34f51c9dea222f3036995ea9086` |
| 权利总材料包 r2 | `8c2c86a80615e76de47e324360a0fbc87c036ef6a22897c171488fffac7bbcd1` |
| 本回执 runtime evidence（156 条闭集＋闭集索引） | `15b430ed562ab9c86c112f1b6e3cc14352a75dafc95bd144a7ac8d22510a1aeb` |
| 原冻结验收驱动器 | `2856c1d03cec67baaaffe7bf9acb7892c82466ad5fde2297836f9c4d3336189d` |

**保留策略与验收组成。** 原冻结驱动器 15 阶段全部 PASS，原 `final` 没有执行。它要求旧三份包一直存在，与实际 daily=7、weekly=4、monthly=6 按每个 UTC 时段选择最新完整全量链的策略冲突。新 full 成功后，同日旧 full＋inc 被正常轮换；旧 full 的 Mac 原副本重新核验通过，旧 inc 原字节未留存，不能再取回，未重建或伪造。新增独立终验工具重复读取当前业务、最新备份、两次 stage 比较、Mac 证明及回滚槽，通过 10 项正反合同测试并实际返回 `PASS_WITH_EXPECTED_RETENTION_ROTATION`。原驱动器与 ledger 没有修改。删除观察来自执行日志，未冒充产品结构化删除回执。

**安全与第三方延期事实。** 应用 M4 原始扫描仍有 2 Critical、5 High；七项均以精确组件/版本/原严重级别和源码/实际镜像适用性证明记录为技术 FALSE_POSITIVE，无降级、无未审风险接受。原有效期仍为 `2026-10-31T12:48:26.362098+00:00`，后续出包需重新评估有效性。另列 Docker 官方五个 DEB 的已知 6 Critical、29 High 上游项，按负责人指令延期，不声称应用 M4 已修复 Docker。无 Docker TCP 2375/2376 暴露。

M6 正式权利审批仍 `NOT_CLOSED`，engineering/legal approval 为 null。四组件／六范围保留 REVIEW_REQUIRED：de-indent、lodash-unified 缺发布者 MIT 正文与主体声明；linkedca、blake3 有正文、主体未明示。原件与来源保留，没有补造权利人。律师、会计、合同与支持生命周期签署、真实非开发者独立安装、客户持钥恢复与双人见证签署、真实浏览器及承诺支持的手机设备验收均为 `NONBLOCKING_INTERNAL_OWNER_DEFERRED`。开发侧合同/支持/交接说明和模板已整理，未声称已签署商业 SLA，也未将专业意见冒充开发结论。正式对外发布仍保留独立门槛。

本次 freshInstallOnly，直接升级来源为空；同版本备份恢复不等于 rc.10→rc.11 升级。Docker 在验收机预先在线安装；已通过应用镜像离线加载，不构成 Docker 离线安装或全主机无网络安装验收。rc.10 原失败介质和合成站点冷保留，未作为通过版本重用。

[机器可读完整回执](ACTUAL_ACCEPTANCE_20261026.json)列明镜像 ID、备份身份、延期和证据摘要。[脱敏实际证据包](actual-acceptance-20261026/runtime-evidence.tar.gz)含原 ledger、实际数据库/附件/API、两次恢复比较、宿主机事实及密钥清理报告；本地独立校验全部 156 条摘要，无私钥、实际密码或原始 proof。原冻结驱动器、补充终验程序与合同测试一并归档，便于审计复核。

安装介质受控副本仅通过 SSH 读取：`ubuntu@18.142.134.26:/home/ubuntu/copper-acceptance-20261025/export/rc11-guided-final-r2.tar.gz`，权限 0600，配套 `.sha256`。验收证据副本同目录 `rc11-final-acceptance-evidence.tar.gz`。最新备份 `b7d4e16dc13e810f160c8b161c4b09fb` 位于 `/var/backups/copper`，另有实际 Mac 副本；首个恢复回滚槽位于 `/var/lib/copper-restores/release-recovery-rc11`。验收站点继续运行，仅含合成数据。
