# M5-02 多架构镜像与离线发行合同

## 结论与边界

M5-02 在不修改任何既有 `copper-release/v1`、`copper-delivery/v1`、发布标签、镜像摘要和 M4 amd64 离线介质的前提下，新增 `copper-multiarch-release/v1` 候选发行合同。历史 Ubuntu 24.04 amd64 升级、回滚、整站备份和恢复继续按 M4 合同执行；M5 多架构合同不能反向覆盖历史发布。

当前平台级别固定为：

| 平台 | 级别 | 含义 |
| --- | --- | --- |
| `linux/amd64` | Certified（镜像架构） | 继承 M4 已通过的 x86_64 镜像生命周期基线；不自动证明本地/NAS/Windows VM平台兼容 |
| `linux/arm64` | Candidate | 只允许候选验证；必须完成 M5-03 真实 Apple Silicon/ARM NAS 生命周期后才能提升 |

禁止把 GitHub Actions 构建成功、QEMU 运行成功或单个容器能够启动解释为 ARM64 正式支持。

> M5-03 后续产品决策补记：当前本地主机安装目标已收敛为 Ubuntu 24.04 x86_64；Windows 仅作为 Ubuntu x86_64 虚拟机宿主。ARM64 工件继续保留 `Candidate` 发行证据，不接入当前正式安装器，也不因 M5-03 完成而提升为生产支持。

> M7-06 首发范围补记：`1.0.0` 只拟支持客户自有公有云 Ubuntu 24.04 x86_64。`linux/amd64` 的 `Certified` 是镜像架构和云端技术合同，不表示 Ubuntu本地实体机、Windows宿主VM或任意NAS已经通过；这些平台已移出首发。

## 构建与发布关闭规则

Web 和 Backend 均使用两个原生 GitHub runner：

- `ubuntu-24.04` 只构建 `linux/amd64`；
- `ubuntu-24.04-arm` 只构建 `linux/arm64`；
- 每个平台先发布临时的来源提交标签和不可变子 digest；
- 两个平台都成功并上传各自归档后，汇总作业才创建共同顶层 manifest；
- 任一 runner、子 digest、离线归档、内容 ID 或校验和缺失，汇总作业失败，不得发布“多架构完成”；
- PR 只测试和构建，不向 GHCR 发布；只有 `main` 或明确的人工发布输入可以写入 GHCR；
- 所有运行与交付引用都必须包含 `@sha256:`，禁止 `latest`。

Web 镜像同时承载静态前端和 Caddy 反向代理，因此代理架构与 Web 镜像一同受控。Backend Dockerfile frontend、Node/Caddy、Maven/Temurin 与 MySQL 来源均保持版本或 digest 可追溯。

## Actions 工件

每个应用的最终 Actions 工件必须同时包含：

```text
copper-<role>-amd64.tar.gz
copper-<role>-arm64.tar.gz
image-platform-amd64.json
image-platform-arm64.json
image-bom.json
SHA256SUMS-amd64
SHA256SUMS-arm64
SHA256SUMS
```

`image-bom.json` 使用 `copper-image-bom/v1`，绑定源码仓库、40 位源码提交、工作流编号、顶层 manifest digest、两个平台子 digest、生产端内容 ID、归档字节数和 SHA-256。归档固定使用 `gzip -n`，文件名不得携带可变标签。

生产端 `localId` 是 CI Docker 镜像存储的构建证据，不是跨 Docker graphdriver/containerd 存储可移植的运行时 ID。同一份 `docker save` 归档在 Docker 29 containerd 存储导入后可能以 OCI manifest digest 作为 `.Id`，不能错误要求它等于生产端 config ID。跨主机的稳定身份是归档 SHA-256、平台子 digest、来源 revision 和受控归档标签；实机导入后必须重新记录 `runtimeId`，并让 Compose、备份与恢复绑定该主机的 `runtimeId`。

## 组装完整候选发行

先分别验证 Web/Backend Actions 工件：

```bash
scripts/multiarch-contract.py validate-app \
  --bom /secure/web/image-bom.json

scripts/multiarch-contract.py validate-app \
  --bom /secure/backend/image-bom.json
```

MySQL 必须从已固定的官方 8.4 LTS 顶层 manifest 解析两个平台子 digest，并在对应真实架构主机拉取、检查架构、记录本地内容 ID，再分别导出：

```bash
sudo scripts/export-mysql-platform.sh \
  'mysql:8.4.10@sha256:<顶层manifest摘要>' \
  linux/amd64 \
  /secure/mysql-amd64

sudo scripts/export-mysql-platform.sh \
  'mysql:8.4.10@sha256:<同一顶层manifest摘要>' \
  linux/arm64 \
  /secure/mysql-arm64
```

