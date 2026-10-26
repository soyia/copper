# Copper M1 安装与交接手册

> M7 内部演练的全新 Ubuntu 安装优先使用 [Copper 一站式安装器](M7_GUIDED_INSTALLER.md)：进入完整介质目录后只运行 `sudo ./copper-install`。本页保留底层工具和人工维护细节，不再作为普通操作者的首选安装入口。

本手册面向能够使用 SSH 和 `sudo`、但不需要理解 Java、Vue 或 SQL 的实施人员。M7-06 决定的 `1.0.0` 首发拟支持范围只有客户自有公有云上的 Ubuntu Server 24.04 LTS、amd64/x86_64、Docker Engine 29.x、Compose Plugin 5.x、MySQL 8.4 LTS 和单站点部署。Windows 宿主 Ubuntu VM、本地常开主机、NAS主机、ARM64和macOS均已移出首发标准兼容清单；不支持 Windows 原生、WSL2 或 Docker Desktop 安装。

本手册仍保留部分本地/Windows VM 候选检查项，供未来真实设备复验，不表示当前可按 `¥649起` 标准本地部署服务对外销售。正式发布和收费仍等待 M6、M7-02 与 M7-07 门槛。

## 1. 安装前准备

客户需要提前提供：

- 一台全新或已确认无端口冲突的 Ubuntu Server 24.04 LTS x86_64 主机。
- 至少 2 核 CPU、4 GiB 内存、20 GiB 可用磁盘；实际容量必须按客户附件与数据库增长量调整。
- 公网模式需要一个指向该主机的正式域名；中国大陆主机应使用已完成所需备案/接入的域名。固定公网 IPv4 和短周期 IP 证书模式只适用于云厂商允许公网 IP 访问的环境，不能替代中国大陆的域名备案与接入要求。
- LAN 模式改为客户控制域名的内网 A 记录、固定/DHCP 保留的 RFC1918 地址、允许访问的私网 CIDR 和客户 CA 签发的服务器证书；不需要公网端口转发。
- LAN 客户如需可选互联网入口，可在 LAN 安装与验收完成后，从 Mac 一站式维护菜单选择“互联网远程访问（Cloudflare，可选）”；服务器本机维护菜单提供相同选项。详见 [Cloudflare Tunnel 远程访问手册](M5_CLOUDFLARE_REMOTE_ACCESS.md)。它不取代 LAN，也不开放路由器端口。
- 公网模式需要云安全组中的 TCP 80/443、UDP 443 放行规则；LAN 模式只在指定私网网卡和允许网段内开放这些端口。
- 发布清单中的 Web、Backend 不可变镜像摘要；禁止使用 `latest`。
- 一名客户授权管理员，用于当面接收首次一次性凭据并立即改密。

未来复验 Windows 宿主场景时，必须先创建独立 Ubuntu 24.04 x86_64 虚拟机，并满足上述 CPU、内存和磁盘要求。Copper 数据盘属于 Ubuntu 虚拟机；不得把 `/var/lib/copper` 放在 Windows 共享目录、SMB/NFS 或同步盘。Windows 侧需设置“宿主机启动后自动启动该虚拟机”，Copper 服务本身由虚拟机内的 systemd 启动。该段是候选技术边界，不是首发安装步骤。

主机必须安装并启动 Docker Engine 29.x 和 Compose Plugin 5.x。全新 Ubuntu 24.04 空白机可以先校验发布包，再运行：

```bash
sudo ./scripts/bootstrap-ubuntu.sh
sudo ./scripts/m1-07-preflight.sh
```

