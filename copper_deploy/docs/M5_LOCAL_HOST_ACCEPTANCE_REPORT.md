# Copper M5-03 本地主机生命周期验收报告

> 验收日期：2026-07-15
>
> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/62>
>
> 结论：Ubuntu Server 24.04 x86_64 的写入前预检、正式安装、systemd 自启动、整机重启、四层监控、普通卸载、受管重装、数据/密钥/备份保留和最终清理均通过。M5-03 的 Copper 管理范围可以关闭。

## 1. 最终支持边界

Copper 本地主机安装目标收敛为 Ubuntu Server 24.04 LTS x86_64。Windows 电脑可以作为虚拟化宿主机，但 Copper 仍只安装在独立 Ubuntu 24.04 x86_64 虚拟机内；Windows 原生、WSL2 和 Docker Desktop 不属于安装目标。

云服务器、Linux 实体机和 Windows 上的 Ubuntu 虚拟机共用同一套预检、Compose、systemd、备份和恢复合同。Windows 侧的虚拟机自动启动属于实施前置条件，不进入 Copper 安装器，也不要求 Copper 保存 Windows 管理员凭据。

## 2. 实测主机与不可变镜像

正向验收使用全新正式路径的 Ubuntu 24.04.4 LTS x86_64 主机：2 核、约 8 GiB 内存、ext4，本轮开始时约 35.7 GB 可用。Docker Engine 29.1.3、Compose Plugin 5.1.4，80/443/3306/8191 均无冲突。

三个运行镜像沿用 M5-02 已验证身份：

| 角色 | 不可变来源 / 本机离线内容 ID |
| --- | --- |
| Web | 来源 `ghcr.io/soyia/copper_web@sha256:13b288569886881ba4966acaff96b422ac69ea81942e49668ab20edde4564cb6`；运行 ID `sha256:3145b24c45c4f5dd9b11be664a23a295b8b68bdb47f3b08c6c38c8c790c5ac75` |
| Backend | 来源 `ghcr.io/soyia/copper_backend@sha256:9773420210c17284f738dd30c8657c75101a5a3895a47ad01b01b76d81f010ab`；运行 ID `sha256:13739b516cb4cab2c9308c43c8de00dc79b77fee597e83ad6b1753b031885bcb` |
| MySQL | `mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af` |

Web/Backend Actions 工件先在受控交付机核对 `SHA256SUMS`、`copper-image-bom/v1`、amd64 架构和源码 revision，再只把 amd64 归档传入目标机。目标机没有保存 GitHub Token。一次直接访问私有 GHCR 返回 `unauthorized` 后，业务未启动，也没有通过向客户机放置长期仓库凭据绕过；清理后改用已验证离线工件完成正式验收。

## 3. 写入前预检

空白主机执行 `copperctl host preflight --json`：

- 最终代码 20 项检查全部通过；
- `profile=ubuntu-24.04-x86_64`；
- `status=PASS`、`writeAllowed=true`；
- 预检前后 `/etc/copper`、`/var/lib/copper` 均不存在；
- 正式安装保存的 `/etc/copper/host-preflight.json` 为 `root:root 0600`。

真实 Ubuntu 24.04 ARM64 主机执行同一脚本返回退出码 3，`platform.architecture=FAIL`、`writeAllowed=false`。执行前后 `/etc/copper`、`/var/lib/copper` 均不存在，原有 `vm-mysql`、`vm-valkey` 始终保持 healthy，证明不支持的架构在写入前 fail-closed，且不停止未知业务。

自动化还覆盖网络文件系统、未知文件系统、80 端口冲突、3306/8191 复核、内存/磁盘不足、孤立数据、秘密不完整和受管重装等反向场景。

## 4. 安装、自启动与四层恢复

正式安装完成 MySQL 8.4.10、Flyway 5、Backend 和 Web 三层健康，Web 精确发布 80/443，Backend 与 MySQL 没有宿主机端口映射。首次管理员完成登录、强制改密和重新登录后，一次性凭据文件自动销毁。

