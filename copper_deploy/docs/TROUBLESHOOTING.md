# Copper M1 故障排查手册

先执行：

```bash
sudo copperctl doctor
```

每个“失败”或“提醒”项都会附带“下一步”。处理后重复执行，直到没有失败项。不要为恢复服务而手工改数据库、删除 `/var/lib/copper`、开放 8191/3306，或使用 `docker compose down -v`。

同时查看最近一次完全本地的监控状态：

```bash
sudo copperctl monitor status
sudo copperctl monitor history --limit 20
sudo copperctl operations status
```

`monitor status` 不依赖 Docker 或公网，可以在服务故障时读取最后保存的存活、就绪、可写、可恢复、容量、备份、证书和时间证据。修复后运行 `sudo copperctl monitor check` 生成新证据；不要手工编辑 `/var/lib/copper/monitor`。

数据库、磁盘、备份、证书、系统时间或异常断电时，再运行 `sudo copperctl operations checklist <database|disk|backup|certificate|time|power>`，按“安全停止 → 诊断 → 恢复门槛 → 升级路径”执行。不要为了临时恢复写入而删除审计、唯一备份或唯一回滚目录。

## 端口与访问失败

- 标准模式只允许 Web 发布 80/443；Backend 和 MySQL 没有宿主机端口。
- 80/443 被其他程序占用：运行 `sudo ss -lntup` 查明进程，确认归属后处理；不要直接杀死未知客户进程。
- 外部代理模式只能看到 `127.0.0.1:COPPER_PROXY_PORT`，公网入口由客户代理负责。
- LAN 模式先运行 `sudo copperctl lan status --json`；固定地址、内网 DNS、客户 CA 证书或 `DOCKER-USER` 任一失败都不得降级成 HTTP。
- `/healthz` 正常但 `/readyz` 失败：Web 存活，Backend 或数据库尚未就绪，查看 `copperctl logs backend`。

## 数据库或迁移失败

```bash
sudo copperctl logs mysql
sudo copperctl logs backend
```

- 密码错误：确认 `/etc/copper/secrets` 未被覆盖，不要新生成密码替换旧值。
- Flyway 失败：保持 Web/Backend 不就绪，先备份现状；不得修改已经发布和执行过的迁移文件，也不得手工跳过失败版本。
- MySQL 数据目录权限异常：重新运行发布目录中的 `sudo ./copperctl install`；不要递归改成 `777`。

## 文件权限失败

正式配置应为 `root:root 600`，数据库密钥目录为 root 受限目录，应用附件目录由 UID/GID 10001 使用。运行：

```bash
sudo ./copperctl install
sudo copperctl doctor
```

不要使用 `chmod -R 777`。凭据文件权限不是 600 时，`admin init` 会拒绝显示密码。

## 磁盘空间不足

`doctor` 在数据盘剩余空间低于 5 GiB 时阻断交付。优先扩容；清理前先确认文件归属。不要直接删除 MySQL、附件、Caddy 或备份目录。

查看容量：

```bash
sudo du -sh /var/lib/copper /var/backups/copper
df -h /var/lib/copper /var/backups/copper
```

`monitor check` 同时检查绝对剩余量与百分比，并分别展示数据库、附件、导出、备份和日志字节数。严重时先暂停新的导出、备份和大附件；阻断时保持业务写保护。只清理明确可再生且有保留策略的文件，禁止删除唯一成功备份、附件、MySQL 文件或审计证据。

## 域名或证书失败

- 核对 DNS A/AAAA 是否指向本机公网地址。
- 核对云安全组和上游防火墙是否放行 TCP 80/443、UDP 443。
- 核对服务器时间是否正确。
- 查看 `sudo copperctl logs web`。
- 外部代理模式必须由客户代理提供受信任 HTTPS，不能把回环 HTTP 地址交给最终用户。
- 中国大陆机房若对未备案域名返回非 Copper 的 `403`，不要绕过机房策略；使用合规域名，或在固定公网 IPv4 场景切换到 `COPPER_TLS_MODE=ip`。
- 公网 IP 模式运行 `sudo copperctl tls status`。证书不足 12 小时、SAN 不匹配或续期计时器失效都会由 `doctor` 阻断交付。
- 公网 IP 证书约 6 天有效，必须保留每日自动续期；不要停用 `copper-ip-tls-renew.timer`，也不要依赖人工日历提醒。
- LAN 证书失败时核对 `/etc/copper/tls/lan-ca.pem` 的客户指纹、叶证书 DNS SAN 和私钥匹配。单张自签名叶证书、点击浏览器“继续访问”和临时 hosts 记录均不能关闭故障。
- LAN 地址变化时恢复 DHCP 保留/静态地址；确需迁移则先更新受控 DNS 和证书，再复验 Cookie、上传、打印、备份下载及 3306/8191 外部不可达。

## 容器无法启动或不健康

```bash
sudo copperctl status
sudo copperctl logs mysql
sudo copperctl logs backend
sudo copperctl logs web
```

按 MySQL → Backend → Web 的顺序处理。Backend readiness 未通过时 Web 不会进入整站就绪，这是数据安全保护，不应删除健康依赖绕过。

## 首次管理员问题

