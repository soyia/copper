# M5 本地主机安装、启动与数据生命周期

> M7-06 最终范围决定（2026-07-18）：本文件中的 Ubuntu本地主机和Windows宿主Ubuntu VM均为未来版本 `Candidate/Deferred`，已移出 `1.0.0` 首发标准兼容清单。本文件保留工程实现与未来复验步骤，不构成当前 `Certified`、客户交付或 `¥649起` 服务授权。详见 [M7-06范围决定](M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md)。

## 1. 支持边界

M5 本地主机候选只有以下运行环境：

- Ubuntu Server 24.04 LTS；
- x86_64/amd64；
- Docker Engine 29.x 与 Compose Plugin 5.x；
- 至少 2 核 CPU、4 GiB 内存、20 GiB 可用本地磁盘；
- `/var/lib/copper` 位于 ext4、xfs 或 btrfs 的可靠本地块存储。

Windows 电脑可以作为虚拟化宿主机，但 Copper 只安装在独立的 Ubuntu 24.04 x86_64 虚拟机内。Windows 原生、WSL2、Docker Desktop、macOS、ARM64 Linux、SMB/NFS 在线数据库盘均不在本里程碑支持范围。这样云服务器、Linux 实体机和 Windows 上的 Ubuntu 虚拟机使用完全相同的发布包、备份格式和运维命令。

Windows 宿主机需要由实施人员设置“宿主机启动后自动启动 Ubuntu 虚拟机”。Copper 安装器不修改 Windows，不保存 Windows 管理员凭据，也不依赖用户登录桌面。

## 2. 写入前 fail-closed 预检

正式安装前先运行：

```bash
sudo ./copperctl host preflight --json
```

预检只读取系统状态，输出 `copper-local-host-preflight/v1`。只有 `status=PASS` 且 `writeAllowed=true` 才可安装；`FAIL` 和 `MANUAL_REVIEW` 均返回退出码 3。检查项包括：

- OS、精确版本、CPU 架构与虚拟化类型；
- CPU、内存、数据盘可用容量；
- 数据路径、符号链接、读写状态、是否可移除、文件系统类型；
- Docker、Compose、systemd、Docker 自启动和时间同步；
- 80/443 冲突，以及宿主机 3306/8191 监听复核；
- 数据目录是全新、受管重装，还是配置缺失的孤立数据。

预检失败不得创建配置、密钥、数据库目录或启动容器。`install` 会再次执行预检，通过后才调用主机准备脚本，并把报告保存到 `/etc/copper/host-preflight.json`，权限为 `root:root 0600`。

发现 `/var/lib/copper` 已有内容但缺少 `/etc/copper/copper.env` 时，必须阻断为 `orphaned-data`。不得生成新密钥覆盖未知数据库；先确认数据归属，再选择恢复或人工接管。

## 3. 网络与数据盘

Copper 只向宿主机发布 Web 的 80/443。Backend 8191 和 MySQL 3306 留在 Compose 内部网络，不得发布到 Ubuntu 宿主机或局域网。

本地主机面向多人访问时必须使用 [M5-04 LAN 安全基线](M5_LAN_ACCESS.md)：固定/DHCP 保留的私网地址、客户控制域名的受控 DNS、客户 CA 签发证书和按允许网段收口的 Docker 防火墙。`.local`、hosts、临时 IP、弱 HTTP、UPnP 和公网端口转发不能替代正式验收。

数据物理磁盘由客户选择，但逻辑挂载点固定为 `/var/lib/copper`。如需独立数据盘，应先在 Ubuntu 中完成分区、文件系统和 `/etc/fstab` 挂载，再运行预检。禁止把数据库数据放到以下位置：

- Windows 共享文件夹或虚拟机共享目录；
- SMB/CIFS、NFS、SSHFS；
- OneDrive、网盘同步目录；
- USB 或其他可移除盘。

NAS、S3 和可移除介质可以作为备份目标，但不能作为 MySQL 在线数据目录。

独立外接盘和 NAS 目标还必须执行 `copperctl storage enroll` 绑定稳定介质身份。缺盘、换盘或错误共享时自动任务必须失败，不能把备份写入失去挂载后的本机空目录。完整流程见 [M5-05 外接盘、NAS 备份恢复与换机迁移](M5_STORAGE_BACKUP_MIGRATION.md)。

## 4. 启动、重启与断电恢复

正式 `install` 在应用三层健康通过后安装 `/etc/systemd/system/copper.service`。该服务依赖 `docker.service` 和 `network-online.target`：

```bash
sudo copperctl host autostart status
sudo systemctl status copper.service
```

Ubuntu 正常关机时，systemd 先执行 `copperctl down`，容器和网络被移除，但数据库、附件、配置、备份和密钥保持不变。Ubuntu 再次启动后，systemd 等待 Docker 和网络，再执行 `copperctl up`；Compose 健康门槛未通过时 `copper.service` 不得显示成功。

断电恢复验收必须检查：

1. Ubuntu 启动后无需人工按顺序启动 Docker、MySQL、Backend 和 Web；
2. `copper.service` 为 enabled/active；
3. `copperctl status`、`doctor` 和 `monitor check` 可判断业务是否 ready；
4. 断电前写入的业务数据仍可读取；
5. 80/443 可访问，3306/8191 未对宿主机或 LAN 暴露。

Windows 宿主机还要额外实测一次 Windows 重启：Ubuntu 虚拟机应自动启动，然后完全复用上述 Ubuntu 验收。宿主机睡眠会让服务暂停，因此正式营业设备推荐禁用自动睡眠；唤醒后仍以 `monitor check` 判定业务状态，不能仅凭虚拟机窗口已打开认定可用。

## 5. 卸载、重装与永久删除

```bash
sudo copperctl uninstall
```

普通卸载会停用 `copper.service`、移除容器/网络及命令链接，但保留：

- `/etc/copper` 配置和密钥；
- `/var/lib/copper` 数据库、附件和运行状态；
- `/var/backups/copper` 备份；
- `/var/lib/copper-restores` 旁路恢复数据。

在原发布目录重新执行 `sudo ./copperctl install` 时，预检应显示 `managed-reinstall`，并复用原配置、密钥和数据。重装前仍应创建并验证一次加密整站备份。

永久删除只能使用独立危险脚本，必须输入站点标识和明确确认值。不得把删除数据混入 `down`、`restart`、`uninstall`、虚拟机重启或软件升级。

## 6. 实施验收清单

- [ ] Ubuntu 24.04 x86_64 预检报告为 PASS，且报告文件为 root-only。
- [ ] ARM64、网络文件系统、端口冲突、孤立数据等反向场景在写入前阻断。
- [ ] 安装后 Web/Backend/MySQL 三层健康且 3306/8191 未发布。
- [ ] Ubuntu 重启/异常断电恢复后无需人工按顺序启动。
- [ ] 卸载保留数据，重装识别 managed-reinstall 并读取原业务数据。
- [ ] 永久删除仍需独立脚本和双重确认。
- [ ] 若 Windows 为宿主机，虚拟机已配置随宿主机启动并完成一次 Windows 重启验收。
- [ ] LAN 固定地址、受控 DNS、客户 CA HTTPS、允许网段和 3306/8191 外部扫描已按 M5-04 通过。