引导脚本只在未检测到 Docker 时按 [Docker 官方 Ubuntu 安装方法](https://docs.docker.com/engine/install/ubuntu/)配置仓库并安装 29.x/5.x；发现不兼容的既有 Docker 会拒绝自动覆盖。中国区或其他不能稳定访问 Docker/GHCR 的环境，必须使用第 2 节的离线交付方式。不要安装本地开发用的 Docker Desktop、Colima，也不要把 Backend 8191 或 MySQL 3306 暴露到公网。

## 2. 放置发布包

把经过校验的 `copper_deploy` 发布包放到固定目录，例如：

```bash
sudo install -d -o root -g root -m 0755 /opt/copper/releases/m1
sudo cp -a copper_deploy/. /opt/copper/releases/m1/
cd /opt/copper/releases/m1
```

发布目录中不得放置真实 `.env`、数据库口令、管理员密码或客户附件。

### 2.1 中国区离线交付

在能够访问 Docker 官方仓库和 GHCR 的受控中转机上执行：

```bash
sudo ./scripts/fetch-offline-docker.sh \
  releases/0.1.0-m1-rc.3/docker-ubuntu-amd64.env \
  /secure-transfer/docker-ubuntu-amd64

# 先以临时 read:packages 凭据登录 GHCR，不把令牌写进脚本或文件。
sudo ./scripts/export-offline-images.sh \
  releases/0.1.0-m1-rc.3/components.env \
  /secure-transfer/copper-images-amd64
sudo docker logout ghcr.io
```

中转机自身可以是 ARM64，但导出脚本固定拉取并验证 `linux/amd64`。不要停止或删除中转机上的现有容器。把两个目录通过受控链路传到客户主机，逐个核对 `SHA256SUMS` 后执行：

```bash
sudo ./scripts/install-offline-ubuntu.sh /secure-transfer/docker-ubuntu-amd64
sudo ./scripts/import-offline-images.sh /secure-transfer/copper-images-amd64
sudo ./scripts/m1-07-preflight.sh
```

离线安装脚本只接受 Ubuntu 24.04 amd64、固定版本 DEB 和固定 SHA-256，不访问软件仓库；镜像导入脚本只接受发布清单导出的三个不可变 amd64 镜像 ID。这里的镜像 ID 必须是归档 `index.json` 中经过逐对象 SHA-256 和 amd64 config 校验的 OCI manifest digest，保证 `docker load` 后仍可直接运行；禁止把构建机 `docker image inspect` 返回的 config digest 手工抄成目标机 ID。目标机无需 GHCR 凭据。

导入完成后，先原样查看介质中的非秘密运行值：

```bash
awk -F= '$1 == "COPPER_IMAGE_DELIVERY" || $1 == "COPPER_WEB_IMAGE" || $1 == "COPPER_BACKEND_IMAGE" || $1 == "COPPER_MYSQL_IMAGE" {print}' \
  /secure-transfer/copper-images-amd64/copper-images-amd64.env
```

随后把这四行写入 `/etc/copper/copper.env`。`*_SOURCE` 行只用于追溯原始仓库摘要，不写入运行配置。不要根据 `docker load` 输出猜测摘要；必须使用已通过 `import-offline-images.sh` 导入后复核的介质值。

M3 起的新版本升级不再手工拼接镜像目录。发布方应生成 `copper-delivery/v1` 联网或离线介质，客户服务器使用 `copperctl release stage online|offline` 校验并写入 root-only `READY` 证据；随后才允许进入 `upgrade preflight`。完整命令和边界见 [M3 发布物交付](M3_RELEASE_DELIVERY.md)。

如使用公网 IP 证书模式，还要在中转机下载发布清单指定的 `acme.sh-3.1.3.tar.gz`，核对 SHA-256 后随离线包传入 `/opt/copper/offline/`。目标机不在线下载证书客户端；签发证书时仍必须能够访问 Let’s Encrypt ACME 服务。

## 3. 生成配置模板

先执行只读 M5 主机预检。只有结论为 `PASS` 且 `writeAllowed=true` 才能进入安装：

```bash
sudo ./copperctl host preflight --json
```

预检会核对 Ubuntu 版本、x86_64 架构、CPU、内存、磁盘、可靠本地文件系统、Docker/Compose、systemd、时间同步、80/443 冲突、内部端口和已有数据归属。`FAIL` 或 `MANUAL_REVIEW` 都以退出码 3 阻断，且不会创建 `/etc/copper` 或 `/var/lib/copper`。正式 `install` 会再次执行同一预检，并把通过报告以 root-only `0600` 保存为 `/etc/copper/host-preflight.json`。

首次执行：

```bash
sudo ./copperctl install
```

第一次通常会创建 `/etc/copper/copper.env` 和随机数据库密钥，然后以退出码 3 提示配置尚未完成。这是正常的安全门槛，不是安装失败。

编辑配置：

```bash
sudo editor /etc/copper/copper.env
```

必须修改：

- `COPPER_SITE_ID`：填写稳定且不含客户秘密的站点代号；后续备份、恢复和永久清理都用它确认数据归属。
- `COPPER_RELEASE_VERSION`：填写当前交付索引或发布清单中的产品版本，不得凭镜像标签猜测。
- `COPPER_TLS_MODE=domain`：`COPPER_DOMAIN` 填客户真实域名，不能保留 `example.com`。
- `COPPER_TLS_MODE=ip`：`COPPER_DOMAIN` 填固定公网 IPv4，并确认 `COPPER_ACME_ARCHIVE` 指向已校验的离线归档。该模式只支持 `standard`。
- `COPPER_WEB_IMAGE`、`COPPER_BACKEND_IMAGE`：发布清单中的完整 `@sha256:` 摘要。
- `COPPER_DEPLOY_MODE`：Copper 自己管理公网证书时使用 `standard`；客户已有可信 HTTPS 反向代理时使用 `external-proxy`。
- `COPPER_DEPLOY_MODE=lan` 与 `COPPER_TLS_MODE=lan`：按 [M5-04 LAN 安全交付手册](M5_LAN_ACCESS.md)填写固定私网地址、网卡、允许网段和受控 DNS，再安装客户 CA 签发的叶证书。
- `COPPER_BACKUP_AGE_RECIPIENT`：只填写客户离线工具生成的 `age1...` 公钥；不得把 `AGE-SECRET-KEY-...` 私钥粘贴到服务器配置。

离线模式还必须逐字使用上一步介质输出的 `COPPER_IMAGE_DELIVERY=offline` 和三个 `COPPER_*_IMAGE=sha256:...`。保存后可只读复核非秘密字段：

```bash
sudo awk -F= '$1 == "COPPER_SITE_ID" || $1 == "COPPER_RELEASE_VERSION" || $1 == "COPPER_DEPLOY_MODE" || $1 == "COPPER_DOMAIN" || $1 == "COPPER_TLS_MODE" || $1 == "COPPER_IMAGE_DELIVERY" || $1 == "COPPER_WEB_IMAGE" || $1 == "COPPER_BACKEND_IMAGE" || $1 == "COPPER_MYSQL_IMAGE" || $1 == "COPPER_BACKUP_AGE_RECIPIENT" {print}' \
  /etc/copper/copper.env
```

这条复核命令不会输出数据库口令、TLS 私钥或会话秘密。字段仍为示例值、镜像值不在已校验介质中或公钥不是 `age1...` 时，不得重新运行安装。

不要修改 `/etc/copper`、`/var/lib/copper`、`/var/backups/copper`、`/var/lib/copper-restores` 四个正式路径，也不要手工填写数据库密码。旁路恢复默认只监听 `127.0.0.1:18080`，禁止设置为 8191；完整操作见 [M2_RESTORE.md](M2_RESTORE.md)。

## 4. 正式安装与验证

配置完成后重新执行：

```bash
sudo ./copperctl install
sudo copperctl doctor
sudo copperctl status
sudo copperctl monitor check
sudo copperctl monitor schedule status
sudo copperctl operations status
sudo copperctl host autostart status
```

LAN 模式在正式安装前还必须完成：

```bash
sudo copperctl lan certificate install --fullchain FILE --private-key FILE --trust-anchor FILE
sudo copperctl lan preflight --json
```

安装会自动把 Web 绑定到指定私网地址并应用 Docker `DOCKER-USER` 规则；失败时不会降级 HTTP。安装后运行 `sudo copperctl lan status --json`，并从独立桌面和移动设备完成客户 CA 信任和 HTTPS 验收。

只有 `install` 报告三层健康通过、`doctor` 没有“失败”项时，才能进入管理员交付。`install` 会同时启用完全本地的 5 分钟监控计时器；首次备份和离线恢复实证完成前，“可恢复”显示阻断是正确门槛，不得通过编辑状态文件消除。`operations status` 还会区分本机恢复点与整机损失恢复点；只有本机备份时显示 `NOT_SUPPORTED` 是正确结果，不能冒充异机容灾。详细边界见 [M4-05 日志、容量、RPO/RTO 与故障处置](M4_OPERATIONS_RPO_RTO.md)。

标准模式还必须从客户网络访问：

```text
https://客户域名/healthz
https://客户域名/readyz
```

浏览器证书必须由受信任机构签发，域名一致且未过期。外部代理模式由客户代理负责公网证书，Copper 只监听服务器回环地址。

公网 IP 模式使用 Let’s Encrypt 约 6 天有效期的短周期证书。安装程序会启用每日带随机延迟的自动续期计时器；`doctor` 会同时检查 SAN、剩余有效期、私钥权限和计时器。可手工检查：

```bash
sudo copperctl tls status
sudo copperctl tls renew
```

不得把 IP 证书私钥发给用户、放进发布包或改成全局可读。

## 5. 首次管理员交付

先检查凭据状态：

```bash
sudo copperctl admin init
```

客户授权管理员本人在可信交互终端查看一次性凭据：

```bash
sudo copperctl admin init --show
```

禁止截图、发群、写入工单或重定向到日志。客户应立即通过 HTTPS 登录并修改密码；改密会清除临时会话并销毁凭据文件。之后再次运行 `sudo copperctl admin init`，必须显示“没有待交付的首次管理员凭据文件”。

## 6. 客户交接清单

- [ ] 客户确认域名、证书和登录地址。
- [ ] LAN 模式已记录固定地址/DHCP 保留、内网 DNS、客户 CA 指纹、允许网段及桌面/移动端信任验收。
- [ ] `sudo copperctl doctor` 无失败项。
- [ ] 客户本人完成首次登录、改密和重新登录。
- [ ] 一次性凭据文件已自动销毁。
- [ ] 客户知道 `status`、`logs`、`doctor`、`restart` 的用法。
- [ ] `sudo copperctl host autostart status` 显示 `copper.service` 已启用且运行；重启 Ubuntu 虚拟机后无需人工按顺序启动组件。
- [ ] 若 Windows 是宿主机，已验证 Windows 重启后 Ubuntu 虚拟机自动启动；Copper 不依赖用户登录后手工开启虚拟机。
- [ ] 客户知道 `down`、`uninstall` 默认保留数据，永久删除必须使用独立危险脚本。
- [ ] 已准备独立外接盘、NAS 或 S3 目标；文件系统目标已执行 `storage enroll`，`storage status` 未把本机同盘副本冒充异机容灾。
- [ ] 已执行一次 `sudo copperctl backup create`，并从独立目标重新读取、校验实际备份。
- [ ] 客户已按 `M2_KEY_HANDOVER.md` 在离线设备生成并保管两份 age 恢复私钥副本；服务器仅配置对应 `age1...` 公钥。
- [ ] 公钥已完成客户确认人和独立见证人双人交接登记。
- [ ] 已执行 `sudo copperctl backup verify`，记录 `.copperbak` 外层 SHA-256 和 `last-success.json`。
- [ ] 客户已在离线设备用实际备份生成 `PASSED` 回执，服务器已登记回执并通过私钥扫描。
- [ ] 已检查 `sudo copperctl backup status`；如配置 Webhook，已验证失败、恢复和通知通道失败三种状态。
- [ ] `sudo copperctl monitor check` 已显示存活、就绪、可写、可恢复四层结论；备份与密钥交接完成后四层均正常。
- [ ] `sudo copperctl monitor schedule status` 显示 5 分钟本地计时器已启用，客户知道查看 `monitor status` 和 `monitor history`。
- [ ] `sudo copperctl operations status` 的日志强制合同通过，且客户理解当前 RPO/RTO 是工程目标还是合同承诺。
- [ ] 已记录发布版本、三个镜像摘要、安装时间、实施人员和验收结果。

## 7. 常用命令与退出码

```text
sudo copperctl up
sudo copperctl down
sudo copperctl restart
sudo copperctl status
sudo copperctl logs backend
sudo copperctl logs web --follow
sudo copperctl doctor
sudo copperctl monitor check
sudo copperctl monitor status --json
sudo copperctl monitor history --limit 20
sudo copperctl monitor schedule status
sudo copperctl operations status
sudo copperctl host preflight --json
sudo copperctl host autostart status
sudo copperctl lan preflight --json  # 仅 LAN 模式
sudo copperctl lan status --json     # 仅 LAN 模式
sudo copperctl tls status       # 仅公网 IP 证书模式
sudo copperctl backup create
sudo copperctl backup status
sudo copperctl backup key status
sudo copperctl backup verify /var/backups/copper/文件名.copperbak
sudo copperctl backup prune --dry-run
sudo copperctl backup schedule install
sudo copperctl backup restore status
sudo copperctl uninstall
```

- `0`：命令成功。
- `1`：运行、容器或健康检查失败。
- `2`：命令参数错误。
- `3`：安装前置条件或配置尚未满足。

`down` 删除容器和网络但保留数据；`uninstall` 还会移除 `/usr/local/sbin/copperctl` 链接，但仍保留发布目录、配置、密钥、数据库、附件和备份。

`uninstall` 会禁用并移除 Copper 的 Ubuntu systemd 自启动，但不会删除 `/etc/copper`、`/var/lib/copper`、备份或附件；在同一发布目录重新执行 `install` 时，预检必须识别为 `managed-reinstall`，禁止生成新密钥覆盖既有数据库。永久删除仍只能使用独立危险脚本和站点标识双重确认。
