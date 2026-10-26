# Copper M3 升级预检与备份准备

> 状态：M3-02 已实现；只建立升级前门槛，不执行迁移、镜像切换或版本变更。

## 1. 能力边界

M3-02 把“可以开始升级”变成可验证状态，而不是把一组新镜像直接交给 Compose：

1. `copper-release/v1` 必须逐字段通过严格校验，并以 SHA-256 绑定同目录的 `copper-upgrade-matrix/v1`。
2. 升级矩阵必须逐项列出当前产品版本和当前 Flyway/schema；版本范围、推测兼容和未实测来源一律拒绝。
3. 预检同时检查运行平台、MySQL 8.4 LTS、CPU、内存、磁盘、三层健康、首次管理员凭据销毁、备份任务状态、客户恢复密钥离线实证和服务器无私钥审计。
4. `prepare` 必须重新生成一份完整加密一致性整站备份，执行正式 `backup verify`，再把目标清单、`READY` 收据、升级矩阵和备份证据绑定为 root-only `PREPARED` 记录。
5. M3-02 不运行 Flyway、不拉取或导入新镜像、不停止 MySQL、不改变当前产品版本。M3-03 只有消费完全相同的准备记录后，才可以进入隔离迁移状态机。

这不是大型 ERP 式审批流程。小微客户只需看到明确的“未准备/已准备”和可执行修复原因，但数据正确性门槛不能被简化掉。

## 2. 生成与校验发布契约

生成升级矩阵时，每个来源都必须来自真实演练：

```bash
scripts/release-contract.py generate-matrix \
  --target-version 0.2.0-rc.1 \
  --target-schema 4 \
  --source 0.1.0-m2-rc.2,3,retained-sidecar,maintenance-window,retained-sidecar-or-verified-backup \
  --output /安全发布目录/copper-upgrade-matrix-0.2.0-rc.1.json
```

准备 `config/release-manifest-v1.fields` 中除格式和矩阵绑定字段之外的值，然后生成最终清单：

```bash
scripts/release-contract.py generate-manifest \
  --values /安全发布目录/release-values.env \
  --matrix /安全发布目录/copper-upgrade-matrix-0.2.0-rc.1.json \
  --output /安全发布目录/copper-release-0.2.0-rc.1.env

copperctl release validate /安全发布目录/copper-release-0.2.0-rc.1.env
```

`scripts/build-release.sh` 对 M3 起的标准 SemVer 自动走上述最终契约，并要求版本目录内存在 `upgrade-matrix.json` 和 `RELEASE_NOTES.md`。历史 M1/M2 标签和旧清单保持不可变。

清单拒绝未知字段、重复字段、可移动镜像标签、秘密字段、非 UTC 发布时间、平台漂移、schema 不一致和矩阵 SHA-256 不一致。矩阵拒绝重复来源、版本范围、来源不早于目标，以及 schema 变化时的 `image-only` 升级或回退。

## 3. 登记当前安装版本

升级预检只信任 root 持有的 `/etc/copper/copper.env`：

```text
COPPER_RELEASE_VERSION=0.1.0-m2-rc.2
```

从既有 M2 站点过渡时，必须按当时实际安装的 Copper Deploy 发布包填写。镜像相同不能证明产品版本相同；无法确认时停止并查原始交付清单，禁止按日期或感觉猜测。新安装由对应发布包写入该值。

## 4. 执行只读预检

```bash
sudo copperctl upgrade preflight /安全发布目录/copper-release-0.2.0-rc.1.env
sudo copperctl upgrade preflight /安全发布目录/copper-release-0.2.0-rc.1.env --json
```

预检不会创建备份或写 `PREPARED`。以下任一项失败都会非零退出：

- 清单或矩阵格式、摘要、目标版本、目标 schema 不正确；
- 当前产品版本与当前 schema 没有作为一条精确来源出现；
- Ubuntu 24.04 x86_64、Docker/MySQL 或不可变镜像配置不满足门槛；
- CPU、内存或“数据目录与备份目录两者较小的剩余空间”不足；
- MySQL、Backend、Web 任一层未健康；
- 首次管理员明文一次性凭据仍存在；
- 最近备份任务不是 `SUCCESS`、存在连续失败、状态与实际归档不一致；
- 客户 age 恢复密钥没有完成至少一次真实备份的离线解密验证回执；
- 服务器密钥审计发现私钥或登记簿与当前公钥不一致。

空间要求取目标发布最低空间与“重新生成完整备份所需估算空间”中的较大值，不能用压缩后的历史备份大小替代。

## 5. 生成升级准备证据

```bash
sudo copperctl upgrade prepare /安全发布目录/copper-release-0.2.0-rc.1.env
sudo copperctl upgrade status
sudo copperctl upgrade status --json
```

固定顺序为：再次预检 → 创建新的 `copper-backup/v1` → 恢复三层健康 → `backup verify` → 核对 `last-success.json` 和实际归档 → 原子写入：

```text
/var/lib/copper/upgrades/prepared.json
```

记录权限固定为 `root:root 0400`，包含当前/目标版本和 schema、清单、`READY` 与矩阵 SHA-256、精确来源合同、容量证据、备份 ID、绝对路径、字节数、归档 SHA-256、接收公钥指纹、校验时间和 `last-success.json` 摘要。它不包含私钥、密码、令牌、客户业务数据或解密证明原文。

目标清单、`READY` 收据、矩阵、Deploy 归档、发布说明和固定版本的 acme.sh 归档还会按原文件名复制到 `/var/lib/copper/upgrades/prepared-contract/` 并设为只读，避免原下载目录被清理后只剩摘要、后续却找不到实际发布物或离线镜像身份。`upgrade migrate` 只从这份已绑定的 `READY` 副本解析目标镜像；`upgrade status` 会重新核对这些文件和准备备份的实际 SHA-256。任何事后删除或篡改都会使状态检查失败，不能继续 M3-03。

存在未消费的 `PREPARED` 时，重复 `prepare` 在创建新备份前即拒绝。不得手工删除或修改该记录来更换目标；后续由 M3-03 状态机消费、归档并保持审计链。

## 6. 失败与恢复

- 预检失败：无备份、无准备记录、无 schema/镜像变化，按错误提示修复后重跑。
- 新备份失败或中断：沿用 M2 备份恢复门槛，Web/Backend 必须自动恢复健康；不得写入 `PREPARED`。
- 最近备份任务失败：即使业务已经恢复健康，下一次预检仍拒绝。先修复原因并成功执行一次普通备份，再重新准备升级。
- 备份外层校验或证据绑定失败：保留诊断，拒绝 `PREPARED`。不能拿较早备份或只改 JSON 绕过。
- 已 `PREPARED` 但尚未进入 M3-03：当前业务仍运行旧版本，不需要回滚。

运维人员不得用旧镜像重启、逆向 SQL 或手工改 `flyway_schema_history` 代替后续正式状态机。

## 7. 隔离验收

Mac 不运行容器验收。使用：

```bash
sudo tests/run-m3-upgrade-preflight-local-database.sh
```

脚本只在 `local-database` ARM 隔离补充环境运行，使用 `/tmp/copper-m3-02-*`、独立 Compose 项目和宿主机 `18089`；它不会占用 8191，并在退出时删除测试容器、目录、回执和测试私钥。脚本前后必须确认共享 `vm-mysql`、`vm-valkey` 的容器身份和运行状态未变化。

`0.2.0-rc.1` 在脚本中只作为契约测试目标，不创建 Tag/Release，也不代表 M3 已发布。