安装器随后启用 `copper.service`。真实执行一次主机重启后，无需人工按顺序启动：

```text
Result=success
ActiveState=active
SubState=exited
```

验收脚本等待 systemd 完成启动门槛，避免 SSH 已恢复但应用仍处于 `activating` 时误报失败。重启后管理员仍可使用改密后的口令登录，一次性凭据没有恢复；监控四层均为 `NORMAL`：

- 存活 `NORMAL`；
- 就绪 `NORMAL`；
- 可写 `NORMAL`；
- 可恢复 `NORMAL`。

“可恢复”在首次运行时曾正确阻断，因为只有备份包校验、尚未登记离线解密恢复回执。补充实际离线解密和回执登记后才转为 `NORMAL`，没有通过编辑监控状态绕过门槛。

本轮正常重启之外，异常断电后的 MySQL/Backend/业务恢复门槛继续引用已经完成的 [M4-06 故障恢复验收](M4_FAULT_RECOVERY_ACCEPTANCE_REPORT.md)。本地主机与虚拟机不得启用营业期间自动睡眠；唤醒或宿主机恢复后仍必须以 `monitor check` 判定业务状态，不能只看虚拟机窗口。

## 5. 卸载、重装与数据保留

安装后先创建客户公钥加密的 `copper-backup/v1`，完成包校验、离线私钥解密和恢复回执登记。随后执行普通 `uninstall`：

- Compose 容器和网络被移除；
- `copper.service` 被停用并删除；
- `/etc/copper`、`/var/lib/copper`、`/var/backups/copper` 保留；
- 数据库业务口令和 root 口令的文件哈希保持不变。

同一发布目录重新执行预检时返回 `status=PASS`、`writeAllowed=true`、`data.existingState=managed-reinstall`。重装后原管理员账户继续登录，首次管理员凭据没有重新生成，三层健康、端口边界和原加密备份再次校验通过。

最终使用独立危险删除流程完成清理；测试配置、数据库、附件、备份、测试私钥、测试口令、容器、网络、systemd unit、离线镜像和临时发布目录均已移除。验收机最终无本轮 80/443/3306/8191 监听，只保留 root-only 脱敏证据。

## 6. 自动化与证据

- 本地主机预检 Python 单元测试：12 项通过；
- Deploy 全量 Python 回归：117 项通过；
- Deploy 静态合同：通过；
- 正向阶段：`installed`、`reboot-recovered`、`data-reattached`、`cleanup` 全部 `PASS`；
- `doctor`：通过 46、提醒 2、失败 0；
- root-only 最终证据目录：`/opt/copper-m5-03-acceptance/evidence`；
- 证据 `SHA256SUMS` 文件摘要：`7a363bf3078308949a2ece1ed805f6f83ebdf45d1a5bf792c93cb5296484c084`。

证据目录不保存主机地址、SSH 私钥、GitHub Token、管理员口令、Cookie、age 私钥或业务明细。

## 7. 完成判定

- [x] OS、版本、架构、虚拟化、资源、文件系统和运行时预检。
- [x] ARM64、端口冲突、网络盘和未知数据在写入前 fail-closed。
- [x] 只发布 Web 80/443，Backend 8191 和 MySQL 3306 不暴露。
- [x] 数据目录固定为可靠本地文件系统，SMB/NFS 被拒绝。
- [x] Ubuntu systemd 自启动、真实重启和四层监控通过。
- [x] 卸载保留数据，重装识别 managed-reinstall，秘密不被覆盖。
- [x] 加密备份、离线解密、恢复回执和重装后再次校验通过。
- [x] 永久删除保持独立双重确认，最终清理无遗留。
- [x] Windows 部署口径收敛为“Windows 宿主 + Ubuntu 24.04 x86_64 虚拟机”，不维护第二套 Windows/WSL2 安装器。