两个命令必须分别在真实 x86_64 与真实 ARM64 主机执行；脚本会拒绝跨架构导出、非固定补丁版本、`latest`、非 OCI 索引、重复平台子项和非空输出目录，并生成平台证据及校验清单。

```text
copper-mysql-amd64.tar.gz
copper-mysql-arm64.tar.gz
```

之后组装只读候选目录：

```bash
scripts/multiarch-contract.py assemble \
  --version 0.4.0-m5-rc.1 \
  --created-at 2026-07-15T00:00:00Z \
  --deploy-commit <40位Deploy提交> \
  --web-bom /secure/web/image-bom.json \
  --backend-bom /secure/backend/image-bom.json \
  --mysql-version 8.4.10 \
  --mysql-image 'mysql:8.4.10@sha256:<顶层manifest摘要>' \
  --mysql-platform 'linux/amd64,sha256:<子摘要>,sha256:<内容ID>,/secure/mysql/copper-mysql-amd64.tar.gz' \
  --mysql-platform 'linux/arm64,sha256:<子摘要>,sha256:<内容ID>,/secure/mysql/copper-mysql-arm64.tar.gz' \
  --output-dir /secure/copper-0.4.0-m5-rc.1-multiarch
```

完整目录只允许一个发行索引、六个分架构镜像归档和一个 `SHA256SUMS`。交付前再次验证：

```bash
scripts/multiarch-contract.py validate-release \
  --index /secure/copper-0.4.0-m5-rc.1-multiarch/copper-multiarch-release-0.4.0-m5-rc.1.json
```

发行索引自身的 SHA-256 必须通过 GitHub Release、双方确认工单或签字交接单等独立可信渠道提供，不能只把摘要放在同一个可整体替换的下载目录中。

## 与升级、回滚和备份的关系

`copper-multiarch-release/v1` 必须绑定：

- Deploy 的 40 位 Git 提交；
- 历史 `copper-release/v1` 与 `copper-delivery/v1` 兼容边界；
- `copper-upgrade-state/v1` 升级状态合同；
- `copper-backup/v1` 整站备份合同；
- Web、Backend、MySQL 的顶层 manifest、平台子 digest 和离线内容 ID。

这意味着升级或回滚只能选择发行索引中同一平台的三个组件，不能把 amd64 与 arm64 内容 ID 混装，也不能用仓库标签重新解析镜像。数据库 schema、升级矩阵、备份先行、失败恢复和 READY 收据仍沿用 M4 门槛；M5-03 才负责把该选择逻辑接入本地设备安装与完整生命周期。

## 实机验收门槛

M5-02 关闭前必须在两台隔离真实主机执行同一套检查：

1. 主机 `uname -m` 与所选平台一致；
2. 六个归档的 `SHA256SUMS` 和发行索引全部通过；
3. 导入后按受控归档标签核对架构和来源 revision，记录主机 `runtimeId`，并与发行索引中的生产端 ID、平台 digest 和归档 SHA-256 建立映射；
4. Compose 不暴露 Backend 8191 和 MySQL 3306，只暴露 Web 80/443；
5. MySQL、Backend、Web 全部健康，`/healthz`、`/readyz` 与登录/核心查询冒烟通过；
6. 运行配置只引用本平台内容 ID；
7. 完成一次备份，并证明回滚/恢复仍按同一发行版本和平台绑定；
8. 清理测试数据和临时凭据，不影响用户正式 8191 服务。

完整候选目录组装后，两台主机使用同一脚本验收：

```bash
sudo tests/run-m5-multiarch-host-acceptance.sh \
  /secure/copper-0.4.0-m5-rc.1-multiarch/copper-multiarch-release-0.4.0-m5-rc.1.json \
  <通过独立渠道取得的索引SHA-256> \
  <本机验收域名>
```

脚本在启动前拒绝已占用的 80、443；运行时只从索引选择本机架构对应的 Web、Backend、MySQL 内容 ID，并通过 Copper 容器自身的 `PortBindings` 证明 Backend 8191 与 MySQL 3306 没有宿主机映射。这样不会把同机其他系统已有的 8191/3306 监听误判为 Copper 泄露，也不会停止或覆盖它们。结束时脚本停止 Compose 并删除隔离数据库、附件、秘密和备份，只保留 root-only 脱敏证据。`.localhost` 域名只允许 ARM64 候选内网验收并使用 Caddy 本地证书；x86_64 认证验收仍使用可验证的真实域名证书。

真实主机固定为已授权的 `local-database` ARM64 与新加坡 Ubuntu 24.04 x86_64。开发 Mac 禁止运行 Docker、Colima 或 Docker Desktop。
