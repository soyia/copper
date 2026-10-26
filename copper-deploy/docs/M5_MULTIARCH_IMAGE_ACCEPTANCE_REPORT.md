# Copper M5-02 多架构镜像与离线发行验收报告

> 验收日期：2026-07-15
>
> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/61>
>
> 候选版本：`0.4.0-m5-rc.1`，状态 `CANDIDATE / NOT RELEASED`
>
> 结论：同一发布合同已在真实 Ubuntu 24.04 x86_64 与 ARM64 主机完成原生镜像导入、三层启动、业务查询、凭据销毁、加密备份、离线解密和隔离清理。`linux/amd64` 为 `certified`，`linux/arm64` 为 `candidate`，M5-02 可以关闭。

## 1. 验收边界

本轮证明 Web、Backend 和 MySQL 8.4.10 可以由同一个闭集发行索引选择正确平台的离线归档，并在真实 x86_64 与 ARM64 主机运行。发行目录不依赖客户从 Docker Hub 或 GHCR 在线拉取镜像，也不把 CI 构建机的 Docker 本地内容 ID 当作跨主机稳定身份。

本报告不创建正式 Tag 或 GitHub Release，也不把 ARM64 提升为生产认证平台。x86_64 继续是正式支持基线；ARM64 只允许候选测试。后续从全新虚拟机模板执行完整安装、升级、备份、恢复和卸载生命周期属于 M5-03，不能由本报告提前放行。

M5-03 后续产品决策把当前本地主机安装范围收敛为 Ubuntu 24.04 x86_64，Windows 仅作为 Ubuntu x86_64 虚拟机宿主。因此 ARM64 仍停留在本报告的镜像 `Candidate`，不接入当前安装器，也不由 M5-03 自动晋级。

## 2. 主机与隔离边界

| 平台 | 实测系统 | Docker / Compose | 结论 |
| --- | --- | --- | --- |
| `linux/amd64` | Ubuntu 24.04.4 LTS，真实 x86_64 云服务器 | Engine 29.1.3 / Compose 5.1.4 | `certified` |
| `linux/arm64` | Ubuntu 24.04.4 LTS，真实 aarch64 主机 | Engine 29.5.3 / Compose 5.1.4 | `candidate` |

开发者 Mac 没有运行 Docker、Colima 或 Docker Desktop。两次验收使用独立 Compose 项目、数据目录、备份目录、密钥和证据目录；Backend 与 MySQL 不映射宿主机端口，Web 只在验收期间使用 80/443。正式 8191 未启动、停止或替换。

ARM64 主机原有 `vm-mysql` 和 `vm-valkey` 在验收前后均保持健康。最终清理后 ARM64 只保留这两个原有容器，x86_64 无运行容器，两台主机 80/443 均无本轮遗留监听。

## 3. 不可变候选基线

| 项目 | 值 |
| --- | --- |
| Deploy 验收提交 | `13b688114a37a1b375b12aaa210f4c980ae37581` |
| 发行格式 | `copper-multiarch-release/v1` |
| 发行版本 | `0.4.0-m5-rc.1` |
| 独立发行索引 SHA-256 | `56e03894d18cb9e377d2c18a7b53b27fa91c26cdae303379e94b6b9a1085d2f7` |
| 闭集目录 | 1 个索引、6 个分平台镜像归档、1 个 `SHA256SUMS`，无额外文件、目录或符号链接 |
| 历史兼容边界 | `copper-release/v1`、`copper-delivery/v1`、`copper-backup/v1`、`copper-upgrade-state/v1` |

应用和数据库顶层身份：

| 角色 | 源码 / 工作流 | 顶层不可变身份 |
| --- | --- | --- |
| Web | `491fff58264af19640ddb442bd9cede28933187c` / `29401245319` | `ghcr.io/soyia/copper_web@sha256:13b288569886881ba4966acaff96b422ac69ea81942e49668ab20edde4564cb6` |
| Backend | `3214e4d0688a122bac2f027718aee0eb064ab4a8` / `29401250476` | `ghcr.io/soyia/copper_backend@sha256:9773420210c17284f738dd30c8657c75101a5a3895a47ad01b01b76d81f010ab` |
| MySQL | 官方 `8.4.10` | `mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af` |

