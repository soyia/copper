# M4-03 生产秘密、日志脱敏、SBOM 与漏洞治理

## 1. 目标与边界

本项把生产秘密、普通日志和发布供应链变成可检查、可轮换、可重复扫描和可交接的闭环。
它不引入 Copper 云端密钥服务、遥测、许可证心跳或客户必须购买的扫描服务。扫描器只在发布工厂
运行，不进入客户正式 Compose，不增加正式服务容器，也不影响完全离线经营。

经营单据、库存、应收应付、资金和核销事实仍由业务表及流水保存。安全日志和发布扫描证据不能
代替业务账簿。

## 2. 秘密分类与生命周期

| 秘密 | 保存位置/持有人 | 生成与交付 | 轮换/撤销/销毁 |
| --- | --- | --- | --- |
| MySQL 业务/root 口令 | `/etc/copper/secrets`，root-only 文件挂载 | 安装时 `openssl rand` 生成，不显示内容 | `copperctl security secrets rotate-database` 计划停写、原子轮换并失败恢复 |
| 首次管理员一次性凭据 | `/etc/copper/credentials/initial-admin.txt` | 仅可信交互终端向客户管理员显示 | 首次改密后 Backend 销毁并撤销临时会话 |
| 登录会话 | 数据库只保存令牌摘要 | 登录时密码学随机生成 | 用户/管理员撤销、改密、停用和并发上限触发撤销 |
| 域名 TLS 私钥 | Caddy 客户站点状态目录 | Caddy 自动签发，客户控制域名和服务器 | 正常续期；泄露时撤销证书并重新签发 |
| 公网 IP TLS 私钥 | `/etc/copper/tls/privkey.pem` | acme.sh 在客户服务器生成 | `copperctl tls renew`；泄露时撤销并重新签发 |
| 备份恢复私钥 | 只由客户离线持有 | `copper-key-offline` 双人交接 | M2 挑战证明轮换；服务器只保留接收公钥 |
| S3/Webhook 凭据 | `/etc/copper/*-credentials` root:root 0600 | 客户从第三方取得后写入文件 | 先在第三方生成新值，再原子换文件、验证、撤销旧值并登记事件 |

`copperctl security secrets audit` 检查：

- 敏感配置必须使用 `_FILE` 绝对路径，不能把值写入 `copper.env`；
- 数据库、TLS、第三方和首次管理员文件的类型、属主、权限与最小长度；
- 已知秘密是否出现在任何进程参数或进程环境；
- 客户唯一 `AGE-SECRET-KEY-` 是否错误进入服务器配置或备份密钥登记目录。

MySQL 仍通过 Compose secret 的 `*_FILE` 接收初始化口令。由于 MySQL 8.4 官方入口脚本会把读取出的
口令继续导出给长期运行的 `mysqld`，Compose 使用随发布物交付的失败关闭包装层：保留官方初始化和
账户创建流程，只在唯一的最终 `exec` 前清除 `MYSQL_PASSWORD` 与 `MYSQL_ROOT_PASSWORD`。如果上游
入口结构变化而无法唯一确认清理点，MySQL 必须拒绝启动，不能带着未知的秘密暴露状态继续运行。

生命周期登记簿位于 `/var/lib/copper/security/secret-lifecycle.json`，权限 0600。它只保存稳定秘密
类别、事件、操作者、原因和可选证据 SHA-256，不保存值、指纹或可用于离线猜测的材料。允许事件：
`GENERATED`、`DELIVERED`、`ROTATED`、`REVOKED`、`DESTROYED`、`INCIDENT_OPENED`、
`INCIDENT_CONTAINED`、`INCIDENT_CLOSED`。

### 2.1 数据库口令轮换失败保护

数据库轮换必须在维护窗口执行，并要求输入当前 `COPPER_SITE_ID`、操作者和原因：

```bash
sudo copperctl security secrets rotate-database \
  --confirm copper-prod --actor customer-admin --reason "quarterly rotation"
```

命令先停止 Web/Backend，秘密只通过标准输入交给容器内临时 MySQL 客户端配置，不进入 argv、环境或
日志；新旧口令独立认证后才原子替换文件并强制重建三层服务。任一步失败会尝试把数据库账户、文件
和服务恢复为旧状态。自动恢复也失败时保持业务停止并明确返回人工处置，不返回假成功。成功事件同时
写入 Backend `security_audit_log` 和 root-only 生命周期登记簿。

### 2.2 泄露处置

