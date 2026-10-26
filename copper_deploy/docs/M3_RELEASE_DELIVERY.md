# M3 联网/离线不可变发布物与升级介质

M3-04 解决“目标版本怎样安全到达客户服务器”，不负责决定客户何时升级，也不自动迁移或切换生产。完整联网/离线交付使用 `copper-delivery/v1`；已有精确来源版本的离线升级可使用 `copper-upgrade-delivery/v1` 组件差异包。两者最终都在客户主机写入 root-only `copper-delivery-ready/v1` 收据。

## 1. 交付合同

交付索引精确绑定：

- 产品版本、`ubuntu-24.04/amd64` 和 `online`/`offline` 模式；
- `copper-release/v1` 发布清单；
- Deploy 归档、升级矩阵、发布说明和 ACME 归档的文件名、字节数与 SHA-256；
- Web、Backend、MySQL 三张 `registry@sha256` 镜像及 `linux/amd64` 平台；
- 离线模式额外绑定镜像归档的文件名、字节数、SHA-256，以及归档内三张镜像稳定的 `sha256` 内容 ID。

交付目录只允许索引声明的文件，符号链接、目录、未知文件、缺失文件、路径穿越、重复角色和摘要不一致全部拒绝。发布说明固定包含“功能变化、数据库影响、已知问题、升级步骤、停机与回退”五个章节。

交付索引本身的 SHA-256 必须通过另一个可信渠道交给客户，例如 GitHub Release 页面、签字交接单或双方已确认的工单。不能把索引与它自己的摘要放在同一可被整体替换的下载目录后就视为可信。

## 2. 发布方生成介质

`scripts/build-release.sh` 先从已经评审合入并带精确正式标签的 Deploy 提交生成发布清单及其绑定文件。随后生成联网目录：

```bash
scripts/build-release-delivery.sh online \
  dist/copper-release-<version>.env \
  dist/copper-delivery-online-<version>
```

该目录可以作为只读 HTTPS 静态内容发布。发布方必须单独记录命令输出的索引 SHA-256。

生成离线介质必须在可访问镜像仓库且 Docker 可用的受控构建机执行：

```bash
sudo scripts/build-release-delivery.sh offline \
  dist/copper-release-<version>.env \
  /安全介质/copper-delivery-offline-<version>
```

脚本只拉取发布清单中的精确摘要，验证 `linux/amd64`，再把三张镜像放入同一受索引约束的归档。正式模式禁止本地标签、`latest` 或 ARM64 镜像。

若客户服务器已经运行一个有完整离线索引的精确来源版本，可生成组件差异升级包：

```bash
sudo scripts/build-release-delivery.sh offline-delta \
  dist/copper-release-<target-version>.env \
  /安全介质/copper-upgrade-<base>-to-<target> \
  /受控归档/copper-delivery-offline-<base>/copper-delivery-offline-<base>.json
```

构建器仍会核对三张目标镜像，但最终介质只包含内容 ID 变化的组件。未变化组件在索引中保留来源引用、来源本机 ID、目标引用和目标本机 ID，并明确标为 `reuse`。即使三个组件都不变，Deploy、升级矩阵、发布说明、ACME 工具和发布清单仍需进入闭集差异包。

## 3. 客户服务器联网暂存

客户从独立可信渠道取得索引 URL 和 SHA-256 后执行：

```bash
sudo copperctl release stage online \
  https://可信发布地址/copper-delivery-online-<version>.json \
  <index-sha256>
```

生产只接受无用户名、密码、查询参数和片段的 HTTPS URL。索引和全部文件先下载到 root-only 临时目录；完整验证后才按发布清单拉取三张摘要镜像。任何下载、摘要或镜像失败都不会产生 `READY`，不会修改 `/etc/copper/copper.env`、数据库、附件、容器或升级状态。

## 4. 客户服务器离线暂存

在联网中转机生成离线介质并通过 U 盘、移动硬盘或受控内网传到客户服务器。目标机执行：

