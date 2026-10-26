# Copper GitHub 路线图任务拆分（M0-M7）

> 建立日期：2026-07-13
> 状态：M0 至 M4 已完成；M5 实体本地范围经 M7-06 移出 1.0 首发且没有本地 Certified，Cloudflare 可选远程访问仅完成内部工程基线；M6 内部全流程演练已通过但正式退出仍阻断；M7-02 内部工程自动验收通过，M7-07 仍保持产品负责人签署的 `NO_GO`
> 权威产品与验收清单：`docs/SELF_HOSTING_AND_DATA_CONTROL_ROADMAP.md`
> 用途：在 GitHub App 获得仓库访问权限后，按本文件顺序创建 Issues、依赖关系和 Project 视图。

## 1. GitHub Project 建议配置

### Project

- 名称：`Copper 自主部署路线图`
- 范围：前端 `soyia/copper_web`、后端 `soyia/copper_backend`，以及未来确定的部署资产仓库。
- 管理原则：GitHub `origin` 是唯一代码和工作状态源；不读取、同步或推送 Gitee。

### 状态字段

1. 待规划
2. 可开始
3. 进行中
4. 代码评审
5. 真实验收
6. 已完成
7. 阻塞

### 自定义字段

| 字段 | 类型 | 建议值 |
| --- | --- | --- |
| 里程碑 | 单选 | M0 至 M7 |
| 优先级 | 单选 | P0、P1、P2 |
| 责任面 | 单选 | 产品、商业、法务、前端、后端、数据库、部署、安全、运维、交付 |
| 目标版本 | 文本 | 尚未排期时留空 |
| 依赖 | 文本 | 使用 Issue 编号，例如 `#12` |
| 验收证据 | 文本 | PR、测试或真实环境验收链接 |

### 推荐视图

- `日常看板`：按状态分列，隐藏已完成，显示负责人、优先级和里程碑。
- `里程碑总览`：表格按里程碑分组，显示完成率、依赖和阻塞。
- `实施路线`：Roadmap 视图，仅在目标版本和真实排期确定后填写日期。
- `我的工作`：按 Assignee 过滤，个人或 Codex 每次只领取一个主要进行中任务。
- `阻塞项`：过滤状态为阻塞，必须显示解除条件。

## 2. 执行规则

- 一个 Issue 交付一个可以独立验收的结果，不把整个里程碑塞进单一 Issue，也不为每一句说明机械建 Issue。
- Issue 标题使用稳定编号：`[M0-01]`、`[M1-02]`。编号关闭后不复用。
- 只有前置依赖全部完成，任务才能进入“可开始”。
- 代码完成后进入“代码评审”，不能直接进入“已完成”。
- 真实安装、升级、备份、恢复、离线或跨环境验收完成后，才能进入“已完成”。
- 关闭 Issue 前必须更新权威路线图 checkbox 和更新记录；路线图没有证据，不视为完成。
- 同一负责人原则上同时只保留一个主要“进行中”任务，降低跨仓库半成品数量。
- Agent 自动创建的开发分支必须完成验证、PR评审并合入 GitHub `main`；合并后本地 `main` 必须仅快进同步到 `origin/main`。除非产品负责人明确要求暂停，不得把“分支已推送”或“PR已创建”作为完成状态。

## 3. M0 Issues

### M0-01：经营主体、站点和账套隔离规则

- GitHub Issue：<https://github.com/soyia/copper_web/issues/1>
- 当前状态：已完成
- 建议仓库：`soyia/copper_web`（产品跟踪 Issue）
- 责任面：产品、后端、数据库
- 优先级：P0
- 初始状态：可开始
- 前置依赖：无
- 目标：确定一个经营主体、数据库/站点、多个账套及多个部署之间的权威关系，避免以“无限账套”破坏数据隔离。
- 对应路线图：M0 产品边界第 5、6 项。
- 验收条件：
  - [x] 明确默认“一经营主体一站点/数据库”是否成立。
  - [x] 明确同主体多经营账簿与多个独立经营主体的差异。
  - [x] 明确多个实例的软件费用、人工部署和运维费用边界。
  - [x] 明确数据隔离、编号、库存、应收应付、资金和审计规则。
  - [x] 结论写入路线图或正式产品规则，并通过前后端数据模型评审。

### M0-02：客户、Copper 与第三方责任矩阵

- GitHub Issue：<https://github.com/soyia/copper_web/issues/2>
- 当前状态：已完成
- 建议仓库：`soyia/copper_web`
- 责任面：产品、商业、运维
- 优先级：P0
- 初始状态：可开始
- 前置依赖：无
- 目标：明确服务器、域名、证书、数据库、备份、安全事件和第三方服务的责任边界。
- 对应路线图：M0 产品边界第 7 项。
- 验收条件：
  - [x] 分别覆盖自助部署、标准部署和代运维场景。
  - [x] 明确客户必须保管的账号、密码和加密密钥。
  - [x] 明确 Copper 可以操作、不能操作和需客户确认的事项。
  - [x] 明确短信、OCR、电子发票、地图、云打印和对象存储的第三方责任。
  - [x] 形成可直接用于合同附件和交接单的责任矩阵。

### M0-03：单一产品能力的价格与服务目录

- GitHub Issue：<https://github.com/soyia/copper_web/issues/3>
- 建议仓库：`soyia/copper_web`
- 责任面：产品、商业、财务
- 优先级：P0
- 当前状态：已完成；目标价格和服务额度已确认，采用中国试运营条件化税务口径
- 前置依赖：M0-02
- 目标：确定自助部署、标准部署、本地/NAS部署、维护、代运维、迁移和定制价格，不形成软件功能版本。
- 对应路线图：M0 收费和授权第 1 至 3 项。
- 验收条件：
  - [x] 每项服务有价格、交付内容、次数/工时、标准环境和超范围规则。
  - [x] 第三方资源、服务器和税费口径单独说明。
  - [x] 停止购买服务不影响现有系统继续运行。
  - [x] 建立 M1 至 M5 真实部署、维护和恢复成本的复算门槛，避免无限免费人工服务。
  - [x] 形成对外目标价格表和内部成本复核入口。

### M0-04：客户源码授权与公开开源法律方案

- GitHub Issue：<https://github.com/soyia/copper_web/issues/4>
- 当前状态：已完成中国境内低风险试运营条款；AGPL公开、外部贡献和高风险授权专项复核移至 M6/M7
- 建议仓库：`soyia/copper_web`
- 责任面：法务、工程、商业
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M0-01、M0-02
- 目标：确定公开开源前的客户源码交付授权，以及未来 AGPLv3 与商业双授权、贡献者协议和商标规则。
- 对应路线图：M0 收费和授权第 4 至 6 项。
- 验收条件：
  - [x] 客户获得与运行版本一致源码的永久内部使用、修改、备份和恢复权利。
  - [x] 明确公开再发布、转售、改名销售、OEM和商标限制。
  - [x] 确认中国试运营源码使用条款，并将 AGPLv3、商业双授权及外部贡献版权专项复核设为 M6/M7 门槛。
  - [x] 明确正式开源前后既有客户授权如何衔接。
  - [x] 形成中国境内低风险试运营可执行文本和后续仓库许可证决策记录。

### M0-05：M0 发布文本一致性验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/5>
- 当前状态：已完成（中国境内试运营基线）
- 建议仓库：`soyia/copper_web`
- 责任面：产品、商业、法务
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M0-01、M0-02、M0-03、M0-04
- 目标：完成 M0 退出验收，保证产品承诺、价格、合同、源码授权和第三方费用说明一致。
- 对应路线图：M0 退出条件。
- 验收条件：
  - [x] 所有 M0 前置 Issue 已形成规则和验收证据。
  - [x] 产品说明、价格表、服务边界和中国试运营授权文本已经形成。
  - [x] 不存在版本、用户、账套、存储或停止维护后的冲突表述。
  - [x] 已更新路线图 M0 状态和更新记录。

## 4. M1 Issues

### M1-01：冻结标准生产基线、部署拓扑和资产归属

- GitHub Issue：<https://github.com/soyia/copper_web/issues/7>
- 当前状态：已完成，Issue 已关闭
- 建议仓库：`soyia/copper_web`（跨仓库跟踪 Issue）
- 责任面：部署、前端、后端、数据库
- 优先级：P0
- 初始状态：进行中
- 前置依赖：M0-05
- 目标：确定首个正式支持的 Linux/客户云环境、服务拓扑以及部署脚本长期归属。
- 对应路线图：M1 支持矩阵和架构第 1 至 3 项。
- 验收条件：
  - [x] 冻结 Linux、CPU、内存、磁盘、Docker/Compose和浏览器基线。
  - [x] 明确 Web、后端、MySQL、反向代理、附件和备份目录拓扑。
  - [x] 明确端口、域名、TLS终止、网络暴露和数据卷边界。
  - [x] 确定部署资产仓库与版本对应规则。
  - [x] 形成架构决策记录，M1-02 至 M1-06 已据此实施。

### M1-02：前端生产镜像与同源 API

- GitHub Issue：<https://github.com/soyia/copper_web/issues/8>
- 当前状态：已完成，Issue 已关闭
- 建议仓库：`soyia/copper_web`
- 责任面：前端、部署
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M1-01
- 目标：建立可重复构建的前端生产镜像，通过正式反向代理使用同源 `/api`。
- 对应路线图：M1 架构第 4 项、安装与初始化第 1 项。
- 验收条件：
  - [x] 使用锁定依赖完成确定性生产构建。
  - [x] 镜像不包含开发服务器、私密环境变量和源码运行依赖。
  - [x] 刷新工作区详情路由不会出现 404。
  - [x] `/api`、静态资源、缓存和上传大小配置与正式网关一致。
  - [x] 完成容器启动和健康检查；中国大陆公网 Chrome/Edge 验收按负责人确认等待备案后补验。

### M1-03：后端生产镜像与安全配置基线

- GitHub Issue：<https://github.com/soyia/copper_backend/issues/1>
- 当前状态：已完成，Issue 已关闭
- 建议仓库：`soyia/copper_backend`
- 责任面：后端、部署、安全
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M1-01
- 目标：建立可重复构建的后端生产镜像，移除生产弱默认值并形成配置校验。
- 对应路线图：M1 安装与初始化第 2、4、7 项。
- 验收条件：
  - [x] 使用锁定 Java/Maven 版本构建可追溯镜像。
  - [x] 生产启动不接受 `root/123456` 等弱默认数据库凭据。
  - [x] 数据库、端口、时区、日志、附件和备份配置缺失时给出可理解错误。
  - [x] 镜像以非特权用户运行，且不写入临时容器层保存业务数据。
  - [x] 完成容器启动、健康检查和 API 验收。

### M1-04：Compose、数据卷、密钥与健康检查编排

- GitHub Issue：<https://github.com/soyia/copper_web/issues/9>
- 当前状态：已完成，Issue 已关闭
- 建议仓库：由 M1-01 决定
- 责任面：部署、数据库、前端、后端
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M1-02、M1-03
- 目标：提供正式 Compose 编排，保证服务依赖、密钥、数据卷、网络和健康检查可靠。
- 对应路线图：M1 安装与初始化第 3、7 至 10 项，运维入口第 1 项。
- 验收条件：
  - [x] 数据库、附件、配置和备份使用明确持久卷。
  - [x] 数据库不默认暴露公网，密钥不提交仓库或写入镜像。
  - [x] 健康检查区分容器存活、应用就绪和数据库可用。
  - [x] 重复执行、停止、重启和容器重建不丢数据。
  - [x] 卸载默认保留数据，危险删除使用独立确认流程。

