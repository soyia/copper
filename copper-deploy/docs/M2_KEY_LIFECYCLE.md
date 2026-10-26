# M2-06 客户持钥、备份任务状态与告警

> 安全边界：Copper 服务器持久状态只保存 `age1...` 公钥、交接记录哈希和离线验证回执哈希；客户恢复私钥长期由客户离线持有。真实恢复时只允许从客户安全介质读取，或按 `M2_RESTORE.md` 临时进入 `/run` tmpfs，恢复结束立即销毁。

## 1. 不可降低的原则

- Copper 不提供未加密备份模式，也不提供“临时关闭加密”开关。
- `AGE-SECRET-KEY-...` 私钥不得进入服务器持久磁盘、Git、容器镜像、聊天、工单、邮件正文或普通日志；真实恢复只允许按 `M2_RESTORE.md` 从客户安全介质读取或临时进入 `/run` tmpfs。
- 客户至少保留两份离线私钥副本，放在两个独立介质或地点；两份全部丢失后，没有厂商后门可以恢复历史备份。
- 新密钥轮换只影响轮换后的新备份，不重写历史备份。旧私钥必须保留到所有使用它加密的备份按既定保留期安全退出后，才能由客户自行销毁。
- 备份计划只有在活动公钥完成双人交接登记、实际备份离线解密验证和服务器私钥扫描后才能启用。

## 2. 客户离线生成首把密钥

在客户控制、断网或受控联网的可信设备上准备官方 `age`、`zstd` 和 Python 3，然后运行随交付包提供的工具：

```bash
./copper-key-offline generate \
  --output-dir /客户离线介质/copper-recovery-key-01 \
  --site-id copper-prod \
  --customer-reference 客户内部代号
```

工具拒绝覆盖已有目录，并生成：

- `copper-age-identity.txt`：客户私钥，权限 `0600`，禁止交给服务器。
- `recipient.txt`：可交给部署人员的公钥。
- `handover-record.json`：交接信息。
- `handover-checklist.md`：双人核对和签署清单。
- `README.txt`：离线保存边界。

客户完成两份离线复制并由客户确认人、独立见证人签署后，只把公钥和签署记录的 SHA-256 交给服务器操作员。服务器配置公钥后登记：

```bash
sudo copperctl backup key register \
  --handover-sha256 64位小写SHA256 \
  --customer-confirmed-by '客户确认人' \
  --witness-confirmed-by '独立见证人'
```

两名确认人必须不同；已有登记簿不能被 `register` 覆盖。

## 3. 首次实际备份验证与启用计划

服务器创建加密备份：

```bash
sudo copperctl backup create
sudo copperctl backup status
```

把 `.copperbak` 和同名 `.sha256` **以原文件名一起**复制到客户离线设备；两者必须位于同一目录，且不得只复制主包或把主包另行改名。先在服务器执行 `sudo copperctl backup status` 取得完整文件名，再分别复制：

```bash
scp server:/var/backups/copper/copper-prod_时间_备份ID_manual.copperbak \
  /客户离线介质/copper-recovery-key-01/
scp server:/var/backups/copper/copper-prod_时间_备份ID_manual.copperbak.sha256 \
  /客户离线介质/copper-recovery-key-01/
```

然后用客户私钥验证实际备份并生成不含私钥的回执：

```bash
./copper-key-offline verify-backup \
  --identity /客户离线介质/copper-recovery-key-01/copper-age-identity.txt \
  --archive /客户离线介质/copper-recovery-key-01/copper-prod_时间_备份ID_manual.copperbak \
  --receipt /客户离线介质/backup-verification-receipt.json
```

只把回执交回服务器登记。回执包含一次性证明值但不包含私钥；服务器会把它与本地实际 `.copperbak` 的 SHA-256、公钥指纹和 envelope 证明哈希逐项核对，手工伪造 `PASSED` JSON 不会被接受：

```bash
sudo copperctl backup key record-restore-test backup-verification-receipt.json
sudo copperctl backup key audit
sudo copperctl backup key status
```