Web Actions 工件 ID 为 `8337163058`，工件 ZIP SHA-256 为 `df2b517449f9c5e48be3bb3186fbd972bf0e4c0f889fc236b088ae95866a80fa`，BOM SHA-256 为 `b69e1e4776ff02079303625e9758e633cd5e7562e28eae69844ac4cb8e4557df`。Backend 工件 ID 为 `8337195524`，工件 ZIP SHA-256 为 `1e3fb2c04f8dd6a8e9d03e9af43465c228a07f9660bbcec58e5042b9937238b6`，BOM SHA-256 为 `6a218d7ff738915b05ddc7ed5da6382003d3bedb9d239714a51014810d14f611`。

## 4. 分平台身份与运行映射

| 角色 / 平台 | OCI 平台子摘要 | 生产者内容 ID | 验收主机运行时 ID |
| --- | --- | --- | --- |
| Web / amd64 | `sha256:929204f3466e0adbeeb04424a6b1de00a21eb5e9cbf04f64499e45a3a938c952` | `sha256:bf2bbd813cd548fdc5adbacf684f1049562b4cee948ceeef23201b47e0e4a581` | `sha256:3145b24c45c4f5dd9b11be664a23a295b8b68bdb47f3b08c6c38c8c790c5ac75` |
| Web / arm64 | `sha256:e150949c9f3e0458b908c79853d88ea31294887c4c508e9e6585eeb1b872adb6` | `sha256:f1f1a66478e37219ff4e2cca25bc4f5c5fb361086ce9cf4d349802b17c95e0ff` | `sha256:92615c848fca6e0c058b2cafc69a946efd36cc7ebe5f635b1c46b6be2fca8a4b` |
| Backend / amd64 | `sha256:0fcd7db17b8ade5216cd6441f0d64612082cb4a7db1a625dcd3bc6e36e5bfc4d` | `sha256:39f124dd93f41a9d0f6d3238ec6edbe835903e87656c471b5a8bfda78268f394` | `sha256:13739b516cb4cab2c9308c43c8de00dc79b77fee597e83ad6b1753b031885bcb` |
| Backend / arm64 | `sha256:b4f56957d49ab584cf7a25f083c0fa00b458a0875d34064e487b2b4eda22d9e2` | `sha256:2e9ce3c02c6ca56abe9d26ad77bd38e6170c7f8ea9c42fd8c02b6a26224c5be8` | `sha256:5a8d06181bbd772be41fc93914538e04d1de2dd2334e38753547ae13840c0654` |
| MySQL / amd64 | `sha256:ef9038553b7ea407704f16770e407ffd32f5566f125d0d94f63ff736af1d43f8` | 同平台摘要 | 同平台摘要 |
| MySQL / arm64 | `sha256:28474aba911e2a51b664e5c77d6ffe5657ae4b27550ff588e8c54db426023c44` | 同平台摘要 | 同平台摘要 |

真实导入证明 Docker 29 的不同镜像存储后端可能为同一归档生成不同的本地 `.Id`。因此发行合同同时固定归档 SHA-256、OCI 平台摘要、源码 revision、架构和生产者内容 ID；验收机记录生产者 ID 到实际运行时 ID 的映射，再使用实际运行时 ID 启动 Compose 和绑定备份。禁止只比较 CI 本地 ID，也禁止因此放宽归档、标签、revision 或架构校验。

## 5. 双平台实机场景

两台主机均通过以下门槛：