1. 不在公开 Issue、群聊或普通日志粘贴疑似秘密；登记 `INCIDENT_OPENED`。
2. 撤销会话、第三方 Token、证书或账户；必要时停止对外入口，但不删除业务数据和审计证据。
3. 确定受影响的站点、秘密类别、产品版本和时间段；不得把客户数据复制到 GitHub。
4. 生成新秘密，按依赖顺序切换并验证，随后撤销旧值；登记 `ROTATED`/`REVOKED`。
5. 执行秘密扫描、权限审计和相关业务一致性检查；登记 `INCIDENT_CONTAINED`。
6. 客户确认无未授权访问后登记 `INCIDENT_CLOSED`。事件证据保留，秘密本体销毁。

## 3. 普通日志脱敏

日志采用三层防护：

1. Caddy 原始访问日志显式删除 Authorization、Proxy-Authorization、Cookie、Set-Cookie、Referer 和
   User-Agent；查询参数中的 password、token、secret、key、code 等值替换为 `REDACTED`，IP 只保留
   网络段。
2. Backend 禁止请求/响应完整正文日志，并在 Logback 最终格式化出口统一遮蔽口令、Token、Cookie、
   API key、邮箱、手机号、证件号和银行账号；异常栈文本经过同一出口。
3. `copperctl logs` 再执行结构化/文本脱敏，作为客户查看普通日志的最后防线。

客户名称、地址、价格和单据正文没有可靠通用正则，因此正确控制不是“先记录再猜测脱敏”，而是禁止
写入普通日志。排障只记录追踪 ID、稳定内部主键、事件类型、结果和异常类别。业务完整内容只能在授权
页面、导出或加密备份中按其自身权限边界处理。

## 4. 发布 SBOM 与扫描证据

`config/security-policy.env` 固定发布工具版本和不可变多架构镜像摘要：

- Syft 1.44.0：CycloneDX 1.6 SBOM；
- Grype 0.112.0：依赖/镜像漏洞报告；
- Gitleaks 8.30.1：Git 历史、发布快照和归档秘密扫描，输出 100% 脱敏。

`config/gitleaksignore` 只允许保存经过人工复核的**完整发现指纹**，禁止按目录、规则、正则或通配符
放行。前两条指纹均来自后端 `BootstrapCredentialService.PASSWORD_CHARS` 的 57 字符候选集：该常量
仅被 `SecureRandom.nextInt(...)` 用于生成 24 位首次管理员一次性密码，不是凭据、令牌或连接密钥；
两个指纹对应同一行在两个历史提交中的记录。第三条指纹来自 `SensitiveLogSanitizerTest` 为验证日志
脱敏而故意构造的残缺 JWT 头，不含 payload、签名或可用凭据。第四条指纹来自 M2 存储测试 `else`
分支的一条固定 `echo` 诊断文案，匹配范围内没有变量展开、文件读取或凭据值。新增或改变任何指纹都
必须重新复核代码用途、单独评审并留下 PR 审计记录，不能通过漏洞例外文件绕过真实秘密发现。

发布物深度扫描会为归档内部内容生成独立指纹。`v0.2.0-rc.1` 另有四条已复核归档指纹：一条是上述
M2 诊断文案在部署源码归档中的副本；三条是固定依赖 `acme.sh 3.1.3` 中以 `#` 开头的配置示例注释。
它们均不含运行时赋值或凭据值。归档名、版本或内容一旦变化，旧指纹不会继续生效，扫描必须重新阻断并
人工复核。

每个具体产品发布生成六份 SBOM：Web/Backend/Deploy 三个精确源码提交，以及 Web/Backend/MySQL 三张
精确 amd64 镜像。源码 SBOM 只扫描上述提交的 `git archive` 快照，禁止把 checkout 中未跟踪或被忽略
的 `node_modules`、构建输出、IDE 缓存和历史对象误计入发布依赖；三仓 Gitleaks 仍扫描完整 Git 历史。
六份 SBOM 均进入 Grype，发布目录另行进入 Gitleaks。安全证据包包含：

- 产品版本、发布清单 SHA-256、三仓提交和三张镜像摘要；
- CycloneDX SBOM、Grype JSON、Gitleaks JSON 和 Grype 数据库状态；
- 工具镜像摘要、安全策略摘要、漏洞计数、处置人、修复截止时间和有期限例外；
- Grype 数据库必须为有效状态，构建时间不得晚于扫描时钟且不得超过策略规定的 72 小时；
- 所有内部文件的字节数与 SHA-256。