### M1-05：数据库 V1 基线与首次管理员初始化

- GitHub Issue：<https://github.com/soyia/copper_backend/issues/2>
- 当前状态：已完成，Issue 已关闭
- 补充运行时证据（2026-07-13）：`local-database` 已从 MySQL 8.0.42 原数据冷拷贝升级至 MySQL 8.4.10，升级前后业务基线一致且 1,341 张业务表检查无异常；该证据证明现有实例兼容 8.4 LTS，正式全新空库 V1 验收另由 M1-05 完成。
- 建议仓库：`soyia/copper_backend`
- 责任面：后端、数据库、安全
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M1-01、M1-03
- 目标：以验收通过的真实 `copper_v2` 数据库生成唯一 V1，首次启动自动初始化并安全创建首个管理员，不再保留历史开发 SQL 迁移链或要求手工挑选 SQL。
- 对应路线图：M1 安装与初始化第 5、6 项。
- 验收条件：
  - [x] 删除现有历史开发 SQL，并记录删除清单；不得重新排序后伪装为发布迁移。
  - [x] 从冻结数据库生成唯一 V1，且只包含结构和白名单系统种子数据。
  - [x] 全新 MySQL 8.4 LTS 数据库可以自动初始化到 V1，并与冻结数据库逐表比对一致。
  - [x] 首发后从 V2 起只执行缺失迁移，已发布 V1/V2 及后续迁移不可被覆盖修改。
  - [x] 迁移失败停止业务写入并给出版本、SQL和恢复提示。
  - [x] 首个管理员通过一次性安全流程创建，不存在公共默认密码。
  - [x] 验证空库初始化、重复启动和迁移失败恢复。

### M1-06：统一运维命令与交付文档

- GitHub Issue：<https://github.com/soyia/copper_web/issues/10>
- 当前状态：已完成，Issue 已关闭
- 建议仓库：由 M1-01 决定
- 责任面：部署、运维、交付
- 优先级：P1
- 初始状态：待规划
- 前置依赖：M1-04、M1-05
- 目标：提供统一的安装、启动、停止、重启、状态、日志、诊断、管理员初始化和卸载入口，以及标准安装和排错文档；M1 仅含同版本停机式基础备份/恢复。
- 对应路线图：M1 运维入口第 2、3 项。
- 验收条件：
  - [x] 命令入口名称、参数、返回码和失败提示一致。
  - [x] 安装文档覆盖前置检查、配置、安装、验证和交接。
  - [x] 排错文档覆盖端口、数据库、权限、磁盘、证书和容器启动失败。
  - [x] 非开发人员可根据输出判断当前状态和下一步处理方式。

### M1-07：空白云服务器端到端验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/11>
- 当前状态：已完成内部交付基线；中国大陆公网与 Chrome/Edge 访问待域名备案后补验
- 建议仓库：`soyia/copper_web`（跨仓库验收 Issue）
- 责任面：交付、部署、前端、后端、数据库
- 优先级：P0
- 初始状态：待规划
- 前置依赖：M1-02、M1-03、M1-04、M1-05、M1-06
- 目标：完成 M1 退出验收，证明发布包可以在全新标准云服务器上独立安装并在重启后保持数据。
- 对应路线图：M1 退出条件。
- 验收条件：
  - [x] 仅使用 RC3 发布包和公开交付文档完成离线安装，不修改源码、不手工执行写 SQL。
  - [x] 完成管理员一次性凭据改密、登录、主数据和采购至收付款最小业务链路验证。
  - [x] 服务器重启及容器重建后服务自动恢复，业务数据、附件、配置和密钥保持不变。
  - [x] 端口、数据库口令和目录权限三类故障均可按提示定位并恢复。
  - [x] 验收证据已回填至 `soyia/copper_deploy#6`；M1 状态已更新，公网例外明确记录。

## 5. M2 Issues

### M2-01：冻结备份包、导出格式与恢复安全架构

- GitHub Issue：<https://github.com/soyia/copper_web/issues/16>
- 当前状态：已完成
- 建议仓库：`soyia/copper_web`（跨仓库架构跟踪）
- 责任面：产品、部署、后端、数据库、安全
- 优先级：P0
- 前置依赖：M1-07 内部交付基线
- 目标：冻结灾难恢复备份、人可读业务导出、客户持钥、数据一致性、恢复安全和三仓责任边界。
- 验收条件：
  - [x] 区分 `copper-backup/v1` 与 `copper-export/v1`，禁止列表导出冒充灾难恢复。
  - [x] 冻结备份包、校验、加密、客户持钥和失败原子性。
  - [x] 冻结短暂停写的一致性边界、旁路恢复和恢复后业务校验。
  - [x] 冻结开放导出格式、十进制精度、来源关系、附件和审计规则。
  - [x] 明确 `copper_web`、`copper_backend`、`copper_deploy` 责任和 M2 实施顺序。
  - [x] 架构文档通过 PR 合入 GitHub `main`。

### M2-02：实现一致性整站备份、校验与保留策略

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/7>
- 完成 PR：<https://github.com/soyia/copper_deploy/pull/11>
- 当前状态：已完成
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、数据库、运维
- 优先级：P0
- 前置依赖：M2-01
- 目标：实现数据库、附件和允许清单配置同一边界的 `copper-backup/v1`，支持原子发布、校验、计划任务和保留策略。
- 验收条件：
  - [x] 受控停写并等待事务/上传结束，生成数据库、附件、配置和验证清单。
  - [x] 生成格式版本、内外层 SHA-256，损坏或不完整包可被识别。
  - [x] 失败不发布成功包、不更新成功时间、不删除旧成功备份。
  - [x] 覆盖重复执行、空间不足、中断、校验失败和服务恢复测试。
  - [x] 在 MySQL 8.4.10 与数据库引用附件卷形成可复核样例、大小和耗时证据。

### M2-03：实现旁路恢复、完整性验证与失败保护

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/8>
- 完成 PR：<https://github.com/soyia/copper_deploy/pull/12>
- 当前状态：已完成
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、数据库、运维、交付
- 优先级：P0
- 前置依赖：M2-01、M2-02
- 目标：恢复到新数据库和暂存数据目录，验证通过后才切换；任何失败不覆盖当前可用系统。
- 验收条件：
  - [x] 完成格式、密钥、版本、容量、权限、端口和空目标检查。
  - [x] 自动比对结构、记录数、库存、应收应付、资金、核销、审计和附件哈希。
  - [x] 隔离启动和验证通过后才允许切换正式数据指向。
  - [x] 错误密钥、损坏包、空间不足、恢复中断和版本不兼容均安全失败。
  - [x] `local-database` 上完成 MySQL 8.4.10、88 张表、切换失败自动恢复、正常切换和可验证回滚实测；共享容器不变且旁路现场无残留。

### M2-04：实现完整、人可读、可审计的业务导出

- GitHub Issue：<https://github.com/soyia/copper_backend/issues/10>
- 完成 PR：<https://github.com/soyia/copper_backend/pull/11>、<https://github.com/soyia/copper_web/pull/21>
- 当前状态：已完成
- 建议仓库：`soyia/copper_backend`，前端配套在 `copper_web`
- 责任面：后端、前端、产品、审计
- 优先级：P0
- 前置依赖：M2-01；执行排期在 M2-03 之后
- 目标：让客户用通用软件读取完整主数据、进销存、应收应付、资金、核销、审计和原始附件。
- 验收条件：
  - [x] 后端按权限和完整范围执行，禁止前端翻页拼接全量数据。
  - [x] CSV、字段字典、筛选清单、稳定主键、来源关系和枚举说明完整。
  - [x] 金额、数量、单价和税额使用十进制字符串，时间保留时区和业务日期。
  - [x] 附件保持原始字节、稳定路径、业务关联和 SHA-256。
  - [x] 导出任务可追踪、可重试并进入审计日志。

### M2-05：实现本地、NAS、S3 兼容存储目标与容量治理

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/10>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`，容量展示配套在 `copper_backend`/`copper_web`
- 责任面：部署、存储、后端、前端
- 优先级：P1
- 前置依赖：M2-01、M2-02
- 目标：同一备份包可写入客户本地目录、系统挂载 NAS 和 S3 兼容对象存储，并展示真实占用和容量风险。
- 验收条件：
  - [x] 三类目标支持写入、读取、列举、校验、原子发布和受控删除。
  - [x] 同一备份包字节内容和 SHA-256 不因目标不同而变化。
  - [x] 明确 NAS 挂载、对象存储配额、凭据、断线和第三方费用责任。
  - [x] 容量采用预警、严重和危险分级，不展示 Copper 人为套餐空间。

### M2-06：收口客户持钥、自动调度、告警与密钥生命周期

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/9>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`
- 责任面：安全、运维、交付
- 优先级：P0
- 前置依赖：M2-02、M2-05
- 目标：让客户独立持有恢复私钥，完成调度、告警、密钥交付、轮换和销毁闭环。
- 验收条件：
  - [x] 生产备份强制使用公开 `age` 格式，不提供未加密备份模式；服务器仅保存接收公钥。
  - [x] 私钥/口令不进入镜像、Git、普通配置和日志，交付后客户离线持有。
  - [x] 轮换不静默破坏历史备份，恢复测试、双人交接、退役和销毁均有证据。
  - [x] 连续失败、空间不足、超时和通知失败均有隐私安全的本地与签名通知留痕。

### M2-07：空白服务器恢复、跨环境迁移与自由导出最终验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/17>
- 当前状态：已完成（2026-07-14），M2 退出验收通过
- 建议仓库：`soyia/copper_web`（跨仓库退出验收）
- 责任面：交付、部署、前端、后端、数据库、安全
- 优先级：P0
- 前置依赖：M2-02、M2-03、M2-04、M2-05、M2-06
- 目标：在空白服务器恢复出可登录、可查、可继续记账的 Copper，并证明非开发人员可独立读取导出和迁移到自有存储。
- 验收条件：
  - [x] 恢复前后结构、记录数、库存、应收应付、预收预付、资金、核销和审计一致。
  - [x] 附件数量、字节数、路径和 SHA-256 一致。
  - [x] 恢复后完整采购至收付款链路可以继续运行。
  - [x] 至少完成两个本地/客户云/换机迁移方向和三个存储目标验收。
  - [x] 非开发人员仅依赖发布包与文档即可恢复，并用通用软件读取导出。
  - [x] 回填耗时、磁盘峰值、失败次数和人工成本后再更新 M2 完成状态。

验收证据：`soyia/copper_deploy#16` 已合入 `main`，正式发布 `v0.1.0-m2-rc.2`。Ubuntu 24.04 x86_64 空白主机以离线固定镜像完成 `local-database -> 客户云` 和 `客户云 -> 空白替换环境` 两个方向；两次旁路恢复均为 35 秒，一致性云站备份 16 秒，旁路目录峰值 254,382,850 字节。恢复后完成采购、入库、库存、销售、出库、应收应付和收付款闭环，库存 3 kg、应收应付均结清；89 张表、Flyway V1/V2/V3、附件及历史审核快照路径/字节/SHA-256 一致。正式发布包 SHA-256 为 `e22ea70df0aafc1a2a4bae2446f140e72672c4b5ced8b21393b3d26c91136f29`。验收后按授权使用产品危险删除流程清理现场，复核为 0 Copper 数据目录、容器、镜像、网络、卷和监听端口；公网 TLS/浏览器访问因中国大陆域名备案要求留待备案后补验，不阻断 M2 数据自主能力完成。

