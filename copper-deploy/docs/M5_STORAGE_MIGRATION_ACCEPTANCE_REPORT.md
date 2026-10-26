# M5-05 外接盘、NAS 备份恢复与换机迁移工程验收报告

> 验收日期：2026-07-15
>
> 验收层级：Ubuntu 24.04 x86_64 工程环境
>
> 历史结论：实现与工程故障注入通过；实体外接盘、远程 NAS 和独立设备 B 尚未验收。
>
> M7-06决定：上述实体存储和换机能力已移出 `1.0.0` 首发标准兼容清单，Issue #63按 `not planned for 1.0` 收口；不是实体验收通过。详见 [M7-06范围决定](M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md)。

## 1. 本阶段验收范围

本阶段验证存储目标身份、故障域提示、失败关闭、备份重新读取和旁路恢复合同。验收机为隔离的 Ubuntu 24.04 x86_64 云主机；外接介质使用独立 loop-backed ext4 挂载，NAS 使用 Linux 内核 NFS4 通过 localhost 独立挂载。

这两种介质能真实触发 Linux 挂载、文件系统、NFS、只读、空间和断开行为，适合自动化工程验收，但不等同于客户现场的实体外接盘、远程 NAS 或独立设备 B。因此本报告不能关闭路线图的实体环境门槛。

## 2. 自动化合同

以下合同在实现分支完整通过：

- Python 测试：135 项通过；
- CLI 合同：通过；
- 静态合同：通过；
- 存储目标专项测试：8 项通过；
- 备份任务专项测试：4 项通过。

专项覆盖四类目标 `local`、`external`、`nas`、`s3`，并覆盖介质登记、身份标记丢失/替换、发布中断不生成成功标记、同盘风险提示和备份目标集合校验。

## 3. x86_64 工程环境证据

验收脚本：`tests/run-m5-storage-migration-x86-acceptance.sh`

本轮结果：

```text
restored_table_count=90
archive_bytes=51200
m2_restore_runtime=passed
m505-publish-interrupted=passed
m505-external-missing=passed
m505-external-swapped=passed
m505-nas-read-only=passed
m505-external-full=passed
storage_failure_domains=passed
engineering_external_media=loop-ext4
engineering_nas=nfs4-localhost
physical_external_and_remote_nas_required_for_issue_close=true
m5_05_engineering_acceptance=passed
```

验收同时证明：

1. 空白安装、首次管理员一次性凭据改密和旧凭据销毁成功；
2. 整站备份包含 90 张数据库表、附件原件、附件引用、财务指标和审计数据；
3. 错误恢复私钥、损坏归档、空间不足、不兼容镜像、附件缺失和切换中断均失败关闭；
4. 从 NAS 目标重新读取的备份完成 SHA-256 与字节数核验，并成功执行旁路恢复、切换和回滚；
5. 缺盘、换盘、只读、满盘和发布中断均未改写最后成功记录，也未生成成功假象；
6. `local` 正确显示 `SAME_AS_PRIMARY_DATA / WARNING`，工程外接盘与 NFS 显示独立挂载故障域；
7. 脚本退出后无 Copper 容器、测试挂载、loop 设备、NFS 导出或测试端口残留，正式端口 8191 始终未被占用。

归档摘要仅用于证明本次字节级核验，不能作为长期下载地址或恢复材料。验收日志和脚本不得记录数据库密码、管理员凭据、age 私钥或 NAS 密码。

## 4. 尚未关闭的实体环境门槛

以下项目必须在客户同类实体环境完成后，才能关闭 M5-05：

- 使用真实独立外接盘或独立块设备完成登记、备份、断开、重新挂载、读取和校验；
- 使用与 Copper 主机分离的真实 NFS/NFS4/CIFS/SMB3 NAS 完成登记、权限变化、网络断开、读取和校验；
- 从设备 A 的独立备份介质恢复到独立空白 x86_64 设备 B，并核对核心业务、附件、审计和 Flyway；
- 由客户控制的两份离线恢复私钥与唯一备份介质物理分离，并形成真实离线解密和交接回执；
- 在客户确认前保留来源设备和回退窗口，确认后才执行危险清理。

Issue 回填必须明确区分“工程介质通过”和“实体介质通过”。不得把 loop 设备、localhost NFS 或同机旁路容器写成客户现场最终验收。

## 5. 实体验收签字记录

| 项目 | 介质/设备标识 | 日期 | 执行人 | 见证人 | 证据位置 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 独立外接盘 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待验收 |
| 远程 NAS | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待验收 |
| 设备 A 到设备 B | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待验收 |
| 离线恢复材料交接 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待验收 |