- 发行索引独立摘要与闭集目录校验；
- 当前主机只能选择本机架构的 Web、Backend、MySQL 归档；
- 原生 `docker load` 后复核架构、源码 revision、MySQL 补丁版本和运行时 ID；
- MySQL、Backend、Web 首次启动及一次停止/重启恢复均为 healthy；
- MySQL、Backend 无宿主端口，Web 端口集合精确为 80/443 TCP 与 443 UDP；
- HTTPS health/readiness、首次管理员登录、Cookie `Secure` / `HttpOnly` / `SameSite=Strict`；
- 首次强制改密、新口令登录及一次性凭据文件销毁；
- 商品、往来单位和可用库存批次三条核心查询成功；
- 客户 age 公钥双人登记、一致性整站备份、外层/envelope/密文载荷校验和离线私钥解密证明；
- `doctor` 通过 43 项、提醒 3 项、失败 0；
- Compose 项目、运行配置、测试数据、Cookie 和离线私钥自动清理。

ARM64 证据运行 ID 为 `20260715T093759Z-73849`，证据清单 SHA-256 为 `5bc031bac9ebf69bf15a3dfa4530d97b2c5b57fc05455e5a099b14a06b469cbb`。x86_64 证据运行 ID 为 `20260715T095048Z-380223`，证据清单 SHA-256 为 `818717300d090535917bd6f9cba89f5fc900d313431a9e033e228cef8dd294a7`。两个证据目录均为服务器 root-only，本报告不保存主机地址、SSH 私钥路径、Cookie、管理员口令、age 私钥、业务数据或原始运行日志。

## 6. 失败关闭与实测修正

真实验收暴露的问题均先失败并清理，没有绕过后继续：

1. 首次 ARM64 导入发现 CI 生产者 `localId` 不能跨 Docker 存储后端作为运行引用。验收在 Compose 启动前失败，随后改为受控归档标签、完整身份复核和生产者/运行时 ID 映射。
2. ARM64 全业务链首次通过后，`doctor` 仍按公网 DNS 检查隔离 `*.localhost`，导致最终门槛失败。修复只在 `COPPER_TEST_MODE=1` 且域名为 localhost 时使用本机解析和测试证书；正式域名/IP 检查没有放宽。
3. x86_64 配置测试域名的公网 HTTP-01 多地校验成功，但生产 CA 因该同名标识七天内已签发五张证书而返回 429。没有把 CA 限额记为代码失败，也没有关闭证书校验；同一主机、同一安全组、同一 Caddy 配置改用全新临时 DNS 标识取得受信任公网证书后完成最终验收。配置测试域名在 CA 限额恢复前不得重复申请。

## 7. 自动化回归

- 105 项 Python 测试全部通过，其中 7 项覆盖多架构发行合同。
- `backup-contract.sh`、`cli-contract.sh`、`m3-version-policy.sh`、`static-contract.sh`、`storage-contract.sh` 全部通过。
- `copperctl`、`copper-key-offline`、全部 `scripts/*.sh` 和 `tests/*.sh` 通过 Shell 语法检查。
- 发行目录再次执行 `validate-release`，x86_64 与 ARM64 主机均返回相同索引摘要和精确平台集合。

## 8. 完成判定与后续门槛

- [x] Web、Backend、MySQL 均提供 amd64/arm64 原生构建或原生导出工件。
- [x] 顶层 manifest、平台子摘要、归档 SHA-256、源码 revision、架构和运行时 ID 可审计。
- [x] 候选发行是严格闭集，可在目标主机完全离线导入镜像。
- [x] 真实 x86_64 与 ARM64 完成启动、重启、业务、凭据、备份、离线解密与清理。
- [x] x86_64 保持正式认证基线，ARM64 明确保留 candidate 状态。
- [x] 自动化回归和双平台证据清单全部通过。
- [ ] 正式 Tag / GitHub Release：M5-02 不单独发布，待 M5 总体验收决定。
- [ ] ARM64 生产支持：必须完成 M5 后续完整生命周期和长期稳定性门槛后另行评审，不能由 candidate 自动升级。
- [ ] 全新机器生命周期：M5-03 使用干净 Ubuntu 24 虚拟机副本验证从零安装、升级、备份、恢复、卸载和数据保留策略。

本验收候选绑定报告提交之前的 Deploy 实现提交。报告合入 `main` 只增加文档，不改变已验收运行代码；最终对外交付仍必须从精确 `main` 重新生成候选并记录新的 Deploy 提交、发行索引摘要和证据摘要，禁止复用本报告候选冒充最终发布物。