## 6. M3 Issues

### M3-01：统一版本号、发布节奏和兼容性规则

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/17>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、后端、数据库、产品
- 优先级：P0
- 前置依赖：M2-07
- 目标：为客户产品版本、组件身份、数据库 schema、备份/导出格式、RC/稳定版节奏、直接升级来源和失败回退建立唯一且机器可检查的规则。
- 验收条件：
  - [x] 新产品版本采用 `MAJOR.MINOR.PATCH[-rc.N]`，内部里程碑不再进入新版本号。
  - [x] `copper_deploy` 发布清单成为客户产品版本和组件组合的唯一权威来源。
  - [x] Web/Backend 构建身份、工程版本、Flyway、备份和导出格式保持独立技术口径。
  - [x] 冻结 RC、稳定版、紧急修复节奏和客户自主选择升级规则。
  - [x] 目标发布只允许升级矩阵中逐项实测的来源；未列出即不支持直接升级。
  - [x] schema/data 变化禁止旧镜像或逆向 SQL 假回滚，必须使用保留旁路或已验证备份。
  - [x] 形成 `copper-release/v1` 必填字段、`copper-upgrade-matrix/v1` 和静态契约测试。
  - [x] 历史 M1/M2 标签保持不变，`0.2.0-rc.1` 仅预留，`1.0.0` 继续受 M7 门槛约束。

完成证据：`soyia/copper_deploy#18` 已评审合入 `main`；决策文档为 `docs/M3_VERSIONING_AND_COMPATIBILITY.md`，机器策略为 `config/release-policy.env`，30 个最终发布清单必填字段位于 `config/release-manifest-v1.fields`。版本正反例、历史清单不可变和兼容/回退红线由 `tests/m3-version-policy.sh` 验证，并已接入现有静态契约。

### M3-02：发布契约、升级预检和升级前已验证备份

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/19>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、数据库、运维
- 优先级：P0
- 前置依赖：M3-01
- 目标：把目标版本、精确来源版本/schema、资源门槛、恢复密钥实证和新的一致性备份绑定成可重复验证的升级准备证据，但不在本项执行迁移或切换。
- 验收条件：
  - [x] 严格生成并验证 `copper-release/v1` 与 `copper-upgrade-matrix/v1`。
  - [x] 只接受矩阵明确列出的当前产品版本和 Flyway/schema 来源。
  - [x] `upgrade preflight` 检查 MySQL 8.4、三层健康、CPU/内存/磁盘、首次凭据、恢复密钥实证和最近备份状态。
  - [x] 任一预检失败不得创建备份、改 schema、改镜像或进入可升级状态。
  - [x] `upgrade prepare` 创建新的加密一致性整站备份并执行正式 `backup verify`。
  - [x] 发布清单、矩阵、Deploy 包、发布说明、ACME 包和实际备份绑定到 root-only `PREPARED` 记录。
  - [x] 失败准备恢复三层健康且不写 `PREPARED`；未消费状态拒绝重复准备。
  - [x] `local-database` MySQL 8.4.10 隔离验收通过，共享容器与宿主机 8191 不受影响。
  - [x] PR 评审合入 GitHub `main`，Issue 关闭并回填证据。

完成证据：`soyia/copper_deploy#20` 已评审合入 `main`，Issue `soyia/copper_deploy#19` 已关闭并全部勾选。全量 33 项 Python 测试及静态、CLI、M3 版本策略、M2 备份契约测试通过；`local-database` 完整隔离验收 94 秒、MySQL 8.4.10、Flyway schema 3 不变，空间不足、待交付初始凭据、缺少恢复证明、最近备份失败、停机后故障恢复和重复准备拒绝全部通过。未执行迁移、镜像切换或创建 `0.2.0-rc.1` Tag/Release。

### M3-03：隔离迁移、失败保护与人工切换状态机

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/21>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、数据库、运维
- 优先级：P0
- 前置依赖：M3-02
- 目标：精确消费 M3-02 的 `PREPARED`，先在恢复旁路运行目标 Flyway 和完整数据合同，再由客户人工切换；任一失败保持或恢复来源站一致可用。
- 验收条件：
  - [x] PREPARED、发布清单和升级前实际加密备份被一次性精确绑定并消费。
  - [x] 目标 Flyway 只在独立 Compose、MySQL 与附件旁路执行，不修改当前站 schema。
  - [x] 目标 schema、完整 Flyway 历史、来源表、经营余额、附件和外键合同全部通过后才允许切换。
  - [x] 迁移失败保持当前站运行；验证后写入旁路会进入 `DIRTY` 并拒绝切换。
  - [x] 可信终端人工确认后，原子交换数据、附件、不可变镜像引用和版本配置。
  - [x] 切换失败自动恢复来源数据与配置，通过旧站合同后才恢复 Web；无法证明恢复时进入 `RECOVERY_FAILED`。
  - [x] `SWITCHED` 保留来源回滚槽，人工 rollback 后重新验证来源站并支持安全 discard。
  - [x] 非法、重复、并发状态转换均被拒绝，root-only 状态与事件证据完整保留。
  - [x] `local-database` MySQL 8.4.10 覆盖成功、迁移失败、写脏、切换失败和回滚路径，8191 与共享容器不受影响。
  - [x] PR 评审合入 GitHub `main`，Issue 关闭并回填证据。

完成证据：`soyia/copper_deploy#22` 已 squash 合入 `main`，Issue `soyia/copper_deploy#21` 已关闭并全部勾选。36 项 Python 单元测试及静态、CLI、备份契约通过；`local-database` 完整运行 395 秒，MySQL 8.4.10、隔离 Flyway、迁移失败保持当前站、写脏拒绝、切换故障恢复来源、正常切换与回滚、schema/经营余额/附件/外键合同全部通过。没有创建真实 `0.2.0-rc.1` Tag/Release，也未交付联网或离线升级介质。

### M3-04：联网/离线不可变发布物与升级介质

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/23>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、运维、安全
- 优先级：P0
- 前置依赖：M3-03
- 目标：把目标发布清单、Deploy 包、升级矩阵、发布说明、ACME 和三张不可变镜像以联网或完全离线方式交付到客户服务器，并在不触发升级的前提下形成可复核 READY。
- 验收条件：
  - [x] `copper-delivery/v1` 精确绑定产品版本、模式、平台、全部文件名/字节数/SHA-256 和三张镜像摘要。
  - [x] 联网介质使用独立索引摘要与 HTTPS，先完整下载校验再拉取精确镜像摘要。
  - [x] 离线介质包含受索引约束的 amd64 镜像归档，目标机不执行 `curl` 或 `docker pull`。
  - [x] root-only `copper-delivery-ready/v1` 原子落地；同内容幂等，不同内容拒绝覆盖。
  - [x] 篡改、未知文件、符号链接、不安全权限、摘要/拉取/导入失败均不得产生 READY。
  - [x] `release status` 复核文件、权限、本机镜像 ID/架构和正式发布摘要引用，镜像删除后 READY 立即失效。
  - [x] 生产升级清单必须位于已验证 READY 目录，交付命令本身不执行 prepare/migrate/switch。
  - [x] `local-database` 真实 Docker 验证联网模拟、完全离线、失败保护、幂等和主机隔离。
  - [x] PR 评审合入 GitHub `main`，Issue 关闭并回填证据。

完成证据：`soyia/copper_deploy#24` 已 squash 合入 `main`，Issue `soyia/copper_deploy#23` 已关闭并全部勾选。41 项 Python 测试及 CLI、备份、M3 版本策略、静态契约通过；`local-database` 最终运行 3 秒，错误索引摘要、联网拉取失败、离线无仓库导入、无效 Docker 归档、重复暂存和 READY 镜像删除检测全部通过，宿主机 8191、共享容器和正式配置不变。未创建真实 `0.2.0-rc.1` 或正式 amd64 介质。

### M3-05：正式 x86_64 真实结构变化升级与空白恢复验收

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/25>
- 当前状态：已完成（2026-07-14），M3 退出验收通过
- 完成 PR：Backend <https://github.com/soyia/copper_backend/pull/13>；Deploy <https://github.com/soyia/copper_deploy/pull/26> 至 <https://github.com/soyia/copper_deploy/pull/33>
- 正式 Release：<https://github.com/soyia/copper_deploy/releases/tag/v0.2.0-rc.1>
- 责任面：发布、部署、后端、数据库、运维、交付
- 优先级：P0
- 前置依赖：M3-04
- 目标：使用正式 amd64 离线介质完成 schema 3 至 schema 4 的真实升级、故障恢复、继续经营和当前版本独立空白站恢复。
- 验收条件：
  - [x] Backend 只追加不可变 Flyway V4，V1 至 V3 历史不变。
  - [x] 正式发布清单、升级矩阵、Deploy 包、Release 和离线 amd64 镜像摘要相互绑定。
  - [x] `after-switch-audit` 故障后恢复原站，半迁移 Web 未放行。
  - [x] 正常升级到 `0.2.0-rc.1/schema 4` 后可登录、可查、可继续经营。
  - [x] 当前版本备份恢复到完全独立的空白站，经营汇总、附件、Flyway、审计和表数量一致。
  - [x] 现场按危险删除流程清理，8191 始终未使用。

完成证据：`soyia/copper_deploy#25` 已关闭并全部勾选，最终报告通过 `soyia/copper_deploy#33` 合入 `main`。正式离线索引 SHA-256 为 `db1c85056d67f5c951d9309efa0311b1c622b542f648ee65746ab578c951f3c0`，amd64 离线镜像包 SHA-256 为 `c1cc5de10f1af836f6fdbf8526a077276ad7bb12572d8b6f060af9344602bd8e`，证据归档 SHA-256 为 `c981054e82b6b47e5003d36bf9aa38cb3b5c61bb9558100f6273388ae266ebe1`。

## 7. M4 Issues

M4 架构权威文档为 `docs/self-hosting/M4_SECURITY_MONITORING_DR_ARCHITECTURE.md`，总跟踪 Issue 为 <https://github.com/soyia/copper_web/issues/31>。

### M4-01：冻结安全、监控、容灾与离线自主架构

- GitHub Issue：<https://github.com/soyia/copper_web/issues/32>
- 当前状态：已完成（2026-07-14）
- 建议仓库：`soyia/copper_web`（跨仓库架构跟踪）
- 责任面：产品、安全、运维、后端、前端、数据库、部署
- 优先级：P0
- 前置依赖：M3-05
- 目标：冻结信任边界、威胁模型、数据分级、审计矩阵、健康信号、RPO/RTO 决策边界、故障不变量和完全离线验收合同。
- 验收条件：
  - [x] 明确正式 HTTPS 拓扑、三仓责任和客户/第三方边界。
  - [x] 冻结身份会话、授权数据范围、高风险动作审计和秘密生命周期。
  - [x] 冻结日志脱敏、SBOM、漏洞治理、外联清单和遥测默认关闭规则。
  - [x] 冻结四层健康、容量写保护、故障矩阵和经营财务不变量。
  - [x] 建立 M4-02 至 M4-07 GitHub Issues 及依赖顺序。
  - [x] 通过 PR 评审合入 GitHub `main`，回填证据并关闭 Issue。

