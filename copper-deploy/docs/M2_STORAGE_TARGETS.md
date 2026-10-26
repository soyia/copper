# M2-05 存储目标与容量治理

> 状态：已实现；最终对外完成状态以 `local-database` 三目标运行时验收和 PR 合并记录为准
> 备份格式：`copper-backup/v1`，存储适配器不得修改包内任何字节

## 1. 支持边界

Copper V1 始终在客户服务器本地目录保留一份恢复主副本，并可把同一 `.copperbak` 和 `.sha256` 复制到：

1. 客户服务器本地目录；
2. 客户操作系统已经稳定挂载的独立 ext4/xfs/btrfs 外接盘；
3. 客户操作系统已经稳定挂载的 NFS/NFS4/CIFS/SMB3 NAS；
4. 使用 Path-style API 和 AWS Signature V4 的 S3 兼容对象存储。

三个目标中的 `.copperbak` 字节数和 SHA-256 必须一致。NAS 挂载、对象存储账号、供应商配额、流量费和网络质量属于客户与第三方供应商责任，不属于 Copper 免费资源或软件套餐容量。

## 2. 配置

本地目录必须排在第一位：

```text
COPPER_BACKUP_TARGETS=local,external,nas,s3
COPPER_BACKUP_DIR=/var/backups/copper
```

外接盘和 NAS 必须在首次备份前登记稳定身份：

```text
COPPER_BACKUP_EXTERNAL_DIR=/mnt/copper-external-backups
COPPER_BACKUP_NAS_DIR=/mnt/copper-backups
```

```bash
sudo copperctl storage enroll external --confirm /mnt/copper-external-backups
sudo copperctl storage enroll nas --confirm /mnt/copper-backups
```

登记生成的随机身份同时写入介质和 `/etc/copper/copper.env`。缺盘、错误共享或换盘时身份不一致会失败关闭，禁止回退到同名本地空目录。

NAS 由操作系统管理员提前挂载，Copper 不保存 NAS 用户口令，也不负责执行 `mount`：

```text
COPPER_BACKUP_NAS_DIR=/mnt/copper-backups
```

生产环境只接受 NFS/NFS4/CIFS/SMB3 文件系统。每次备份前都会检查挂载类型、写权限、可用空间和同文件系统原子改名；断线后如果目录退化为本机空目录，预检查会拒绝备份，避免把“NAS 备份”误写到系统盘。

S3 兼容目标：

```text
COPPER_BACKUP_S3_ENDPOINT=https://s3.example.com
COPPER_BACKUP_S3_REGION=us-east-1
COPPER_BACKUP_S3_BUCKET=copper-backups
COPPER_BACKUP_S3_PREFIX=copper-prod
COPPER_BACKUP_S3_ACCESS_KEY_FILE=/etc/copper/storage-credentials/s3_access_key
COPPER_BACKUP_S3_SECRET_KEY_FILE=/etc/copper/storage-credentials/s3_secret_key
```

- Bucket 必须由客户提前创建并配置最小权限、版本控制和生命周期规则。
- 生产 Endpoint 必须使用 HTTPS；HTTP 只允许隔离测试。
- 访问密钥文件必须为 `root:root`、`0600` 或更严格，目录 `/etc/copper/storage-credentials` 不挂载进业务容器。
- 临时会话凭据可另设 `COPPER_BACKUP_S3_SESSION_TOKEN_FILE`。
- 不把访问密钥写入环境值、命令行、日志、镜像或 Git。

## 3. 统一存储接口

`scripts/storage-target.py` 对本地、外接盘、NAS、S3 提供相同操作语义：

- `preflight`：连接、权限、空间和目标能力预检查；
- `publish`：写入、校验并发布成功边界；
- `fetch`：读取到本地目录并重新校验；
- `list`：只列举已经发布成功标记且可验证的备份；
- `verify`：校验字节数、SHA-256、sidecar 和成功标记；
- `delete`：先撤销成功可见性，再受控删除对象。

