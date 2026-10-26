# M7-06 多环境试点与首发支持范围决策

> 状态：`INTERNAL_REHEARSAL_ONLY`
>
> 决策日期：2026-07-18
>
> 对应 Issue：[`soyia/copper_deploy#88`](https://github.com/soyia/copper_deploy/issues/88)
>
> 决策结论：`FIRST_RELEASE_CLOUD_ONLY`。Copper `1.0.0` 首发拟支持范围只保留客户自有 Ubuntu 24.04 LTS x86_64 云服务器；本地/LAN、Windows 宿主 Ubuntu VM、NAS 主机、ARM64、扫码枪和针式打印均移出首发标准兼容清单。该范围决定不等于正式发布授权，M6、M7-02 正式独立客户样本和 M7-07 总审计仍为 `FORMAL_RELEASE_BLOCKED`。

## 1. 为什么采用云端首发

M7-06 的目标是让销售、安装和客户预期只引用有真实证据的环境，而不是为了“多环境”数量把工程模拟或缺少设备的结果写成正式兼容。

目前只有客户云 Ubuntu 24.04 x86_64 具备完整证据链：空白安装、首次管理员、真实经营、升级/回滚、备份、离线解密、旁路恢复、自由导出、断外联经营、监控和重启。Mac、手机和 A4 打印已经取得实体客户端证据，但没有完成本地服务器、Windows 宿主 VM、实体外接盘、远程 NAS、独立设备 B、扫码枪和针式打印的真实全生命周期。

因此首发只承诺客户云，其他环境不再作为 `1.0.0` 的隐含前置，也不得用 `¥649起` 本地部署目标价吸引客户后再临时试装。未来取得独立实体证据后，以新版本重新评审，不追溯改变首发范围。

## 2. 首发兼容矩阵

| 环境/能力 | M7-06 等级 | `1.0.0` 首发 | 现有证据 | 限制与后续 |
| --- | --- | --- | --- | --- |
| 客户自有公有云 VM：Ubuntu Server 24.04 LTS、x86_64/amd64 | `Candidate（1.0 首发拟支持）` | 纳入拟支持范围 | M1-M4 生命周期；M7-02 空白安装与交接；M7-03 经营闭环；M7-04 升级、恢复、导出和断外联 | 最少2 vCPU、4 GiB、20 GiB可用本地可靠磁盘；Docker 29.x、Compose 5.x；客户准备域名、DNS、80/443和云账号。只有 M7-07 `GO` 后才可晋级 `Certified` 并进入正式销售 |
| 其他云厂商但满足同一精确 Ubuntu/amd64 合同 | `Candidate` | 不自动纳入 | 只有 AWS Singapore 完整 M7 样本 | 首个非 AWS 云客户按标准检查；完成真实样本前不能宣传“所有云厂商” |
| Ubuntu 24.04 x86_64 本地常开实体主机 | `Candidate` | 移出 | 云主机和工程合同通过；无独立实体本地主机全生命周期 | 未来需安装、重启/断电、休眠策略、LAN、升级、实体异机恢复和成本复验 |
| Windows 11 宿主 Ubuntu 24.04 x86_64 VM | `Deferred` | 移出 | 仅冻结架构；没有精确虚拟化产品、桥接、自动启动和Windows重启实证 | 不支持Windows原生、WSL2或Docker Desktop；未来必须按指定虚拟化产品重验 |
| 指定 Synology x86_64 / Container Manager | `Deferred` | 移出 | 无真实型号、DSM、存储和生命周期证据 | 不宣传“支持群晖/所有NAS”；MySQL在线数据不得位于NFS/SMB |
| Linux/ARM64、macOS Apple Silicon 主机 | `Candidate artifact / Unsupported install` | 移出 | 有候选镜像合同，无当前安装器和生命周期证据 | ARM64镜像构建成功不等于平台支持；macOS不运行Copper生产容器 |
| Windows原生、WSL2、Docker Desktop、Intel Mac、任意NAS/低配设备 | `Unsupported` | 不纳入 | 无正式路线且风险不可接受 | 安装前说明不支持，不提供临时绕过 |

当前没有任何正式 `Certified` 环境。云端只是在 M7-06 被选为 `1.0` 首发拟支持范围；正式状态仍由 M7-07 决定，不能提前发布、收费或交付。

## 3. 存储、备份和恢复范围

| 项目 | 首发决定 | 说明 |
| --- | --- | --- |
| `/var/lib/copper` 在线数据 | 支持可靠本地块存储 | ext4、xfs 或 btrfs；禁止 NFS、SMB、Windows共享盘、同步盘和可移除盘 |
| 服务器本机加密备份 | 支持，但明确同故障域警告 | 可防逻辑误操作，不能承诺整机/云盘损失可恢复 |
| 客户手工下载加密备份并离线验包 | 支持 | 客户私钥不上传服务器；下载副本由客户保管 |
| S3兼容异机目标 | `Candidate` | 工程合同已通过，但无真实首发客户目标和异机通知样本，不形成代运维/RPO承诺 |
| 实体独立外接盘 | `Deferred` | Deploy #63 实体登记、断盘、重新挂载和恢复门槛未完成，移出首发标准服务 |
| 与主机分离的远程 NAS | `Deferred` | 工程 NFS 故障注入不能替代真实 NAS；移出首发标准服务 |
| 独立设备 B 换机恢复 | `Deferred` | M7-04 同机隔离恢复只证明云端等价运行时，不替代独立设备 B |
| 客户双人离线私钥交接 | `FORMAL_RELEASE_BLOCKED` | 内部 NOT-FORMAL 回执不能替代客户本人和独立见证人签署 |

首发标准云部署只承诺配置一个已取得证据的备份路径和一次演练范围内的恢复验证。只有本机备份时，订单和交接单必须显著写明“整机/云盘损失不受保护”；不得销售 RPO/RTO、代运维或异机容灾承诺。

## 4. LAN、终端与外设决定

| 项目 | 已有证据 | M7-06 决定 |
| --- | --- | --- |
| Mac + 手机双终端 | 独立会话、权限、相邻写入、图片上传、客户端短断网和Mac客户端休眠观察通过 | 保留为浏览器客户端体验证据；不等于本地/LAN服务器支持 |
| HP Laser MFP 136w A4打印 | Mac Chrome实体单页和24行两页打印通过 | 保留为代表性浏览器/操作系统A4打印证据；不建立打印机型号兼容清单，不负责驱动 |
| HID USB/蓝牙扫码枪 | 工程键盘模拟流程通过，实体设备未通过 | `Deferred`，移出首发外设承诺；允许普通键盘输入条码，但不得宣传扫码枪已认证 |
| 得力 DE-620K 针式打印 | Web版式工程合同通过，实体Windows打印未通过 | `Deferred`，移出首发；不宣传连续纸、撕纸位置或驱动兼容 |
| 本地主机休眠/LAN地址变化 | 客户端休眠通过，本地主机未通过 | `Deferred`；客户云主机不得依赖自动睡眠，DNS/IP变化由云平台与维护流程处理 |
| Windows宿主VM局域网 | 未实测 | `Deferred`，移出首发 |
| LAN 上的 Cloudflare Tunnel 可选互联网入口 | 已实现边车、root-only token、Access 默认拒绝验证和维护生命周期；尚无客户真实网络/中国大陆质量与合规验收 | `Internal rehearsal / Deferred after 1.0`；只随未来本地/LAN版本复验，不改变 `FIRST_RELEASE_CLOUD_ONLY`，也不承诺 Cloudflare SLA 或免费套餐长期边界 |

## 5. 延期 Issue 的处理决定

| Issue | M7-06 处理 | 是否代表功能通过 |
| --- | --- | --- |
| Deploy [#63](https://github.com/soyia/copper_deploy/issues/63) 外接盘/NAS/设备B | 以 `not planned for 1.0` 关闭；保留工程成果和未完成清单，未来新版本有真实设备时另开复验任务 | 否 |
| Deploy [#64](https://github.com/soyia/copper_deploy/issues/64) 本地LAN | 以 `not planned for 1.0` 关闭；Mac/手机/A4局部证据保留，Windows VM和本地主机不通过 | 否 |
| Web [#44](https://github.com/soyia/copper_web/issues/44) M5-07 | 以 `not planned for 1.0` 关闭；M5状态改为 `Deferred after 1.0`，没有本地平台晋级 | 否 |
| Web [#41](https://github.com/soyia/copper_web/issues/41) M5总跟踪 | 在上述范围决定合入后以 `not planned for 1.0` 收口；已完成工程资产继续保留 | 否 |

关闭这些 Issue 是首发范围治理，不是勾选未完成验收。若未来恢复本地/NAS方向，必须从真实设备清单、客户需求、成本和安全故障域重新建 Issue，不得直接把旧工程报告改名为 `Certified`。

2026-07-24 增加的 [Cloudflare Tunnel 可选连接器](M5_CLOUDFLARE_REMOTE_ACCESS.md)属于上述未来本地/LAN方向的内部工程资产。它没有把本地部署重新纳入 `1.0.0`，也没有改变云端首发的公网入口、交付价格或正式门槛。未来恢复本地部署时，应为客户网络验收、第三方权利/SBOM、数据路径与中国大陆实测另建可追踪任务。

## 6. 价格和责任边界

- 自助部署 `¥0` 和客户云标准部署 `¥349/生产环境` 的内部目标保留；是否正式生效仍等待 M7-07。
- 本地/NAS部署 `¥649起` 只保留未来目标价，不进入 `1.0.0` 价格页面、订单选项或销售承诺。
- 代运维 `¥99/月或¥995/年` 在真实异机目标和通知样本完成前不发布。
- 对不在兼容矩阵中的环境，只能先做书面可行性评估；`¥150/小时`也不能用于绕过 `Unsupported` 安全红线。
- 客户负责云账号、域名、DNS、备案、80/443、安全组、磁盘容量、异机备份目标和恢复私钥；Copper不成为这些资产的唯一控制方。

## 7. 证据覆盖与缺口

### 7.1 客户云证据

- M7-02：AWS Singapore Ubuntu 24.04.4 LTS x86_64，2 vCPU、约8 GiB、48 GiB根盘；空白安装至健康、首次改密、加密备份、离线解密、旁路恢复、导出和自动备份完成。首份样本总墙钟86分31秒，安装至健康28分40秒，含约15分钟实时支持，结论仍为 `SUPPORT_REQUIRED`。
- M7-03：同类 AWS Ubuntu 24.04 x86_64，2 vCPU、4 GiB、MySQL 8.4；使用虚构数据完成采购、入库、库存、销售、出库、应收应付、收付款和来源追溯。本轮没有形成可独立复核的整段墙钟，因此不制造时长结论，只保留业务和缺陷复验证据。
- M7-04：完成rc.3至rc.4升级、失败注入、50秒回滚、再次升级、重启、21–25秒备份、45秒同机隔离恢复、2秒完整导出及断外联经营；最终88表、1,419记录、3附件、98/98包内摘要通过。

### 7.2 仍保留的正式阻断

- M7-02 内部工程链已由真实 AWS 安装/恢复/修复证据和自动操作者连续模拟收口；正式发布前仍需非开发客户使用精确 `origin/main` 闭集介质完成零新增命令、零实时支持空白样本。
- M6 GitHub平台历史引用、最终权利/SBOM/许可证材料和外部专业复核。
- 首次收费前的企业会计税务复核和正式客户双人交接。
- M7-07 从三个最新 `origin/main` 执行总审计并形成书面 `GO`；在此之前不得创建 `v1.0.0`。

### 7.3 可复核来源

- [M7-02 首份自助部署样本](M7_02_SELF_SERVICE_EVIDENCE.md)
- [M7-04 升级、恢复与连续经营证据](M7_04_UPGRADE_RECOVERY_CONTINUITY_EVIDENCE.md)
- [`copper_web` M7-03 首日经营验收](https://github.com/soyia/copper_web/blob/main/docs/self-hosting/M7_03_FIRST_DAY_BUSINESS_ACCEPTANCE_REPORT.md)
- [`copper_web` M7-05 价格与服务成本复算](https://github.com/soyia/copper_web/blob/main/docs/self-hosting/M7_05_PRICE_SERVICE_COST_RECALCULATION.md)
- [M5 LAN 历史工程验收](M5_LAN_ACCEPTANCE_REPORT.md)
- [M5 存储迁移历史工程验收](M5_STORAGE_MIGRATION_ACCEPTANCE_REPORT.md)

## 8. M7-06 退出结论

M7-06 内部状态为 `INTERNAL_REHEARSAL_SCOPE_DECIDED`：

- [x] 对所有原拟支持环境给出精确的当前状态和首发处置；当前无正式 `Certified` 环境。
- [x] 只把具备完整证据链的客户云 Ubuntu 24.04 x86_64 纳入首发拟支持范围，并保持为 `Candidate` 直到 M7-07 `GO`。
- [x] #63、#64、M5-07、扫码枪、针式打印、主机休眠和第三环境全部移出首发，不制造“普遍支持”。
- [x] 已记录硬件、系统、架构、资源、时间、支持事件、失败和限制。
- [x] 安装手册、兼容清单、责任边界和价格适用范围使用同一口径。

可以进入 M7-07 内部总审计；M7-02 内部工程任务不再要求产品负责人重复人工安装，但当前正式决定仍为 `NO-GO / FORMAL_RELEASE_BLOCKED`。M7-07 只能在 M6 和真实非开发客户独立安装门槛完成后改为 `GO`。