### M4-02：登录、会话、权限、数据范围与危险操作审计

- GitHub Issue：<https://github.com/soyia/copper_backend/issues/15>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_backend`，Web/Deploy 配套在各自仓库
- 责任面：后端、前端、安全、审计
- 优先级：P0
- 前置依赖：M4-01
- 目标：后端统一执行身份、会话、授权、数据范围和高风险审计，防止越权和无痕修改。

### M4-03：生产密钥、日志脱敏、SBOM 与漏洞治理

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/36>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_deploy`
- 责任面：安全、发布、部署、前端、后端
- 优先级：P0
- 前置依赖：M4-01
- 目标：建立秘密生成/交付/轮换/销毁、统一日志脱敏、发布绑定 SBOM 和漏洞修复闭环。

### M4-04：应用、数据库、磁盘、备份、证书与时间监控

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/37>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、运维、后端、前端
- 优先级：P0
- 前置依赖：M4-01
- 目标：让客户独立判断存活、就绪、可写、可恢复及容量、证书和系统时间风险。

### M4-05：日志轮转、RPO/RTO 与故障处置规范

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/34>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_deploy`
- 责任面：运维、部署、产品、商业
- 优先级：P0
- 前置依赖：M4-01、M4-04、M2 备份恢复能力
- 目标：形成日志容量保护、可兑现 RPO/RTO、服务边界和故障处置清单，并复算服务成本。

### M4-06：数据库、磁盘、备份、证书与异常断电故障演练

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/35>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、数据库、运维、安全、交付
- 优先级：P0
- 前置依赖：M4-02、M4-03、M4-04、M4-05
- 目标：真实注入五类故障和异常终止，证明安全失败、可靠恢复及经营财务不变量一致。

### M4-07：完全断网运行与空白 x86_64 最终验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/33>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_web`（跨仓库退出验收）
- 责任面：交付、部署、安全、运维、前端、后端、数据库
- 优先级：P0
- 前置依赖：M4-02 至 M4-06
- 目标：在新加坡 AWS Ubuntu 24.04 x86_64 空白主机完成真实 TLS、完全断网、第三方降级、故障恢复和客户自助状态查看验收。
- 环境边界：公开证据不记录主机 IP、SSH 私钥路径或凭据；容器不在开发者 Mac 运行；宿主机只暴露受支持 HTTPS 入口。
- 验收条件：
  - [x] 从合入 GitHub `main` 的三仓提交生成 `0.3.0-rc.4` 安全候选和正式离线介质，组件、源码、工作流、镜像和迁移集合均由不可变摘要绑定。
  - [x] 空白 Ubuntu 24.04 x86_64 主机只使用离线介质完成安装，真实公开 CA TLS、Cookie 与六类安全响应头全部失败关闭验收。
  - [x] 阻断运行容器访问公共互联网后，采购、入库、库存、销售、出库、客户回款、供应商付款和查询完整通过。
  - [x] ACME、客户 S3、客户 Webhook 和联网交付不可用时明确降级；许可证心跳、强制遥测和远程停用开关均未成为核心业务依赖。
  - [x] 客户可在产品界面和运维命令中独立查看磁盘、数据库、附件、备份、证书、时间和健康状态。
  - [x] 同一 Web、Backend、MySQL 镜像重新通过 M4-06 六类故障、经营财务不变量和可信时间恢复验收。
  - [x] 一次性管理员凭据使用后销毁；浏览器交接凭据在浏览器内存、本机临时文件和服务器运行目录销毁；测试站点使用产品危险删除流程清理。
  - [x] 最终脱敏报告、路线图和价格服务边界通过 PR 合入 GitHub `main`。

完成证据：Deploy `soyia/copper_deploy#59`、`#60` 已合入 `main`，最终候选 Deploy 提交为 `6148ccdf5e1bb4562fff43424e5eb0f09a4991b4`。离线索引 SHA-256 为 `a8a3afc916f3151d4983611036061e479c46c3a541841432a442c3eaa682ae59`，安全证据包 SHA-256 为 `db31ac9945944450431f341632fc82ac5a5c66258d3fcac8efd0e42f636e4b0f`，空白机最终证据清单 SHA-256 为 `4ac89fc5162bd3a8f068ac9f07135b04fa1afe9187092168ef54dd9a981e2461`，M4-06 最终复验证据清单 SHA-256 为 `1c1bd9e3bd3b88a065cb0b61067d5bed351548813b967338d8e3cd169d303b7d`。详见 `docs/M4_FINAL_OFFLINE_ACCEPTANCE_REPORT.md`。

## 8. M5 Issues

M5 架构权威文档为 `docs/self-hosting/M5_LOCAL_DEVICE_LAN_NAS_ARCHITECTURE.md`，总跟踪 Issue 为 <https://github.com/soyia/copper_web/issues/41>。M1 至 M4 的云端 Ubuntu x86_64 证据不能替代本地平台验收；M5-07 通过前，本地目标均不得标记为正式支持。M6 只依赖已经完成的 M1 至 M4，因此可以在 M5 延期门槛仍打开时开始，但不得把并行推进解释为 M5 已通过或存在本地 `Certified` 平台。

### M5-01：冻结本地部署支持矩阵与交付架构

- GitHub Issue：<https://github.com/soyia/copper_web/issues/42>
- 当前状态：已完成（2026-07-15）
- 建议仓库：`soyia/copper_web`（跨仓库架构跟踪）
- 责任面：产品、部署、前端、后端、数据库、安全、交付
- 优先级：P0
- 前置依赖：M4-07
- 目标：冻结支持分级、候选平台顺序、LAN/TLS、存储、休眠、外设、真实设备晋级和低价标准服务边界。
- 验收条件：
  - [x] 定义 Certified、Candidate、Deferred、Unsupported 四级口径。
  - [x] 冻结 Ubuntu 本地主机、Synology、Windows 宿主上的 Ubuntu x86_64 虚拟机、macOS Apple Silicon 候选顺序和初始资源门槛；取消 Windows 原生/WSL2 安装。
  - [x] 冻结局域网 HTTPS、稳定地址、防火墙和内部端口边界。
  - [x] 冻结主数据本地块存储、外接盘/NAS 备份和单盘风险边界。
  - [x] 冻结浏览器打印、键盘模拟扫码、上传、并发和休眠边界。
  - [x] 建立 M5-02 至 M5-07 Issues、依赖和真实设备晋级证据门槛。
  - [x] 明确 `¥649 起` 标准范围及硬件、许可、复杂网络和专有外设排除项。

### M5-02：正式多架构镜像与离线发行合同

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/61>
- 当前状态：已完成（Deploy PR #65）
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、前端、后端、安全
- 优先级：P0
- 前置依赖：M5-01
- 目标：在不回归 M4 amd64 路径的前提下，建立 linux/amd64 与 linux/arm64 的不可变在线/离线发行能力，并用真实两架构主机验证。

### M5-03：本地主机预检、安装、自启动与数据目录

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/62>
- 当前状态：已完成（Deploy PR #66；Ubuntu 24.04 x86_64）
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、数据库、运维、交付
- 优先级：P0
- 前置依赖：M5-01、M5-02
- 目标：安装前只读检查 OS、架构、资源、运行时、端口、文件系统、地址、启动和备份目标；不支持时在写数据前失败关闭。

完成证据：Deploy PR `#66` 已合入 `main`，Issue `#62` 已关闭。Ubuntu 24.04 x86_64 完成空白预检、正式安装、systemd 主机重启、四层监控、卸载/`managed-reinstall`、原账户/密钥/加密备份复验和最终清理；真实 ARM64 在写入前返回退出码 3。Windows 只作为 Ubuntu x86_64 虚拟机宿主，不建设原生/WSL2/Docker Desktop 安装器。

### M5-04：局域网访问、HTTPS、名称解析与防火墙

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/64>
- 当前状态：Closed / not planned for 1.0（工程实现保留，实体 LAN 门槛未通过；M7-06 移出首发）
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、安全、网络、前端
- 优先级：P0
- 前置依赖：M5-03
- 目标：提供稳定私网地址、正式 HTTPS、证书信任/轮换和最小端口暴露；公网远程访问不混入标准局域网安装。

阶段证据：Deploy PR `#67` 已合入 `main`。Ubuntu 24.04 x86_64 / Docker 29.1.3 / Compose 5.1.4 已完成空白安装、客户 CA、受控 DNS、RFC1918 精确绑定、`DOCKER-USER` 允许/拒绝来源、80 到 HTTPS 308、3306/8191/恢复端口外部不可达、整机重启、地址迁移、叶证书轮换、Cookie、完整导出下载、加密备份和永久清理。真实验收修复了回程包误杀、301/308、改密会话语义、Caddy 未重载证书和验收秘密残留。Windows 宿主 VM、独立本地主机、主机休眠和 LAN 地址变化未验收；M7-06 将其移出 `1.0`，Issue `#64` 按“不计划”关闭，不得解释为 M5-04 退出通过。

### M5-05：本地盘、外接盘与 NAS 备份恢复迁移

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/63>
- 当前状态：Closed / not planned for 1.0（Deploy PR #68 工程实现保留；实体外接盘、远程 NAS、设备 B 和离线恢复材料未通过，M7-06 移出首发）
- 建议仓库：`soyia/copper_deploy`
- 责任面：部署、数据库、运维、交付
- 优先级：P0
- 前置依赖：M5-03、M2 备份恢复能力；可与 M5-04 并行
- 目标：验证主数据与备份故障域分离、外接盘/NAS 挂载身份、断盘失败保护、空白恢复和跨设备迁移。

阶段证据：Deploy PR `#68` 已合入 `main`。已实现 `external` 目标、`storage enroll external|nas` 稳定身份、本机同盘风险提示和缺盘/换盘/错误共享失败关闭。135 项 Python、静态/CLI 契约通过；Ubuntu 24.04 x86_64 工程环境以 loop-backed ext4 和 localhost NFS4 完成 90 张表、附件、财务与审计数据备份恢复，发布中断、缺盘、换盘、只读和满盘均未形成成功假象，从 NAS 取回的备份完成 SHA-256/字节数校验、旁路切换和回滚。工程介质不得冒充真实独立外接盘、远程 NAS 或独立设备 B；M7-06 将其移出 `1.0`，Issue `#63` 按“不计划”关闭，不得勾选 M5-05 退出。

### M5-06：局域网多人、打印、扫码、上传与离线体验验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/43>
- 当前状态：已完成（2026-07-16，当前收敛范围；M5-07 和延期实体门槛不计为通过）
- 建议仓库：`soyia/copper_web`，Backend/Deploy 配套在各自仓库
- 责任面：前端、后端、部署、交付、外设联调
- 优先级：P0
- 前置依赖：M5-03、M5-04、M5-05
- 目标：以真实客户端和打印设备验证权限、并发、打印、上传、短断网及客户端休眠恢复体验；HID 扫码和本地主机休眠保留为延期门槛。