```bash
sudo copperctl release stage offline \
  /安全介质/copper-delivery-offline-<version>/copper-delivery-offline-<version>.json \
  <index-sha256>
```

脚本在 `docker load` 前先验证整个目录；导入后再次验证三张镜像存在、正式架构为 amd64，并将归档内容 ID 与交付索引逐项核对。交付索引同时保留上游仓库发布摘要，因此发布身份和离线运行身份可双向审计。离线路径不执行 `curl` 或 `docker pull`，不需要 GHCR Token，也不把任何仓库凭据写入介质或服务器配置。

## 5. READY 与后续升级

成功交付位于：

```text
${COPPER_VAR_DIR}/releases/<version>/
```

目录权限为 `0700`，文件和 `ready.json` 为 `0400`。收据记录交付模式、UTC 时间、索引摘要、发布清单摘要以及三张镜像的发布引用、本机不可变 ID 和架构。

查看状态：

```bash
sudo copperctl release status
sudo copperctl release status <version> --json
```

同版本同索引重复暂存是幂等操作。同版本不同索引、READY 内容或权限被修改、文件被替换时必须停止并由管理员调查，不能覆盖原证据。`release status` 还会复核本机镜像 ID 和架构；联网模式同时确认三条发布摘要引用仍解析到收据中的同一镜像，离线模式则严格按索引绑定的归档内容 ID 验证，镜像被删除后不得继续显示可升级。正式升级运行配置只从 root-only READY 收据取得离线内容 ID，不再访问仓库。

组件差异包在目标机使用：

```bash
sudo copperctl release stage offline-delta \
  /安全介质/copper-upgrade-<base>-to-<target>/copper-upgrade-delivery-offline-delta-<base>-to-<target>.json \
  <index-sha256>
```

暂存前必须先验证当前 `COPPER_RELEASE_VERSION` 等于差异包来源版本，并逐项读取当前 Web、Backend、MySQL 的真实本机镜像 ID。若服务器还保留该来源版本的 READY 交付记录，其索引 SHA-256 也必须与差异包声明的来源索引一致；首次由一站式安装器建立、尚无 READY 的站点则继续以版本和三项不可变镜像身份为来源门槛。任何一项不一致都拒绝拼接；操作者必须改用与当前现场匹配的差异包或完整离线包。差异仅适用于程序镜像传输，绝不对在线 MySQL 数据目录、附件或备份密文做二进制补丁。

`READY` 只表示发布介质完整可用。标准升级仍按以下顺序执行：

1. `release status <version>` 复核 READY；
2. `upgrade preflight` 与 `upgrade prepare`；
3. `upgrade migrate` 隔离迁移；
4. 客户在可信终端人工 `upgrade switch`；
5. 验收后创建并离线验证新版本整站备份；再由可信终端执行 `upgrade accept`，释放旧回滚副本并解锁下一次升级。

## 6. M3-04 边界

- 不创建真实 `0.2.0-rc.1` 标签或 GitHub Release；
- 不执行数据库迁移、版本切换或生产停机；
- `COPPER_DELIVERY_TEST_*` 只允许 `COPPER_TEST_MODE=1` 的隔离验收，不能进入正式介质；
- `local-database` ARM64 运行只证明交付编排和失败保护，正式 amd64 介质仍由后续 x86_64 发布验收确认；
- M3-05 才使用正式 READY 介质完成真实结构变化的跨版本升级与新机器恢复。

M3-05 标签前可以使用 `scripts/build-release.sh --candidate` 冻结待验收字节，但候选目录不是 Release，不能向客户交付。最终验收通过、同一 Deploy 提交创建正式标签后，必须重新执行正式构建和联网/离线交付，并证明所有被发布清单绑定的文件与候选逐项同 SHA-256；正式 READY 才是可交付状态。具体流程见 [M3-05 正式 x86_64 升级与空白恢复验收](M3_FINAL_X86_ACCEPTANCE.md)。
