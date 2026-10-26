# M2-06 密钥生命周期、任务状态与告警验收记录

验收日期：2026-07-14（Asia/Shanghai）

## 验收范围

- 客户离线生成私钥，服务器仅持有公钥和非秘密审计记录。
- 首次双人交接、实际备份离线验证、计划任务启用门槛。
- 新旧密钥挑战式轮换，历史旧密钥记录保留。
- 最近成功/失败、连续失败、空间/超时/中断分类和恢复归零。
- HMAC 签名的隐私安全 Webhook，以及通知失败不覆盖本地结果。
- 私钥、Webhook 凭据和状态文件的权限错误拒绝。

## 验收环境

- 主机：`local-database`，Fedora 43，ARM64，仅使用隔离目录、隔离 Compose 项目和 `COPPER_TEST_MODE=1`。
- Docker Engine：29.5.3；Docker Compose：5.1.4。
- 数据库：MySQL 8.4.10，镜像 ID `sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`。
- Backend：由 M2-05 已验证 JAR 和本机既有 JRE 基础镜像构建的临时 ARM64 测试镜像，ID `sha256:aad7e2c6d20da7e455a00f3516aea9bb911e6340a0837dd8c638003266210adc`。
- Web：本机 ARM64 Caddy 健康代理，ID `sha256:ae4458638da8e1a91aafffb231c5f8778e964bca650c8a8cb23a7e8ac557aa3c`。M2-06 不修改 Web 业务代码，该替代只用于三层健康门槛。
- 端口：隔离代理使用 `127.0.0.1:18105`，回环 Webhook 使用 `127.0.0.1:19106`；未监听、停止或替换 8191。

## 自动化验收入口

仅在 `local-database` 使用隔离目录、隔离 Compose 项目和非 8191 端口运行：

```bash
sudo COPPER_STORAGE_TEST_BACKEND_IMAGE=sha256:实际ARM64测试镜像ID \
  ./tests/run-m2-key-lifecycle-local-database.sh
```

脚本保持共享 `vm-mysql`、`vm-valkey` 容器身份与运行状态不变，并在退出时清理隔离容器、挂载、测试私钥、Webhook 日志和临时 systemd unit。

## 实际通过证据

1. 首次实际 `.copperbak` 使用客户测试私钥离线解密，内外层校验、Key ID、backupId、备份 SHA-256 和加密载荷内一次性证明全部匹配；服务器只登记回执哈希和证明哈希。
2. 手工伪造 `PASSED` 回执的单元测试被拒绝；只有与本地真实备份、公钥指纹和 envelope 证明摘要一致的回执才能打开自动调度门槛。
3. 每日 `04:17`、随机延迟 11 分钟的隔离 systemd service/timer 通过 `systemd-analyze verify`，没有写入或启用主机正式 systemd 目录。
4. 新公钥先生成 age 加密挑战；旧私钥和权限为 `0644` 的新私钥均不能生成有效证明。正确新私钥通过后，旧 Key ID 进入 `RETIRED`，新 Key ID 进入 `ACTIVE`，配置原子切换。
5. 轮换后新备份可以由新私钥完整验证，旧私钥不能解密；旧密钥登记历史未删除。
6. 在服务器受控目录植入测试私钥时扫描明确失败；删除服务器副本后扫描恢复通过并记录审计事件。
7. 在维护窗口向备份任务发送 `SIGTERM` 后，任务记录 `INTERRUPTED`，没有发布半成品，MySQL、Backend、Web 自动恢复健康；随后成功任务把连续失败归零并产生恢复事件。
8. 连续两次空间不足记录 `SPACE_INSUFFICIENT` 和连续失败 2；成功备份归零并产生 `BACKUP_RECOVERED`。
9. 回环 Webhook 收到两次 `BACKUP_FAILED` 和一次 `BACKUP_RECOVERED`，三条 JSON 均只含固定允许字段，HMAC-SHA256 全部验证通过；正文不含站点原值、客户、业务、文件名、`.copperbak`、公钥或私钥。
10. Webhook 接收器停止后，任务保留本地失败状态且 `alertResult=FAILED`；权限为 `0644` 的 HMAC 文件被拒绝。修复后本地成功备份仍能恢复状态。
11. 同一运行还通过本地/NAS/S3 字节和 SHA-256 一致、NAS 断开/只读/容量不足、S3 凭据权限错误和 S3 超时恢复，用于确认 M2-06 包装没有破坏 M2-05 存储链路。

最终成功备份：

- 文件：`copper-m205-test_20260713T234710Z_f2f41c5548a6b33f1f868a7d6bac8c4a_manual.copperbak`
- 大小：51,200 字节。
- SHA-256：`b6adb04eaa6ac77b2c7aeca6c17d8cbc2fc1a2828846c4568ebf39d2d9ae8c24`。
- 完整运行时间：136 秒；脚本退出码 0。

## 清理和共享资源复核

- 隔离 Compose 容器、网络、tmpfs/bind 挂载、测试密钥、回执、Webhook 日志、临时 systemd unit、临时 Backend/MinIO 镜像和工作目录均已删除。
- `vm-mysql` 保持运行且容器 ID 未变化：`cf8ab3860858acb595ad9852e6a8966b8d64d89d29704091395c216add75dcff`。
- `vm-valkey` 保持运行且容器 ID 未变化：`73bbd1f0275fe0e356c71150156867ded5e070d82e1efca308e02110fa564282`。
- 清理后根分区可用 4.9 GiB、使用率 68%；`/tmp` 可用 965 MiB。
- 8191 没有测试监听者。

## 结论

M2-06 在 `local-database` 的真实 `age`、MySQL 8.4、Compose、systemd unit 和回环 Webhook 验收通过。客户生产交付仍必须使用客户自己的离线设备和双人签署清单；隔离测试私钥不是生产私钥。M2-07 空白 x86_64 云服务器最终验收仍是整个 M2 对外收口门槛。