完成证据：Web 已实现局域网连接状态与失败语义、四类业务表单会话草稿、采购/入库 A4 打印、销售/出库针式打印、HID/IME 连续扫码、重复扫码累计、附件失败保留/重试和 SHA-256 对账。Ubuntu 24.04 x86_64 隔离站点完成 5 个有效会话、只读角色写入 HTTP 403、两路独立会话并发写入、安全审计、附件幂等、Backend 中断/恢复和真实 HTTPS 浏览器验收。Mac + 手机完成独立登录、改密、权限隔离、相邻双端写入、真实图片上传、客户端短断网恢复及 Mac 休眠观察。HP Laser MFP 136w 使用 Mac Chrome 完成入库单实体 A4 单页及 24 行连续两页打印；第 2 页重复表头并完整包含第 16 至 24 行、合计、备注和签字区，无截断、横向溢出或空白尾页。采购订单共享 A4 基础样式并有独立工程契约，本轮实体代表性单据为入库单。Issue `#43` 当前范围可以关闭并开始 M6；独立 Ubuntu 本地主机、HID 扫码枪、针式打印机、Windows 宿主 VM、主机休眠和地址变化均延期，不得记作已验收。

### M5-07：真实设备全生命周期最终验收与成本复核

- GitHub Issue：<https://github.com/soyia/copper_web/issues/44>
- 当前状态：Closed / not planned for 1.0（实体门槛未通过；M7-06 移出首发，未来须新建版本化任务）
- 建议仓库：`soyia/copper_web`（跨仓库退出验收）
- 责任面：产品、交付、部署、安全、运维、前端、后端、数据库
- 优先级：P0
- 前置依赖：M5-02 至 M5-06
- 目标：在精确真实设备组合完成全新安装、重启/休眠、升级/回滚、备份/恢复、跨设备迁移、LAN、外设、安全和成本验收，决定 Certified 兼容清单。
- M5 退出门槛：
  - [ ] 至少一个本地常开主机组合晋级 Certified。
  - [ ] 每个 Certified 目标都有精确版本、硬件、限制和脱敏证据。
  - [ ] 客户可迁移到另一受支持设备且业务、附件和审计历史不变。
  - [ ] `¥649 起` 标准交付的实际人工、失败率和第三方成本可持续。
  - [ ] 路线图、价格目录、兼容清单和客户验收单口径一致。

范围结论：M7-06 已选择“移出 `1.0` 首发”，Web Issue `#44` 按“不计划”关闭。全部退出门槛仍保持未勾选，没有本地 `Certified` 结果；未来恢复本地交付必须新建版本化任务，不能重开旧 Issue 后沿用过期证据。

### M5-R1：可选 Cloudflare Tunnel 远程访问工程基线

- GitHub 实现：<https://github.com/soyia/copper_deploy/pull/138>、<https://github.com/soyia/copper_deploy/pull/139>
- 当前状态：内部工程实现已合并；未形成 M5 退出、本地 Certified、`1.0` 支持或客户交付结论
- 建议仓库：`soyia/copper_deploy`；正式重启本地产品线时另建版本化验收 Issue
- 责任面：部署、安全、运维、交付、网络与客户合规
- 一期提供者：仅 Cloudflare Tunnel；花生壳和 VPN 不实现、不报价、不承诺
- 已完成：LAN 站点的 `configure / enable / status / rotate-token / disable / remove`、维护和诊断入口、root-only Token、备份/日志/状态排除、最小出站网络、永久删除联动及云端资源人工清理提示。
- 操作入口：v0.4.5 Mac 一站式维护菜单先选择站点，再选择“互联网远程访问（Cloudflare，可选）”；服务器复用同一子菜单。普通用户不复制 `copperctl` 子命令，失败后留在菜单重试，本地移除由程序绑定当前站点并二次选择。
- 正式前置：
  - [ ] 客户自有常开 LAN 主机、真实公网终端、无公网 IP/无路由器入站端口完成生命周期和故障恢复验收。
  - [ ] Cloudflare Access 默认拒绝、授权用户/组、源站不可达、Token 泄露与轮换回滚完成安全验收。
  - [ ] 中国大陆真实客户网络完成质量、时延、断线恢复、数据路径、域名/备案及适用合规复核。
  - [ ] 冻结客户持有 Cloudflare 账号和域名、计划/SLA、支持额度、故障升级、云端删除、退出迁移与正式价格。
- 退出规则：以上门槛全部通过并更新兼容清单、手册、合同和路线图前，只能用于 `INTERNAL_REHEARSAL_ONLY`，不得改变 `FIRST_RELEASE_CLOUD_ONLY` 或对外宣称本地远程访问已正式支持。

## 9. M6 Issues

M6 架构权威文档为 `docs/self-hosting/M6_SOURCE_DELIVERY_OPEN_SOURCE_ARCHITECTURE.md`，总跟踪 Issue 为 <https://github.com/soyia/copper_web/issues/57>。M6 只依赖已完成的 M1 至 M4，不等待延期的 M5-07；并行推进不表示 M5 已完成，也不产生本地 `Certified` 平台。GitHub `origin/main` 是当前唯一权威源，本里程碑不读取、写入或同步 Gitee。

### M6-01：冻结客户源码交付边界与公开开源准备架构

- GitHub Issue：<https://github.com/soyia/copper_web/issues/58>
- 当前状态：已完成（Web PR #62）
- 建议仓库：`soyia/copper_web`（跨仓库架构跟踪）
- 责任面：产品、前端、后端、部署、数据库、安全、发布、法务
- 优先级：P0
- 前置依赖：M1 至 M4
- 目标：冻结完整核心范围、客户源码包合同、版本绑定、GitHub 权威源、安全/版权/许可证门槛及 M6-02 至 M6-07 的顺序。
- 验收条件：
  - [x] 明确 `copper_web`、`copper_backend`、`copper_deploy` 是完整核心系统。
  - [x] 冻结运行版本与三仓提交、迁移、镜像、依赖、发布说明和校验值的绑定合同。
  - [x] 冻结源码包包含项、排除项、客户永久内部自主权和秘密/客户数据零容忍规则。
  - [x] 明确 GitHub `origin/main` 为当前唯一权威源；本任务不操作 Gitee。
  - [x] 冻结第三方权利、SBOM、许可证、贡献和商标专业复核门槛。
  - [x] 创建总跟踪与 M6-01 至 M6-07 七个子任务。
  - [x] 架构文档、路线图和任务清单通过 Web PR `#62` 合入 `main`，Issue 回填证据并关闭。

### M6-02：三仓提交历史、秘密、客户数据与隐私审计

- GitHub Issue：<https://github.com/soyia/copper_web/issues/59>
- 当前状态：进行中（普通 GitHub 引用、标签和 Release 已清理，三仓 `main` 严格审计为 `PASS`；GitHub 托管 PR refs 仍为 `BLOCK`）
- 建议仓库：`soyia/copper_web`（跨仓库审计跟踪）
- 责任面：安全、发布、前端、后端、部署、数据保护
- 优先级：P0
- 前置依赖：M6-01
- 目标：扫描三仓当前树、完整历史、标签和 Release，处置密钥、客户数据、备份、个人信息和受限验收资产，并建立 PR/Release 失败门禁。
- 工程与验收：
  - [x] 建立不输出命中原文/原始路径的当前树、完整可达 Git 历史、commit/tag 与 Git 身份扫描器。
  - [x] 浅克隆、大对象、读取失败、新泄漏和严格 Release 失败关闭。
  - [x] Web、Backend、Deploy PR 门禁和 Deploy 发布前 `strict` 门禁均已通过 PR 合入；普通引用 baseline 已清空，不授予任何豁免。
  - [x] 宏泰网页、图片和抓取脚本从 Web 当前树及新普通 Git 历史排除；私有完整旧史 bundle 已校验并限制为 `0600`。
  - [x] 盘点并删除 Deploy 既有 6 个 RC 标签、5 个 prerelease 和 22 个资产；三仓普通 `main` 全新严格审计为 `PASS`。
  - [x] 已提交 GitHub Support case `#4575811`，请求清理 115 个平台托管历史 PR head 及缓存；case 仍为 Open，不能用提交申请替代清理结果。
  - [ ] GitHub Support 完成平台清理并在隔离复扫中消除 48 项；不能用 baseline 或例外关闭。
  - [ ] 平台清理后重新严格扫描 PR refs、待发布 Release 候选和客户源码包，取得完整 `PASS`。

阶段证据：`docs/self-hosting/M6_02_SECURITY_PRIVACY_AUDIT.md`。首次广口径扫描形成 106 项；PR `#63` 后复扫为 85 项。随后三仓都完成授权单根历史重置，Deploy 旧 RC 标签/Release/资产已删除，普通远端分支各只保留 `main`；三个全新完整单分支 GitHub 克隆严格扫描为 0。显式抓取 GitHub 管理的 115 个 PR head 后仍有 48 项历史阻塞（Web 18、Backend 26、Deploy 4）。支持申请模板见 `docs/self-hosting/M6_02_GITHUB_PLATFORM_REMEDIATION_REQUEST.md`，Issue `#59` 必须保持打开。

### M6-03：第三方代码、内容版权、许可证清单与 SBOM 审计

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/69>
- 当前状态：工程完成（2026-07-21；`0.4.0-rc.6` 真实权利包双端离线验证为 `ENGINEERING_PASS`，M6-05 法律复核仍为 `FORMAL_RELEASE_BLOCKED`）
- 建议仓库：`soyia/copper_deploy`（跨仓库制品与审计工具）
- 责任面：发布、安全、法务、前端、后端、部署、内容资产
- 优先级：P0
- 前置依赖：M6-01；可与 M6-02 并行
- 目标：建立第三方代码、依赖、镜像、字体、图标、图片、文案、商品目录和采集内容的来源/权利台账、许可证清单及版本绑定 SBOM。
- 工程验收：
  - [x] 冻结 Web `c1b3dbb`、Backend `c6511e62`、Deploy `887a92c2` 和三张不可变 amd64 镜像。
  - [x] 六份 CycloneDX 1.6 SBOM 与安全证据包 `PASS`；安全包 SHA-256 为 `cfa09c9e96fb08c22cf73f54e22a97dbff809c851f78474ce796ad9cdb41f2d3`。
  - [x] 683 个组件、18 个工具引用、3 个内容资产由唯一权利记录覆盖，引用 702 份许可证/版权材料，无临时例外。
  - [x] `copper-rights-0.4.0-rc.6.tar.gz` 在 Ubuntu x86_64 发布工厂和 Mac 双端离线验证；SHA-256 为 `7ae03a0d0f003c3a9807a8df239e75a7d07f4a47619c464652beaf4eb36a4af8`。
  - [x] 工程门禁为未知权利 0、许可证冲突 0、缺失归属/授权 0；M6-05 律师复核明确保留为 `REQUIRED`，未创建最终 `LICENSE` 或正式公开结论。