文件系统目标先在目标目录写临时文件、`fsync`，再用 `os.replace` 原子发布。S3 先上传临时对象，校验对象大小和 `x-amz-meta-sha256`，复制为最终对象，最后写成功标记；失败时不会留下可被 `list` 识别为成功的备份。

日常命令：

```bash
sudo copperctl storage status
sudo copperctl storage list
sudo copperctl storage list nas
sudo copperctl storage verify s3 文件名.copperbak SHA256 字节数
sudo copperctl storage fetch s3 文件名.copperbak /受控恢复目录
sudo copperctl storage delete nas 文件名.copperbak --confirm 文件名.copperbak
```

删除命令需要目标、完整文件名和相同确认值。整站保留策略会把同一已过期备份从全部已配置目标删除；任一目标不可达时不继续假装保留策略成功。

## 4. 容量口径

`copperctl storage status` 生成 `copper-capacity/v1`，并原子写入：

```text
${COPPER_VAR_DIR}/backend/runtime/storage-capacity.json
```

后端只读该文件，再用当前 `/app/data` 文件系统实际可用空间校正风险。界面展示：

- MySQL 数据目录物理字节数；
- 原始附件、完整业务导出、成功备份和日志占用；
- 本地/NAS 文件系统总容量和可用容量；
- S3 成功备份占用；
- 最近成功整站备份和状态观测时间。

S3 通常无法通过标准接口可靠获取客户供应商配额，因此剩余容量固定显示“由对象存储供应商配额决定”，不得显示“无限”。

容量保护级别：

| 级别 | 判定（任一满足） | 行为 |
| --- | --- | --- |
| 正常 | 不满足以下条件 | 正常业务与备份 |
| 预警 | 可用空间 `<20%` 或 `<5 GiB` | 提醒清理或扩容，核心业务继续 |
| 严重 | 可用空间 `<10%` 或 `<2 GiB` | 阻止向该低容量目标备份；数据盘严重时阻止完整业务导出和附件上传 |
| 危险 | 可用空间 `<5%` 或 `<1 GiB` | 保留查询、登录和修复通道，受控停止业务写入 |

普通采购、销售和资金业务在“严重”级仍可继续，避免过早中断小微经营；只有达到“危险”级才停止业务写入。危险时仍允许把已有数据救援备份到另一个通过预检查的独立健康目标。不得通过删除 MySQL 数据文件、附件原件或审计记录来解除告警。

## 5. 故障处理

- NAS 断线或退化为本地目录：修复系统挂载后运行 `sudo copperctl storage status`，不要在退化目录中继续备份。
- NAS 只读/权限变化：由系统管理员恢复挂载权限；Copper 不自动放宽权限。
- S3 超时：核心业务可继续，备份任务失败且不更新最后成功时间；检查网络、Endpoint 和供应商状态。
- S3 凭据错误：更换 `/etc/copper/storage-credentials` 下的 root-only 文件，不在日志或工单粘贴密钥。
- 容量不足：优先扩容；如需清理，先用 `backup prune --dry-run` 或受控 `storage delete`，始终保留最近成功备份。

## 6. 验收

仓库提供：

```bash
sudo env COPPER_STORAGE_TEST_BACKEND_IMAGE=sha256:... \
  tests/run-m2-storage-local-database.sh
```

脚本仅允许在 `local-database` ARM 隔离环境运行，禁止 8191，且会核对共享 `vm-mysql`、`vm-valkey` 容器身份。它覆盖真实 MySQL 8.4、bind-mount NAS、固定摘要 MinIO、三目标同字节/SHA、读取校验、容量 API、危险写入保护、NAS 断线、只读、配额不足、S3 凭据错误和超时；退出时清理测试容器、挂载与数据目录。

本项不替代 M2-07 的空白 x86_64 客户云跨环境恢复和迁移最终验收。