- 文件存在：运行 `sudo copperctl admin init` 检查，客户本人用 `--show` 查看并立即改密。
- 文件不存在且已改密：正常，说明一次性文件已销毁。
- 文件不存在且从未登录：查看 Backend 日志和 `doctor`；不要手工插入管理员、复制数据库密码或重新生成一次性文件。

## M2 一致性整站备份

创建备份：

```bash
sudo copperctl backup create
```

该命令要求三层服务先健康，会短暂停止 Web 和 Backend，等待事务归零后捕获 MySQL 逻辑快照、附件原件、非秘密配置、精确记录数和关键余额。原始快照捕获完成后先恢复服务，再压缩并使用客户 age 公钥加密。最终文件为 `.copperbak` 和同名 `.sha256`，详细结构见 `docs/M2_BACKUP.md`。

常见失败：

- 提示尚未登记客户公钥：先完成离线生成和双人交接，运行 `sudo copperctl backup key register`；禁止复制客户私钥到服务器绕过。
- 提示缺少 `age`、`zstd` 或 `python3`：使用可信系统软件源或正式离线介质安装，不执行未知脚本。
- 提示 age 公钥无效：只填写客户私钥对应的 `age1...` 公钥，不要把 `AGE-SECRET-KEY-...` 写到服务器。
- 提示空间不足：扩容或清理非 Copper 文件；失败任务不会更新最后成功时间，也不会删除旧成功备份。
- 提示目标身份缺失/不一致：先确认外接盘或 NAS 的真实挂载和设备身份；禁止复制旧 `.copper-storage-identity.json` 到替换介质。按更换介质流程确认旧副本后再重新登记。
- `storage status` 显示 `SAME_AS_PRIMARY_DATA / WARNING`：这是本机同盘副本的正确风险提示；增加已登记的外接盘、NAS 或 S3，不能通过编辑状态 JSON 消除。
- 提示旧 BLOB 附件：先完成附件文件迁移和哈希校验；不得生成缺少原件的“成功备份”。
- 备份中断：运行 `sudo copperctl doctor`，确认 MySQL、Backend、Web 三层恢复健康；不要手工删除数据库文件。
- 校验失败：该文件不得用于恢复或触发旧备份清理，从客户控制的其他副本重新复制。
- 连续失败未归零：运行 `sudo copperctl backup status` 查看固定错误类别，修复后手工成功创建一次备份；不要直接编辑状态 JSON。
- Webhook 失败：确认 URL/HMAC 文件为 `root:root 0600`、HMAC 至少 32 字节且生产 URL 使用 HTTPS；通知失败不会删除本地备份状态。
- 服务器私钥扫描失败：立即隔离发现的文件并按客户交接流程确认来源、销毁服务器副本；不得用忽略扫描或放宽权限的方式启用计划任务。

同版本恢复：

```bash
sudo copperctl backup restore /var/backups/copper/备份文件.tar.gz
```

该命令当前只接受旧 M1 `.tar.gz` 并执行同版本回退。新的 `.copperbak` 会被明确拒绝；M2 旁路恢复、失败不覆盖和恢复后余额核对由 M2-03 实现。

M2 当前不承诺跨版本恢复或在线增量备份。软件升级编排、自动升级回滚属于 M3，当前不得对客户宣传已经完成。

## M4 本地监控与通知

- “存活正常、就绪阻断”：容器还在运行，但 Backend readiness 或 MySQL 连接失败；先检查 Backend/MySQL 日志和 Flyway，不要绕过健康依赖。
- “可写阻断”：数据库只读、无持久副作用写入探测失败、业务文件区不可写、磁盘进入阻断区或时间偏差超过 60 秒。修复原因并复测前不得宣称业务可安全写入。
- “可恢复阻断”：没有可验证成功备份，或客户活动公钥、双人交接、离线恢复回执不完整。按 M2 密钥交接和实际恢复验证流程补齐，禁止伪造回执。
- “证书预警/严重”：检查本机公开证书链、续期计时器、DNS、80/443 和 NTP。外部代理模式可配置 `COPPER_MONITOR_TLS_CERT_FILE`；监控不会读取私钥或依赖公网探测。
- “系统时间严重/阻断”：运行 `timedatectl status` 和 `timedatectl timesync-status`。时间安全状态会锁存已发现的大幅跳变；恢复 NTP 后连续运行 `sudo copperctl monitor check`，确认时间快照重新正常，再检查证书、登录会话和审计顺序。阻断解除前，财务、权限、导出及订单/入出库状态流转等高风险写入会明确失败；不得编辑状态文件绕过，也不得把失败请求当作已提交，恢复后必须由用户显式重试。
- “远程告警通知失败”：健康事实仍在 root-only `status.json` 和 `history.jsonl`。检查客户自有 Webhook 地址、网络、HMAC 文件权限；不要把状态文件或密钥发送到第三方。

监控完整合同、阈值和输出字段见 [M4_MONITORING.md](M4_MONITORING.md)。

## 仍无法解决

保留现场，不要删除容器、数据目录或日志。收集以下非敏感信息交给支持人员：

- `copperctl version`。
- `copperctl doctor` 输出。
- 失败服务最近 200 行日志；发送前确认不含客户隐私或凭据。
- 问题发生时间、用户操作、域名和发布镜像摘要。