### M6-04：与运行版本一致的客户源码包和离线可复现构建

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/70>
- 当前状态：隔离 x86 内部源码交付与 M6-07 复验已通过；正式客户源码包、完整离线依赖介质仍为 `FORMAL_RELEASE_BLOCKED`
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、前端、后端、数据库、安全、交付
- 优先级：P0
- 前置依赖：M6-01、M6-02、M6-03
- 目标：生成绑定产品版本、三仓提交、迁移、镜像、锁定依赖和校验值的客户源码包，并在空白断网环境完成构建、部署、导出、备份和恢复。
- 内部演练：M6-01 完成后可以先生成 `INTERNAL_REHEARSAL_ONLY` 候选，验证打包、校验、构建和部署链路；候选不得交付客户、公开发布或宣称完成 M6-04。M6-03 权利包的工程门槛已通过；M6-02 严格历史审计和 M6-05 法律复核仍是正式客户源码包的不可跳过门槛。

### M6-05：开源许可证、贡献、安全与商标治理文件

- GitHub Issue：<https://github.com/soyia/copper_web/issues/60>
- 当前状态：进行中（M6-03 工程证据已提交复核；M6-02 平台历史和知识产权律师书面结论仍为 `FORMAL_RELEASE_BLOCKED`）
- 建议仓库：`soyia/copper_web`（跨仓库治理跟踪）
- 责任面：产品、法务、安全、社区、发布
- 优先级：P0
- 前置依赖：M6-02、M6-03
- 目标：复核 AGPLv3/商业双授权候选和贡献版权链，形成最终 `LICENSE`、`NOTICE`、`SECURITY.md`、`CONTRIBUTING.md`、行为准则、贡献机制及商标规则。
- 内部演练证据：`docs/self-hosting/M6_05_LEGAL_REVIEW_PACKET.md` 仅汇集律师复核所需事实、问题、证据和签署栏；它不是法律意见或最终治理文件。M6-03 工程证据已经通过，正式文件仍只能在 M6-02 平台复扫通过及律师书面结论后，经 PR 落地。

### M6-06：GitHub 公开权威源与全新克隆可复现验收

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/71>
- 当前状态：认证 GitHub 全新克隆内部演练已通过；匿名公开资料复现仍未通过，Issue 保持打开
- 建议仓库：`soyia/copper_deploy`
- 责任面：发布、部署、安全、前端、后端、数据库、文档
- 优先级：P0
- 前置依赖：M6-02 至 M6-05
- 目标：仅使用 GitHub 公开资料，在全新环境完成三仓克隆、联网/离线构建、安装、核心业务、完整导出、加密备份和旁路恢复，证明不存在私有核心依赖。
- 内部演练证据：三个 `main` 克隆到全新目录，Web `af6ef0`、Backend `f32e6a`、Deploy `236e96` 与 M6-07 候选精确匹配；三仓严格扫描为 0。该流程使用已认证的私有 GitHub 访问，不是匿名公开验收，不能关闭 M6-06。

### M6-07：空白环境最终审计与 M6 退出验收

- GitHub Issue：<https://github.com/soyia/copper_web/issues/61>
- 当前状态：内部全流程演练已通过；正式 M6 退出为 `NO`，Issue 保持打开
- 建议仓库：`soyia/copper_web`（跨仓库退出验收）
- 责任面：产品、交付、法务、安全、发布、前端、后端、部署、数据库
- 优先级：P0
- 前置依赖：M6-01 至 M6-06
- 目标：在独立空白环境复核三仓、源码包、迁移、镜像、SBOM、许可证、治理文件和公开资料，形成 M6 最终退出报告并决定是否进入 M7。
- 内部演练证据：`docs/self-hosting/M6_07_FINAL_EXIT_ACCEPTANCE_REPORT.md`。`0.4.0-rc.2` 在共享 AWS 主机的独立 Compose 项目完成确定性源码包、联网源码构建、首次改密、虚构主数据、完整导出、加密备份、旁路恢复、整站重启、秘密审计和清场；M7 内部交付演练可以开始。
- 内部演练完成项：
  - [x] 三仓认证全新克隆与严格扫描 0 项。
  - [x] 确定性源码包、远端校验和三张 x86_64 源码镜像构建。
  - [x] 三层部署、首次凭据销毁、核心主数据、完整导出、加密备份和旁路恢复。
  - [x] 恢复业务行核对、整站重启、8/8 秘密审计、测试私钥/旁路/项目清理。
  - [x] 明确主机非独立空白、认证克隆非匿名、离线依赖不完整及 M5 延期项，未产生正式/`Certified` 结论。
- M6 退出门槛：
  - [ ] 发布前安全、历史、版权、许可证、构建和部署审计全部通过。
  - [ ] 第三方只使用交付/公开资料可复现完整核心系统、备份恢复和自由导出。
  - [ ] 仓库、历史、源码包、证据和发布物不存在客户数据、秘密或受限资产。
  - [ ] 法律复核、已知例外、有效期和责任人明确；未通过项失败关闭。
  - [ ] M5 延期项继续独立记录，不因 M6 通过产生本地 `Certified` 结论。

## 10. M7：内部试点、价格验证与正式发布准备