确认登记簿、实际离线验证和服务器无私钥扫描均通过后，设置计划并启用：

```text
COPPER_BACKUP_DAILY_TIME=02:30
COPPER_BACKUP_RANDOM_DELAY_MINUTES=30
```

```bash
sudo copperctl backup schedule install
sudo copperctl backup schedule status
```

每日时间使用 `HH:MM`，随机延迟允许 `0-1440` 分钟。`Persistent=true` 会在主机错过执行时间后补跑。

## 4. 安全轮换

1. 客户在离线设备用 `generate` 生成第二把密钥并完成两份副本与双人交接。
2. 服务器只接收新公钥并生成加密挑战：

```bash
sudo copperctl backup key prepare-rotation \
  --new-recipient age1... \
  --handover-sha256 64位小写SHA256 \
  --customer-confirmed-by '客户确认人' \
  --witness-confirmed-by '独立见证人'
```

3. 把命令返回的挑战文件交给客户离线设备；客户用新私钥解密并生成一次性证明：

```bash
./copper-key-offline verify-challenge \
  --identity /客户离线介质/新私钥/copper-age-identity.txt \
  --challenge rotation-新KEYID.age \
  --proof-output rotation-proof.txt
```

4. 服务器使用一次性证明和精确 Key ID 激活：

```bash
sudo copperctl backup key activate-rotation \
  --key-id 新KEYID \
  --proof-file rotation-proof.txt \
  --confirm 新KEYID
```

5. 操作员立即销毁服务器上的一次性证明传输文件；创建新备份，用新私钥离线验证并登记新回执。
6. 登记簿中的旧密钥保持 `RETIRED` 历史状态。旧私钥继续由客户离线保管，用于解密旧备份；不得因为轮换成功就立即销毁。

## 5. 任务状态和告警

每次正式 `copperctl backup create` 都记录开始、结束、最近成功、最近失败、连续失败次数、错误类别和告警结果：

```bash
sudo copperctl backup status
sudo copperctl backup status --json
```

错误类别只使用固定代码，例如 `SPACE_INSUFFICIENT`、`TARGET_TIMEOUT`、`PERMISSION_DENIED`、`KEY_CONFIGURATION_ERROR`、`VERIFICATION_FAILED`、`INTERRUPTED`，不把原始错误文本发送到外部。

默认 `local` 模式仅写入 root-only 状态和 systemd 结果。需要异机通知时，在 `/etc/copper/notification-credentials` 建立 `root:root 0600` 的 URL 和至少 32 字节 HMAC 密钥文件，然后设置：

```text
COPPER_BACKUP_ALERT_MODE=webhook
COPPER_BACKUP_ALERT_TIMEOUT_SECONDS=10
COPPER_BACKUP_ALERT_WEBHOOK_URL_FILE=/etc/copper/notification-credentials/backup_webhook_url
COPPER_BACKUP_ALERT_HMAC_KEY_FILE=/etc/copper/notification-credentials/backup_webhook_hmac_key
```

生产 Webhook 必须是 HTTPS。通知正文只包含固定版本、事件、站点匿名指纹、任务号、通用目标、时间、结果、错误代码、连续失败和下一步代码；不包含客户名称、业务数据、备份文件名、路径、公钥或私钥。接收方用 `X-Copper-Signature: sha256=...` 验证 HMAC。

告警发送失败不会把已经成功的本地备份改判失败，也不会删除本地任务状态；`alertResult=FAILED` 会留下独立痕迹。外部通知是辅助通道，不能替代 `copperctl backup status`、systemd 监控和定期恢复演练。

## 6. 日常检查

```bash
sudo copperctl backup status
sudo copperctl backup key status
sudo copperctl backup schedule status
sudo copperctl doctor
```

发现服务器私钥扫描失败、配置公钥与活动 Key ID 不一致、连续失败不归零或最近成功超过企业设定时限时，应暂停版本升级，先恢复备份链路。禁止直接编辑 `registry.json` 或 `status.json`。