验证器要求上述角色、数量和包内文件集合与主证据精确一致；缺少任一报告、伪造极简 `PASS` 文档或
追加未被主证据索引的文件都会失败，不能把“能解压”当作安全证据有效。

安全证据格式为 `copper-security/v1`。任何秘密发现或未处置的发布阻断漏洞都会删除临时目录且不生成
成功包。证据包自身不包含秘密，Gitleaks 一旦有发现就拒绝发布报告，避免“扫描报告再次泄露秘密”。

私有仓库不能匿名拉取时，不要求在验收机保存 GitHub Token。`security-export-image.sh` 只能从已缓存且
RepoDigest、amd64 架构、源码 revision 均与发布清单一致的 Web/Backend 镜像导出确定性 gzip 归档和
SHA-256 证据。扫描端用 `COPPER_SECURITY_PRELOADED_<ROLE>_{IMAGE,ARCHIVE,EVIDENCE}` 加载，并通过
`COPPER_SECURITY_PRELOADED_<ROLE>_SOURCE=verified-preloaded` 与 CI 原生归档 `ci-preloaded` 区分；
两种预加载来源加载后都要再次校验架构和 revision，来源证据摘要进入最终证据包。

### 4.1 发布工厂命令

扫描源必须是独立、干净的 Git checkout，HEAD 与发布清单精确一致；工作树 `.git` 指针不被接受：

```bash
COPPER_SECURITY_ALLOW_PULL=1 ./scripts/security-scan.sh \
  /release/copper-release-0.3.0-rc.1.env \
  /source/copper_web /source/copper_backend /source/copper_deploy \
  /evidence/0.3.0-rc.1
```

M4 起候选和正式发布统一使用失败关闭的组合入口：

```bash
COPPER_SECURITY_ALLOW_PULL=1 ./scripts/build-secure-release.sh --candidate \
  0.3.0-rc.1 /release/0.3.0-rc.1 /source/copper_web /source/copper_backend
```

`build-secure-release.sh` 只有在发布包、秘密扫描、六份 SBOM、六份漏洞报告和安全证据校验全部通过后
才原子发布输出目录。旧 `build-release.sh` 只保留历史格式兼容，不再作为 M4 发布完成入口。

## 5. 漏洞治理与例外

| 严重度 | 默认修复时限 | 发布规则 |
| --- | --- | --- |
| Critical | 72 小时 | RC/稳定版均阻断；只有最长 7 天、实名负责人与补偿控制的例外可临时放行 |
| High | 14 天 | 稳定版阻断；RC 记录负责人和截止时间后可继续验证 |
| Medium | 60 天 | 不阻断 RC，但必须进入证据和修复计划 |
| Low/Negligible | 180 天 | 跟踪、随常规维护处理 |

误报或风险接受使用 `copper-security-exceptions/v1`，必须精确匹配发布版本、工件角色、CVE、包名、
安装版本和严重度，并填写 disposition、实名/稳定责任角色、技术理由、补偿控制及到期时间。禁止永久
例外、通配 CVE、只写“不可利用”或删除扫描结果。每条例外还必须绑定同目录普通证据文件及其
SHA-256；原始分析证据进入最终安全证据包。到期例外自动重新阻断发布。

安全修复仍遵守 M3：新提交、新镜像摘要、新发布清单和客户自主升级。禁止移动 Tag、覆盖镜像摘要、
修改已执行 Flyway 或用旧 SBOM 冒充新发布。

## 6. 安全报告入口

三仓 `SECURITY.md` 统一指向 GitHub 私密 Security Advisory：
<https://github.com/soyia/copper_deploy/security/advisories/new>。如果私密表单不可用，公开 Issue 只能写
`Security contact requested`，不得包含利用细节、客户数据、日志、截图、密钥或凭据。

## 7. 验收环境边界

- 普通 Python/Shell/Java 测试可在开发环境执行。
- Docker、Caddy 配置、真实镜像、SBOM 和漏洞扫描只在 `local-database` 或正式 x86_64 验收机执行。
- Mac 不启动 Docker/Colima；发布扫描器不进入客户正式 Compose。
- 完整机器报告保存在发布证据包，不把客户数据、凭据或实际生产日志提交到 GitHub。

M4-03 的真实 x86_64 升级、轮换故障恢复、最终安全候选与空白安装结果见
[M4-03 生产秘密与供应链安全验收报告](M4_SECURITY_SUPPLY_CHAIN_ACCEPTANCE_REPORT.md)。