M7 架构权威文档为 `docs/self-hosting/M7_INTERNAL_PILOT_AND_RELEASE_ARCHITECTURE.md`。总跟踪 Issue 为 [`soyia/copper_web#72`](https://github.com/soyia/copper_web/issues/72)。当前只进行 `INTERNAL_REHEARSAL_ONLY`；M6 正式阻断和 M5 延期项继续独立跟踪。

### M7-01：冻结内部试点与正式发布决策架构

- GitHub Issue：<https://github.com/soyia/copper_web/issues/73>
- 当前状态：已完成架构、指标和七项依赖冻结，等待 PR 合入 `main`
- 建议仓库：`soyia/copper_web`
- 目标：冻结四级状态、操作者主导演练、支持事件、完整经营/运维链、成本和多环境证据，以及 `1.0.0` 失败关闭条件。

### M7-02：零研发干预自助部署与交接演练

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/86>
- 当前状态：内部工程完成；首份 AWS 样本保留 `SUPPORT_REQUIRED` 历史结论，v0.4.4 真实恢复/修复与连续操作者自动模拟均通过；真实非开发客户独立样本转入 M7-07 正式发布门槛
- 建议仓库：`soyia/copper_deploy`
- 前置：M7-01
- 目标：用真实 AWS 样本验证云端预检、安装、首次初始化、备份恢复、导出和交接，并以可重复模拟覆盖不值得产品负责人再次手工执行的输入、DNS 和清场分支；模拟不得冒充正式客户证据。
- 验收工具门槛：执行 `docs/self-hosting/ACCEPTANCE_TOOL_LIFECYCLE_POLICY.md`；开始前建立操作者宿主基线，额外 Agent/研发工具只能使用专用运行环境和唯一 `run_id`，成功、失败、超时或中断后本次新增非证据进程、监听、容器、临时 Profile 和秘密必须为 `0`。
- 失败关闭：若验收工具影响操作者日常 Chrome/Edge，或仍需活动监视器、重启、额外终端命令、研发代操作或 AI 才能恢复，本次不得记为“零研发干预成功”，必须登记支持事件并修复后以新样本重验。
- 第一份样本：完整经营前的安装、首次改密、虚构主数据、加密备份、客户离线解密、旁路恢复、自由导出和自动备份均通过；因镜像身份说明、手工配置、备份侧车和实时排障新增命令，不形成零支持结论。
- 工程修正：Deploy `copper-install` v0.1.0 使用单入口完成闭集介质、Ubuntu/资源/端口、DNS/IP、计划确认、自动配置、三层健康和可信 HTTPS；DNS 未生效可原地重检或更换域名，证书失败可等待/继续/换域名，同版本修复保留数据和密钥，不同版本及来源不明数据拒绝覆盖。
- 恢复子链：2026-07-21 产品负责人仅使用 v0.4.4 Mac 统一入口，从已验证且已删除站点的备份完成空白安装、旁路恢复、正式切换、恢复后数据保护和最终 47 通过/1 提醒/0 失败；没有新增命令或代操作，记为 `INTERNAL_REHEARSAL_RECOVERY_PASSED`。
- 自动收口：Deploy 连续模拟直接调用 v0.4.4 生产输入与 DNS 选择函数，在同一 `run_id` 验证服务器登录用户名提示、DNS 原地重试/换域名/IP/SSH 地址和异常清场；79 项安装/恢复回归通过，专属进程、监听、临时 Profile 和一次性秘密残留为 0。
- 内部结论：`INTERNAL_REHEARSAL_AUTOMATED_PASSED`，M7-02 Issue 可关闭，产品负责人无需重复人工安装。
- 正式门槛：真实非开发客户独立样本仍为 `FORMAL_INDEPENDENT_OPERATOR_SAMPLE_PENDING`，转入 M7-07；未通过前不得形成正式零支持宣传或收费交付结论。

### M7-03：首日经营全闭环与学习成本试点

- GitHub Issue：<https://github.com/soyia/copper_web/issues/74>
- 当前状态：内部演练完成；Web `#83` 至 `#92`、`#94` 本轮相关问题已在 AWS MySQL 8.4 新镜像完成真实浏览器/数据库复验；管理员/普通操作员仓库权限、新草稿 10 kg 实际库存数量和附件关闭页签后重载均通过，M7-03 与 `#74` 可以关闭
- 建议仓库：`soyia/copper_web`
- 前置：M7-02
- 目标：用虚构数据完成采购至收付款/核销/对账闭环，验证库存、往来、资金、权限、附件、打印、移动端和审计，并记录学习成本与误操作。

### M7-04：升级、备份、恢复、换机、导出与断网连续经营

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/87>
- 当前状态：内部演练完成；rc.3 至 rc.4 升级、失败注入、回滚、再升级、重启、备份、离线验证、同机隔离等价换机恢复、升级后完整导出和断外联经营全部通过，验收证据经 Deploy PR `#106` 合入；Issue `#87` 可以关闭
- 建议仓库：`soyia/copper_deploy`
- 前置：M7-03
- 目标：使用 M7-03 虚构经营数据完成受支持升级、失败恢复、加密备份、离线解密、旁路/换机恢复、完整导出和断网经营。
- 证据：<https://github.com/soyia/copper_deploy/blob/main/docs/M7_04_UPGRADE_RECOVERY_CONTINUITY_EVIDENCE.md>。内部状态为 `INTERNAL_REHEARSAL_FLOW_PASSED`；M5 实体延期项、M6 正式阻断和真实非开发客户独立样本继续单独跟踪。

### M7-05：价格、服务额度、税费与真实成本复算

- GitHub Issue：<https://github.com/soyia/copper_web/issues/75>
- 当前状态：内部复算完成；保留 M7-02 `SUPPORT_REQUIRED` 人工和敏感性，当前目标价格整体保留，安心维护调整为年度4次联系、全年主动人工累计不超过120分钟；正式收费仍阻断
- 建议仓库：`soyia/copper_web`
- 前置：M7-02 至 M7-04
- 目标：区分标准人工、研发、客户/第三方等待、基础设施、税费、售后和风险，复算当前 50% 降价目标与全套收费/交接单据。
- 证据：`docs/self-hosting/M7_05_PRICE_SERVICE_COST_RECALCULATION.md`、公式工作簿和按原11页模板填写的虚构首次收费/交付单据；全部为 `INTERNAL_REHEARSAL_ONLY`。

### M7-06：多环境试点与 M5 延期项正式范围决策

- GitHub Issue：<https://github.com/soyia/copper_deploy/issues/88>
- 当前状态：内部范围决策完成；`FIRST_RELEASE_CLOUD_ONLY`，Issue `#88` 可关闭
- 建议仓库：`soyia/copper_deploy`
- 前置：M7-02 至 M7-04
- 结论：`1.0` 只保留客户自有公网云 Ubuntu Server 24.04 LTS x86_64。其他云厂商保持 Candidate；本地/LAN、Windows VM、NAS、ARM64、macOS、实体存储和延期外设移出首发。Deploy `#63/#64` 与 Web `#41/#44` 按“1.0 不计划”关闭，不代表验收通过。
- 证据：<https://github.com/soyia/copper_deploy/blob/main/docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md>。价格、手册、责任和路线图已对齐；本地/NAS `¥649 起` 仅保留为未来成本目标。

### M7-07：M0-M7 总审计与 `1.0.0` Go/No-Go

- GitHub Issue：<https://github.com/soyia/copper_web/issues/76>
- 当前状态：内部总审计完成；产品负责人 Tian 已于 2026-07-18 签署 `NO_GO`；Issue #76 按本轮失败关闭决定关闭，全部正式阻断继续分别跟踪
- 建议仓库：`soyia/copper_web`
- 前置：M7-05、M7-06、M6 正式退出
- 目标：从三个最新 `origin/main` 重建最终候选，复核产品、源码、镜像、迁移、备份/导出、权利、安全、价格、合同、支持和版本生命周期；只有书面 `GO` 才能创建 `v1.0.0`。
- 审计证据：`docs/self-hosting/M7_07_FINAL_GO_NO_GO_AUDIT.md`、`docs/self-hosting/evidence/M7_07_FINAL_AUDIT_SNAPSHOT.json`。三仓当前树 strict=0，但普通 Git 历史 strict=`BLOCK` 41 项；rc.4 缺少 8 个正式清单字段且未绑定当前 Web/Deploy；真实非开发客户独立样本、M6、权利/法律/匿名复现/正式候选/会计与交接门槛未通过。因此没有生成 `1.0.0` 候选，并明确禁止 tag、Release、正式收费和公开开源。

## 11. 创建顺序

1. M0-01 至 M0-05 已按中国境内试运营基线完成。
2. M1-01 至 M1-07 已按依赖顺序实施并完成，M1-07 为最后退出验收。
3. M2-01 至 M2-07 已全部完成，M2 退出验收通过。
4. M3-01 至 M3-05 已完成，正式发布 `v0.2.0-rc.1`，M3 退出验收通过。
5. M4-01 至 M4-07 已全部完成，M4 退出验收通过。
6. M5-01 至 M5-03 已完成支持矩阵、多架构发行和 Ubuntu x86_64 本地主机生命周期。
7. M5-04 和 M5-05 的实现与 Ubuntu x86_64 工程门槛已通过；M5-06 收敛范围于 2026-07-16 完成。M7-06 已将未通过的实体本地、存储和外设范围移出 `1.0`，Deploy `#63/#64` 与 Web `#41/#44` 按“不计划”关闭；没有本地 `Certified`，未来重启须新建版本化任务。
   - M5-R1 的 Cloudflare Tunnel 可选远程访问已完成 Deploy PR `#138` 生命周期和 PR `#139` 选项式维护工程基线；真实客户 LAN/公网/Access/中国大陆网络与合规/成本门槛未通过，不改变上述首发范围。
8. M6 总跟踪 Issue `#57` 与 M6-01 至 M6-07 已创建；M6-01 和 M6-03 工程工作已完成，M6-04/M6-06/M6-07 内部演练链已走通。M7 内部交付演练可以继续；M6-02、M6-05、匿名公开复现、独立空白主机和完整离线依赖介质继续阻断正式退出、客户源码包与公开发布。
9. M7 总跟踪 Issue `#72` 与 M7-01 至 M7-07 已创建；M7-01 至 M7-06 内部工作完成，M7-02 以真实 AWS 证据和自动连续操作者模拟收口，M7-07 内部总审计和产品负责人 `NO_GO` 决定已完成；真实非开发客户独立样本和全部其他正式发布门槛继续跟踪。

## 12. GitHub 授权与执行记录

- 2026-07-13：GitHub 插件已连接用户身份，但 GitHub App 安装账号和可访问仓库列表为空。
- 解除条件：将 GitHub App 安装到拥有 `soyia/copper_web`、`soyia/copper_backend` 的账号/组织，并授权访问这两个仓库。
- 2026-07-13：阻塞已解除，已验证两个仓库可读写，并创建 M0 GitHub #1-#5。
- 2026-07-13：已跨 `soyia/copper_web`、`soyia/copper_backend` 创建 M1-01 至 M1-07；M1-01 指派 `soyia`，其余任务按依赖保持阻塞。
- 2026-07-13：M1-01 至 M1-06 已关闭；M1-07 在全新 Ubuntu 24.04.4 LTS x86_64 中国区云服务器完成 RC3 离线安装、业务闭环、持久化和故障恢复。经产品负责人确认，公网与 Chrome/Edge 验收等待域名备案后补验。
- 2026-07-13：M2-01 至 M2-07 已跨 `soyia/copper_web`、`soyia/copper_backend`、`soyia/copper_deploy` 创建；M2-01 指派 `soyia` 并完成架构冻结，其余按依赖保持未开始。
- 2026-07-13：M2-02 通过 `soyia/copper_deploy#11` 合入 `main`；MySQL 8.4.10、88 张表、数据库引用附件、客户公钥加密、原子发布、计划任务、保留策略和六类故障恢复已在 `local-database` 隔离验收，Issue #7 已回填证据并关闭。
- 2026-07-13：M2-03 通过 `soyia/copper_deploy#12` 合入 `main`；MySQL 8.4.10、88 张表、关键经营财务汇总、附件、外键、旁路登录、失败保护、人工切换和回滚已在 `local-database` 完整验收，最终运行 483 秒；Issue #8 已回填证据并关闭。
- 2026-07-14：M2-04 通过 `soyia/copper_backend#11` 和 `soyia/copper_web#21` 合入两仓 `main`；`local-database` 使用 MySQL 8.4.10 导出 87 张业务表、11,252 条记录和 1 个原始附件，并验证真实进度、并发防重复、失败安全停止、重新生成、权限范围、审计、十进制/时区、关键余额、秘密排除和 SHA-256；前端生产构建、5 项镜像契约测试和真实浏览器验收通过。
- 2026-07-14：M2-05 通过 `soyia/copper_deploy#13`、`soyia/copper_backend#12`、`soyia/copper_web#23` 合入三仓 `main`；`local-database` 使用 MySQL 8.4.10、独立本地目标、bind-mount NAS 和固定摘要 MinIO 验证三目标同字节/SHA、容量 API、危险写保护及 NAS/S3 故障恢复，最终复验 50 秒；共享容器身份不变，8191 未占用，现场已清理。
- 2026-07-14：M2-06 通过 `soyia/copper_deploy#14` 合入 `main`，Issue `soyia/copper_deploy#9` 已关闭；`local-database` 使用 MySQL 8.4.10 验证客户离线持钥、双人交接、真实备份证明、可配置调度、连续失败与恢复、密钥轮换、旧钥恢复、新旧钥隔离、服务器私钥扫描、隐私安全签名告警和通知失败留痕，伪造回执、错误私钥和不安全权限均被拒绝；共享容器身份不变，8191 未占用，隔离现场已清理。
- 2026-07-14：M2-07 通过 `soyia/copper_deploy#16` 合入 `main` 并发布 `v0.1.0-m2-rc.2`。AWS 中国区 Ubuntu 24.04 x86_64 空白主机离线完成两个迁移方向、两次 35 秒旁路恢复、16 秒云站一致性备份、继续经营闭环、回滚、自由导出、客户持钥和附件/历史审核快照哈希验证；5 次失败均保持原站健康且数据不变，并形成可复核的修复与成本记录。验收后清理为 0 Copper 数据目录、容器、镜像、网络、卷和监听端口。
- 2026-07-14：M3-01 通过 `soyia/copper_deploy#18` 合入 `main`，Issue `soyia/copper_deploy#17` 完成版本、节奏、组件组合、逐来源兼容矩阵和安全回退规则。新规则预留 `0.2.0-rc.1` 但未创建标签；`copper-release/v1` 30 个必填字段、`copper-upgrade-matrix/v1` 和静态契约测试通过。
- 2026-07-14：M3-02 通过 `soyia/copper_deploy#20` 合入 `main`，Issue `soyia/copper_deploy#19` 已关闭并全部勾选。发布契约、精确来源匹配、升级 preflight/prepare/status、恢复密钥和最近备份门槛、新的一致性备份及 root-only `PREPARED` 证据已落地；`local-database` MySQL 8.4.10 隔离验收 94 秒通过，未执行迁移或版本切换。
- 2026-07-14：M3-03 通过 `soyia/copper_deploy#22` squash 合入 `main`，Issue `soyia/copper_deploy#21` 已关闭并全部勾选。精确 PREPARED 一次性消费、加密备份旁路恢复、目标 Flyway 隔离执行、完整迁移合同、可信人工切换、原子数据/附件/配置交换、写脏拒绝、失败恢复和对称回滚已落地；`local-database` MySQL 8.4.10 完整运行 395 秒，共享容器与宿主机 8191 未改变。
- 2026-07-14：M3-04 通过 `soyia/copper_deploy#24` squash 合入 `main`，Issue `soyia/copper_deploy#23` 已关闭并全部勾选。联网/离线 `copper-delivery/v1`、root-only READY、独立摘要、精确镜像交付、失败保护、幂等和升级 READY 门槛已落地；41 项测试通过，`local-database` 真实 Docker 最终验收 3 秒，共享容器、8191 和正式配置未改变。
- 2026-07-14：M3-05 通过 Backend `soyia/copper_backend#13`、Deploy `soyia/copper_deploy#26` 至 `#33` 合入两仓 `main`，Issue `soyia/copper_deploy#25` 已关闭；正式 `v0.2.0-rc.1` 在 Ubuntu 24.04 x86_64 完成 schema 3 至 4 升级、故障恢复、继续经营和独立空白站恢复，M3 退出验收通过。
- 2026-07-14：已创建 `soyia/copper_web#31` M4 总跟踪，以及 M4-01 至 M4-07 七个跨仓库 Issues；M4-01 通过架构文档、路线图和任务清单冻结信任边界、审计、监控、容灾及断网合同，其余任务按依赖保持等待。新加坡 AWS 空白 Ubuntu 24.04 x86_64 主机作为 M4-07 最终验收环境，已关停的中国区主机不再使用。
- 2026-07-15：M4-02 应用身份会话、授权、数据范围和危险操作审计与 M4-06 部署任务哈希链审计/故障重放完成闭环；M4-06 在 Ubuntu 24.04 x86_64 真实注入数据库、磁盘、备份、证书、时间和异常断电等价故障，36 条事件及经营财务不变量全部通过。可信时间目录由 root 控制并只读挂载，应用写入探针被拒绝；最终证据清单 SHA-256 为 `9236bf1b1fd0006330e2861a7c92301a5f931af82309e48bff52a86e0fccc923`，当前 schema 声明外键为 0 的结构风险已明示。
- 2026-07-15：M4-07 使用 `0.3.0-rc.4` 未发布安全候选，在新加坡 AWS Ubuntu 24.04 x86_64 空白机完成真实公开 CA TLS、CSP/HSTS、Cookie、公网浏览器、完全断网业务闭环、第三方降级、自助状态、备份、重启持久化、首次凭据销毁和 M4-06 六类故障复验。最终现场无 Copper 容器、数据目录、计时器或产品端口监听，主机时间恢复同步；M4 退出验收通过。
- 2026-07-15：已创建 `soyia/copper_web#41` M5 总跟踪、Web `#42` 至 `#44` 及 Deploy `#61` 至 `#64` 分项 Issues；M5-01 冻结四级支持状态、候选平台顺序、LAN HTTPS、主数据/备份边界、休眠、外设、晋级证据和 `¥649 起` 服务范围。当前无本地 Certified 平台。
- 2026-07-15：M5-02 通过 Deploy `#65` 合入 `main`；M5-03 通过 Deploy `#66` 合入 `main`，Ubuntu 24.04 x86_64 写入前预检、systemd 重启、四层监控、卸载/受管重装和数据保留真实验收通过。Windows 路径改为“Windows 宿主 + Ubuntu x86_64 虚拟机”，取消原生/WSL2/Docker Desktop 安装。
- 2026-07-15：M5-05 通过 Deploy `#68` 合入 `main`；本地同盘风险、外接盘/NAS 稳定身份、失败关闭和 NAS 取回恢复已完成工程验收。Issue `#63` 保持打开，等待真实独立外接盘、远程 NAS、独立设备 B 和客户离线恢复材料交接。
- 2026-07-16：产品负责人明确先跳过 Issue `#63` 的四项实体门槛继续 M5-06；`#63` 保持打开。M5-06 Web 工程实现和 Ubuntu 24.04 x86_64 隔离站点验收完成，覆盖局域网状态、会话草稿、打印合同、HID 扫码、附件幂等、多会话权限审计和 Backend 中断恢复。Issue `#43` 保持打开，等待双实体终端、实体打印机/扫码枪、真实客户端断网上传和本地主机休眠/地址变化。
- 2026-07-16：产品负责人再次收窄当前实体范围，只完成 Mac＋手机双终端和实体 A4 打印；独立 Ubuntu 本地主机、Windows 宿主 VM、HID 扫码枪、针式打印机、主机休眠/LAN 地址变化，以及 Issue `#63` 的四项存储迁移门槛全部延期。两包通过后关闭 Issue `#43` 当前范围并直接开始 M6；M5-07 不启动，本地平台不晋级 `Certified`。
- 2026-07-16：M5-06 当前范围完成。Mac＋手机双实体终端完成权限、相邻写入、图片短断网恢复和客户端休眠观察；HP Laser MFP 136w 完成入库单 1 页复打及 24 行连续 2 页实体打印，跨页表头、分页、边距、金额和单号完整，无空白尾页。Issue `#43` 可关闭，M6 可以开始；采购订单本轮不伪称单独实体打印，依靠共享 A4 基础样式和独立工程契约覆盖。
- 2026-07-16：已创建 `soyia/copper_web#57` M6 总跟踪、Web `#58` 至 `#61` 及 Deploy `#69` 至 `#71` 七个子任务。M6-01 通过 Web PR `#62` 冻结三仓完整核心、精确版本源码包、客户自主权、秘密/客户数据零容忍、GitHub 唯一权威源及法律复核门槛；不创建最终 `LICENSE`，不操作 Gitee，不把仓库公开。
- 2026-07-16：M6-02 三仓普通 GitHub 历史均已重置为单根 `main`，Backend `#24` 和 Deploy `#74` 门禁通过完整历史 CI 合入，Deploy 的 6 个旧标签、5 个 prerelease 和 22 个资产经产品负责人确认删除；三仓普通 `main` 全新严格审计为 0。GitHub 托管的 115 个历史 PR head 仍复现 48 项；已提交 GitHub Support case `#4575811`，等待平台处置与隔离复扫，Issue `#59` 继续打开。
- 2026-07-17：M6-06/M6-07 内部演练完成。三仓认证全新克隆严格扫描为 0；`0.4.0-rc.2` 源码包两次生成 SHA-256 同为 `2b29fd5343c88c49402ded570a3cbe4adb498703d9439a4b27612c9a65826ec6`。Ubuntu 24.04 x86_64 隔离项目完成联网源码构建、三层部署、首次改密、虚构主数据、完整导出、加密备份、旁路恢复、整站重启、秘密审计和清场。主机非独立空白、认证克隆非匿名、Web/Maven 离线依赖介质不完整，因此 Issue `#61`、`#71` 和 M6 总跟踪保持打开；M7 内部演练可以开始，正式退出仍为 `NO`。
- 2026-07-17：M7 总跟踪 Web `#72`、Web `#73` 至 `#76` 和 Deploy `#86` 至 `#88` 已创建。M7-01 冻结内部/试点/正式/公开四级状态、产品负责人主导的自助部署、支持事件、完整经营/运维链、样本指标、成本公式、多环境证据和 `1.0.0` Go/No-Go。当前下一项为 M7-02；Agent 默认不代操作，额外命令、直接登录或修复均计入支持事件。
- 2026-07-17：M7-02 首份 AWS Ubuntu 24.04 x86_64 操作者样本完成安装、首次改密、主数据、加密备份、离线解密、旁路恢复、自由导出和定时备份；工程链通过，但发生介质说明、手工配置、侧车和实时排障支持事件，严格判定 `SUPPORT_REQUIRED`。Deploy 随后实现一站式安装器 v0.1.0，待合入 main、生成闭集介质并完成第二份空白机零新增命令样本后，才可关闭 M7-02。
- 2026-07-18：M7-03 本轮修复经 Web PR `#93`、`#95` 与 Backend PR `#27`、`#29` 合并；精确 `main` 镜像部署到 AWS Ubuntu 24.04 x86_64 / MySQL 8.4 / Flyway 1 至 7。切换前一致性备份成功，切换后 HTTPS、三层健康、四层监控和 47 项自检通过；真实浏览器确认 SKU/来源追溯、收货地址、发货进度、超发阻断、结算方式和工作台应收口径，历史 DRAFT 不再形成应收。随后管理员仓库维护、无 `warehouse.manage` 普通操作员只读、新草稿 `RK20260718083750737101` 的 10 kg 实际库存数量及附件关闭页签后重载全部通过；Web `#84`、`#85`、`#87` 与 M7-03 `#74` 可关闭，进入 M7-04 内部演练，详见 `docs/self-hosting/M7_03_FIRST_DAY_BUSINESS_ACCEPTANCE_REPORT.md`。
- 2026-07-18：M7-04 在 AWS Ubuntu 24.04 x86_64 完成 rc.3 至 rc.4 升级、隔离迁移失败、50 秒正式回滚、再次升级、重启、计划/手工加密备份、客户侧离线验证、45 秒同机隔离恢复、断外联经营和升级后完整导出。最终导出 88 张表、1,419 条记录、3 个附件、`1,409,876` bytes，SHA-256 `6cc44897d59938cb9f50d30013a9601b306617a782f7a43d8b9c416fd4a72dc0`，98/98 包内摘要通过。Deploy PR `#105` 修复控制面原子切换和镜像身份门槛，PR `#106` 合入完整证据；Issue `#87` 可按内部结论关闭，进入 M7-05。
- 2026-07-18：M7-05 使用 M7-02 至 M7-04 的真实时长、支持事件、失败、资源和恢复证据建立税费/人工/风险可调工作簿，7项检查全部通过；目标价格整体保留，安心维护改为4次联系、全年主动人工累计不超过120分钟。本地/NAS和代运维在M7-06及异机通知样本前暂不发布；11页虚构首次收费/交付单据已按原模板填写。完整报告为 `docs/self-hosting/M7_05_PRICE_SERVICE_COST_RECALCULATION.md`，Issue `#75` 可以关闭。
- 2026-07-18：M7-06 以 M7-02 至 M7-04 的 AWS Ubuntu 24.04 x86_64 真实样本和 M5 延期证据复核，冻结 `FIRST_RELEASE_CLOUD_ONLY`。本地 Ubuntu 保持 Candidate；Windows VM、NAS、macOS、ARM64、真实外接盘/NAS/设备 B、HID 扫码枪和针式打印机移出 `1.0`。Deploy `#63/#64`、Web `#41/#44` 按“1.0 不计划”关闭，不冒充验收通过；本地/NAS价格不发布。完整决定见 `copper_deploy/docs/M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md`。
- 2026-07-18：M7-07 总审计 PR `#102` 通过契约、机密性和 amd64/arm64 CI 后 rebase 合入；产品负责人 Tian 随后确认 `M7-07 DECISION=NO-GO`，Issue `#76` 按本轮失败关闭决定关闭。签署不解除任何正式阻断，也没有创建 `v1.0.0`、Release、稳定版清单、镜像、源码包或客户交付物。
- 2026-07-21：M7-02 v0.4.4 从 v0.4.3 已删除站点的真实备份完成空白安装、隔离恢复、正式切换、恢复后新备份、离线复验、第二位置复制、每日计划和最终自检；过程中没有新增命令或代操作，删除后整站恢复子链通过。该运行复用了旧检查点中的 `ubuntu`，不覆盖用户名提示、DNS 重试/换域名、同版本 repair 和宿主清场，故 M7-02 总任务继续打开。
- 2026-07-21：产品负责人决定不再重复人工安装。Deploy 连续模拟直接调用 v0.4.4 真实输入和 DNS 选择函数，覆盖服务器登录用户名、DNS 原地重试、换域名/IP/SSH 地址、最终计划和异常中断清场；79 项回归通过，专属资源残留为 0。结合既有 AWS 真实证据，M7-02 内部状态改为 `INTERNAL_REHEARSAL_AUTOMATED_PASSED` 并可关闭 Issue `#86`；真实非开发客户样本移至 M7-07 正式门槛。
- 2026-07-24：产品负责人决定本地互联网访问一期只支持 Cloudflare。Deploy PR `#138` 已实现 LAN 站点 Tunnel 配置、启用、状态、Token 轮换、停用和本地移除，并接入维护、诊断、永久删除、秘密保护及出站审计；Cloudflare 云端资源由客户账号显式治理。当前仅为 `INTERNAL_REHEARSAL_ONLY` 工程基线，客户常开主机、真实公网身份访问、中国大陆网络质量/数据路径/合规及正式服务成本均未验收，不改变 `FIRST_RELEASE_CLOUD_ONLY`；花生壳和 VPN 保留未来候选。
- 2026-07-24：根据“普通用户优先选项式维护、不复制底层命令”的产品原则，Deploy PR `#139` 将 Cloudflare 完整生命周期接入 v0.4.5 Mac 一站式维护入口并复用服务器同一子菜单；失败后回到菜单重试，本地移除由程序绑定当前站点并二次选择。285 项全量 Python 回归、静态/CLI 合同、完整历史保密审计和 GitHub MySQL 运行时检查通过；真实客户网络门槛不因交互收口而放宽。
- 后续动作：等待 GitHub Support 处置 M6-02，完成 M6-05 对现有 M6-03 权利/SBOM/许可证工程证据的律师书面复核，并取得真实非开发客户独立样本。全部阻断清零后，从届时新的三仓 `origin/main` 重新执行 M7-07；未清零前不得正式客户交付、公开 Release、创建 `v1.0.0` 或标记本地 `Certified`。
