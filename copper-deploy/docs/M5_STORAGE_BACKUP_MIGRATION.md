# M5-05 外接盘、NAS 备份恢复与换机迁移

> 适用目标：Ubuntu Server 24.04 LTS x86_64 本地常开主机，以及 Windows 宿主内的同规格 Ubuntu 虚拟机
> 备份格式：继续使用 `copper-backup/v1`，M5 不修改数据库、附件或审计数据格式
> 安全原则：主数据只使用可靠本地块存储；外接盘和 NFS/SMB 只作备份目标

## 1. 故障域边界

`local` 目标始终保留一份本机恢复副本，但 `/var/backups/copper` 与 `/var/lib/copper` 通常位于同一系统盘。`copperctl storage status` 会把这种情况明确标为 `SAME_AS_PRIMARY_DATA / WARNING`；它只能处理误操作或局部数据损坏，不能防止整盘、整机、火灾或失窃。

生产交付至少再配置以下一种独立目标：

- `external`：操作系统已挂载的独立 ext4、xfs 或 btrfs 外接/独立块设备；
- `nas`：操作系统已挂载的 NFS、NFS4、CIFS 或 SMB3 目录；
- `s3`：客户控制的 S3 兼容对象存储。

MySQL、附件和运行配置不得放在 NFS、SMB、虚拟机共享目录、Windows 映射盘、OneDrive 或普通同步盘。Windows 场景的在线数据必须留在 Ubuntu 虚拟机的可靠本地虚拟磁盘内。

## 2. 操作系统先完成稳定挂载

Copper 不保存 NAS 管理员密码，也不自动执行 `mount`。系统管理员应先使用外接盘文件系统 UUID，或稳定的 NAS 服务器/共享名称配置系统挂载，并验证重启后仍落到同一目标。

示例挂载目录：

```text
/mnt/copper-external-backups
/mnt/copper-nas-backups
```

不要只创建一个空目录冒充已挂载介质。缺盘、未挂载、错误文件系统、与主数据同一设备、只读、空间不足或原子改名失败都会被拒绝。

## 3. 配置并登记稳定介质身份

先在 `/etc/copper/copper.env` 配置目标路径和目标集合：

```text
COPPER_BACKUP_TARGETS=local,external,nas
COPPER_BACKUP_EXTERNAL_DIR=/mnt/copper-external-backups
COPPER_BACKUP_NAS_DIR=/mnt/copper-nas-backups
```

首次使用每个介质时执行一次登记：

```bash
sudo copperctl storage enroll external --confirm /mnt/copper-external-backups
sudo copperctl storage enroll nas --confirm /mnt/copper-nas-backups
sudo copperctl storage status
```

登记会在目标中原子写入 `.copper-storage-identity.json`，并把对应随机身份写入 `COPPER_BACKUP_EXTERNAL_ID` 或 `COPPER_BACKUP_NAS_ID`。身份只包含目标类型、随机 ID 和站点指纹，不包含数据库密码、NAS 密码、客户名称或恢复私钥。

以后每次预检、发布、列举、读取、校验和删除都会同时检查：

1. 挂载类型和独立故障域；
2. 目标身份与当前站点配置一致；
3. 写入、`fsync`、原子改名和剩余容量；
4. 备份包、sidecar 和成功标记一致。

缺盘或换盘后不得复制旧身份文件到新介质来绕过检查。新介质必须先按恢复/更换流程确认旧副本状态，再由实施人员清除旧绑定并重新登记；这一动作不得在自动备份任务中发生。

## 4. 成功备份与不可读演练

```bash
sudo copperctl backup create
sudo copperctl storage list external
sudo copperctl storage list nas
sudo copperctl storage status
```

只有所有已配置目标都完成写入、重新读取 SHA-256 并发布成功标记后，任务才更新 `last-success.json`。任一目标中途断开、满盘、变为只读、权限改变或身份不一致时，整次任务失败；失败目标不会出现可被 `storage list` 识别的成功备份，也不会触发旧备份保留删除。

每次交付和定期恢复演练至少从独立目标取回一次：

```bash
sudo install -d -o root -g root -m 0700 /var/lib/copper-recovery-media
sudo copperctl storage fetch nas 备份文件.copperbak /var/lib/copper-recovery-media
sudo copperctl storage verify nas 备份文件.copperbak SHA256 字节数
sudo copperctl backup verify /var/lib/copper-recovery-media/备份文件.copperbak
```

客户的 age 恢复私钥必须保存在独立离线介质，不能位于应用主机或唯一备份盘。至少保留两份由客户控制的恢复材料，并用真实备份完成一次离线解密回执。

## 5. 设备 A 到设备 B 的标准迁移

1. 设备 B 先通过同一正式版本、目标架构、磁盘和端口预检；使用与来源一致的 `COPPER_SITE_ID` 和三张不可变镜像。
2. 设备 A 创建最新一致性整站备份，在独立目标重新校验，并保持来源站运行。
3. 把备份主包和 `.sha256` 取到设备 B 的受限恢复目录；恢复私钥只在恢复命令执行期间连接。
4. 设备 B 完成空白安装、首次管理员凭据交付和改密，然后执行旁路恢复：

```bash
sudo copperctl backup restore stage /secure/备份文件.copperbak \
  --identity /secure/customer-age-identity.txt \
  --restore-id migrate-device-b
sudo copperctl backup restore status migrate-device-b
```

5. `VALIDATED` 必须证明版本、镜像架构、Flyway、全部表记录数、库存/成本、应收应付、资金/核销、附件和审计合同一致。人工界面核对过的旁路必须丢弃，再以同一备份创建未登录的新旁路。
6. 在可信终端执行 `backup restore switch migrate-device-b`，再切换稳定 LAN 地址和证书。
7. 验证桌面/手机访问、打印、扫码和上传；来源设备及回退数据保留到客户签字确认后再危险清理。

设备迁移不允许通过导入分页 Excel、复制 MySQL 在线目录或手工改余额完成。跨版本恢复必须先走 M3 升级矩阵；跨 CPU 架构迁移只有两端平台和正式镜像均进入支持矩阵后才可承诺。

## 6. M5-05 验收清单

- [ ] 本机 `local` 目标明确显示同盘风险，未被宣传为异机容灾。
- [ ] 独立 ext4/xfs/btrfs 外接/块设备已登记身份并完成备份、读取和校验。
- [ ] 至少一种真实 NFS/NFS4/CIFS/SMB3 目标已登记身份并完成备份、读取和校验。
- [ ] 缺盘、换盘、只读、权限变化、空间不足和发布中断均失败关闭，且没有成功假象。
- [ ] 从独立目标在空白设备 B 完成旁路恢复、切换及核心业务不变量核对。
- [ ] 附件原始字节、数据库引用、审计历史和 Flyway 记录完整。
- [ ] 恢复私钥和唯一备份副本不在同一介质，真实离线解密回执已登记。
- [ ] 来源设备和回退窗口在客户确认前未被清理。

只有上述真实环境证据完成并回填 Issue/验收报告后，M5-05 才能关闭；代码或单元测试通过不能替代外接盘、NAS 和换机恢复。
