# M4-04 本地健康、容量、备份、证书与时间监控

## 目标与边界

`copperctl monitor` 让客户在没有 Copper 云服务、没有公共互联网、没有额外监控容器时，独立判断系统是否存活、就绪、可写和可恢复。监控只采集基础设施状态、字节数、时间、版本和固定动作码，不查询业务表，不计算客户、库存、订单、应收应付或资金汇总，也不保存口令、令牌、私钥、恢复公钥原文和备份文件名。

监控不能替代 M4-06 的真实故障与恢复演练。状态为“正常”表示当前强制信号通过，不表示从未发生故障；完整历史保留在客户服务器 root-only 状态目录中。

## 四层状态

| 层级 | 判断内容 | 关键证据 |
| --- | --- | --- |
| 存活 | MySQL、Backend、Web 是否存在、运行且容器健康 | Docker 容器状态和健康检查 |
| 就绪 | Web、Backend、MySQL 和 Flyway 是否可以服务 | 同容器回环探测、Backend readiness、业务数据库连接 |
| 可写 | 数据库和业务文件区是否允许安全写入，磁盘与系统时间是否可信 | 业务数据库账户临时表探测、Backend 身份临时文件探测、容量、NTP |
| 可恢复 | 最近备份是否新鲜且可验证，客户是否具备恢复密钥实证 | 备份任务状态、SHA-256 实证、目标、双人交接、离线恢复回执 |

状态固定使用四级：

- `NORMAL / 正常`：不需要处理。
- `WARNING / 预警`：业务可以继续，应安排处理。
- `CRITICAL / 严重`：故障或风险已经显著，应立即处理；停止非必要大文件任务。
- `BLOCKED / 阻断`：不得把当前状态宣称为安全可用；涉及数据库、磁盘或时间的阻断应保持业务写保护，修复后重新检查。

容器运行不能冒充应用就绪，应用就绪不能冒充业务可写，备份文件存在不能冒充可恢复。

## 使用方法

```bash
sudo copperctl monitor check
sudo copperctl monitor check --json
sudo copperctl monitor status
sudo copperctl monitor status --json
sudo copperctl monitor history --limit 20
sudo copperctl monitor history --json --limit 100
sudo copperctl monitor schedule install
sudo copperctl monitor schedule status
```

`check` 执行一次真实采集并原子更新状态；`status` 只读取最近一次本地结果，即使 Docker 或公网当前不可用也可查看；`history` 查看有界本地历史。`check` 在正常/预警时退出 0，在严重/阻断时退出 1，配置或状态文件不安全时退出 3。

正式安装默认启用 `copper-monitor.timer`：开机后 2 分钟首次运行，此后每 5 分钟运行。计时器和状态均在客户服务器上，不需要回连 Copper。

## 固定门槛

### 容量

容量同时检查绝对剩余量和剩余百分比，任一条件触发即升级：

| 等级 | 条件 | 默认动作 |
| --- | --- | --- |
| 预警 | 低于 20% 或 5 GiB | 检查增长来源，安排清理或扩容 |
| 严重 | 低于 10% 或 2 GiB | 立即扩容；暂停新的导出、备份和大附件 |
| 阻断 | 低于 5% 或 1 GiB | 保持业务写保护，扩容或安全清理后复测 |

系统分别记录数据库、附件、导出、备份和应用日志占用，并在有至少 6 小时、最多 30 天的有效历史后计算增长速度。预计 30 天内进入严重区为预警，7 天内进入严重区为严重。监控不会自动删除唯一备份、附件、业务数据或审计证据。

### 备份和恢复密钥

- 最近成功且完整校验的备份超过 30 小时为预警，超过 48 小时为严重；没有可验证成功备份为阻断。
- 连续失败 1 次至少为预警，连续失败 2 次及以上至少为严重。
- 活动 age 公钥、双人交接摘要和真实离线恢复回执缺少任一项时，“可恢复”为阻断。
- 输出只显示目标名称、时间、次数和校验状态，不显示恢复公钥、备份文件名或业务内容。

### HTTPS 证书

- 域名证书剩余 30 天为预警门槛，7 天为严重门槛，过期为阻断。
- 公网 IP 短期证书使用 2 天预警、12 小时严重、过期阻断。
- `standard` 模式从 Caddy 客户数据目录读取公开证书；`ip` 模式读取公开 `fullchain.pem`，从不读取私钥。
- `external-proxy` 模式由客户代理负责 TLS。若希望 Copper 同时检查到期时间，把公开证书链放在 root 可读路径，并在 `/etc/copper/copper.env` 设置 `COPPER_MONITOR_TLS_CERT_FILE`。未配置时显示预警，不会联网探测或把外部代理失联误判为核心业务故障。

### 系统时间

- 操作系统报告 NTP 未同步为严重。
- 偏差达到 1 秒为预警，5 秒为严重，60 秒为阻断。
- 偏差阻断恢复前，不应继续依赖可信时间的高风险写入、登录会话、证书和审计操作。

## 本地状态和隐私

状态目录固定为 `${COPPER_VAR_DIR}/monitor`，正式环境即 `/var/lib/copper/monitor`：

| 文件 | 权限 | 用途 |
| --- | --- | --- |
| `status.json` | `0600` | 最近一次完整、客户可读状态 |
| `history.jsonl` | `0600` | 有界状态历史和容量基线 |
| `history.jsonl.1` | `0600` | 超过 5 MiB 后保留的上一段历史 |

目录权限固定为 `0700`。权限过宽、符号链接或格式错误会被拒绝，不会静默忽略。JSON 中 `privacy.businessDetailsCollected=false`、`privacy.secretsCollected=false` 是输出合同的一部分。

## 可选 Webhook 告警

默认 `COPPER_MONITOR_ALERT_MODE=local`，严重事实始终先写本地。需要客户自有通知系统时可使用 `webhook`：

```ini
COPPER_MONITOR_ALERT_MODE=webhook
COPPER_MONITOR_ALERT_REPEAT_HOURS=24
COPPER_MONITOR_ALERT_TIMEOUT_SECONDS=10
COPPER_MONITOR_ALERT_WEBHOOK_URL_FILE=/etc/copper/notification-credentials/monitor_webhook_url
COPPER_MONITOR_ALERT_HMAC_KEY_FILE=/etc/copper/notification-credentials/monitor_webhook_hmac_key
```

两个凭据文件必须是 root-only `0600/0400` 普通文件；生产 URL 必须为 HTTPS。通知正文只允许：格式版本、站点指纹、检查/变化时间、总体和四层等级、固定动作码。请求使用 `X-Copper-Signature: sha256=...` HMAC-SHA256 签名。

状态变化时立即通知；同一非正常等级最多按配置周期重发；恢复正常也通知。通知失败不会覆盖健康事实，`status.json` 和历史记录 `result=FAILED`、`errorCode=DELIVERY_FAILED`，并增加本地“通知失败”预警。

## 排障顺序

1. 先运行 `sudo copperctl monitor status`，记录总体、四层等级、原因、发生时间和动作码。
2. 再运行 `sudo copperctl monitor check` 确认故障仍然存在。
3. 服务/就绪问题运行 `sudo copperctl doctor`，并使用脱敏的 `copperctl logs`。
4. 备份问题运行 `sudo copperctl backup status`、`backup key status` 和 `storage status`。
5. 证书问题检查 DNS、端口、续期计时器和 NTP；禁止临时降级为公网 HTTP。
6. 修复后重新运行 `monitor check`；只有对应层恢复正常，才解除相关业务限制。

禁止为了把监控“变绿”而编辑 `status.json`、删除历史、伪造备份回执或放宽目录权限。
