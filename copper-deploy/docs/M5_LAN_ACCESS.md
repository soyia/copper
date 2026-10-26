# M5-04 局域网访问、HTTPS、名称解析与防火墙

> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/64>

## 1. 正式拓扑与非目标

Copper 的正式 LAN 入口是 Ubuntu 24.04 x86_64 主机或虚拟机上的一个固定 RFC1918 IPv4、一个客户控制域名的内网 A 记录，以及客户 CA 签发的 HTTPS 叶证书。Web 只绑定该私网 IPv4；Backend 8191、MySQL 3306 和恢复端口不发布到宿主机或 LAN。

Windows 只承载 Ubuntu 虚拟机。Copper 不修改 Windows 网络，不在 Windows 原生、WSL2 或 Docker Desktop 中运行。虚拟机使用桥接网卡或由客户明确管理的受控 LAN 接入；不得把 UPnP、路由器端口转发、DMZ 主机、公网裸露或弱 HTTP 当作“局域网安装”。

`.local`/mDNS、单机 `hosts` 文件和直接输入临时 IP 只用于排障，不构成正式多人名称解析。单张自签名叶证书也不构成正式方案：叶证书一旦轮换，所有设备都会失去信任。小微企业的最低可维护方案是“离线保管的自签名根 CA + 由该根 CA 签发的可轮换叶证书”；有企业 PKI 时直接使用客户现有 CA。

## 2. 固定地址与受控 DNS

实施人员应先选择以下一种地址方式：

1. 推荐：在路由器/DHCP 服务器按 Ubuntu 虚拟机网卡 MAC 地址保留固定租约；
2. 备选：由网络管理员在 Ubuntu Netplan 配置静态地址，并确保地址不在 DHCP 动态池中。

Copper 不自动修改路由器、Netplan 或客户 DHCP。完成后记录网卡、私网地址、网关、允许访问网段和负责人员，并在 `/etc/copper/copper.env` 设置：

```dotenv
COPPER_DEPLOY_MODE=lan
COPPER_TLS_MODE=lan
COPPER_DOMAIN=copper.office.customer-domain.com
COPPER_LAN_BIND_IP=192.168.50.20
COPPER_LAN_INTERFACE=ens33
COPPER_LAN_DNS_SERVER=192.168.50.1
COPPER_LAN_ALLOWED_CIDRS=192.168.50.0/24
COPPER_LAN_ADDRESS_RESERVED=yes
COPPER_LAN_DOMAIN_CONTROL_CONFIRMED=yes
COPPER_LAN_NAME_RESOLUTION=dns
```

客户内部 DNS 或具备本地 DNS 能力的路由器应配置唯一 A 记录：

```text
copper.office.customer-domain.com.  A  192.168.50.20
```

该名称应属于客户控制的真实域名。LAN 模式不要求把该记录发布到公网；使用 split DNS 时，LAN 内只返回固定私网 IPv4。`COPPER_LAN_DNS_SERVER` 必须是客户路由器或内部 DNS 的私网 IPv4；预检会直接向它查询 A 记录，并同时要求 Ubuntu 系统解析得到相同结果，不能用 `/etc/hosts` 冒充受控 DNS。`copperctl lan preflight` 会拒绝公网地址、`0.0.0.0/0`、`.local`、示例域名、未保留地址，以及解析结果与绑定地址不一致的配置。

LAN 验收完成后，如客户确实需要互联网访问，可选方案见 [Cloudflare Tunnel 远程访问手册](M5_CLOUDFLARE_REMOTE_ACCESS.md)。公网与 LAN 必须继续使用同一 hostname：LAN DNS 返回固定私网地址，公共 DNS 才进入 Cloudflare；不得让内网用户无意义地绕行公网。Tunnel 失败不能影响本页定义的 LAN 核心入口。

## 3. 客户 CA 证书

客户 CA 根私钥不得复制到 Copper 服务器。服务器只需要：

- 含 `DNS:copper.office.customer-domain.com` SAN 的服务器叶证书和必要中间证书；
- 与叶证书匹配的私钥；
- 供客户端导入和服务端校验的客户根 CA 证书。

叶证书至少保留 30 天有效期，建议提前 60 天轮换。安装命令会检查域名 SAN、私钥匹配、信任链、自签名叶证书和剩余有效期，再以受限权限写入固定目录：

```bash
sudo copperctl lan certificate install \
  --fullchain /secure-transfer/copper-lan-fullchain.pem \
  --private-key /secure-transfer/copper-lan-privkey.pem \
  --trust-anchor /secure-transfer/copper-lan-root-ca.pem

sudo copperctl lan preflight --json
sudo copperctl install
sudo copperctl lan status --json
```

轮换时先签发相同 DNS SAN 的新叶证书，再执行同一命令并增加 `--reload`。Copper 只重启 Web 容器并等待健康，MySQL 和 Backend 不重启；新证书加载失败时恢复 root-only 旧证书并再次恢复 Web。归档私钥不得交给客户端。轮换完成后验证桌面和移动浏览器、Cookie、上传、打印和备份下载。

如果根 CA 私钥丢失，不得伪造或从服务器恢复。应创建新 CA，先把新根证书部署到全部客户端并保留旧根的过渡信任，再签发/切换新叶证书，最后在确认无旧客户端后撤销旧根。只有叶证书私钥丢失时，立即撤销/停用旧叶证书，使用仍由客户离线保管的 CA 签发新叶证书即可。

## 4. 客户端信任交付

只向客户端分发 `lan-ca.pem` 对应的公开根证书，不得分发服务器私钥或 CA 私钥。交付前和导入后都要通过独立渠道核对 SHA-256 指纹。

- Windows 桌面：由管理员把根证书加入本机 `Root` 信任库，例如在提升权限终端运行 `certutil -addstore -f Root copper-lan-root-ca.cer`，再用 `certutil -store Root` 核对指纹。命令语义见 [Microsoft certutil 官方文档](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/certutil)。
- macOS：优先使用组织管理的配置描述文件；小规模设备可在“钥匙串访问”中导入到“系统”钥匙串并明确设为 TLS 信任。macOS 13 及以后，手工配置描述文件中的根证书默认未必自动获得 TLS 信任，见 [Apple 证书管理说明](https://support.apple.com/guide/deployment/intro-to-certificate-management-depb5eff8914/web)。
- iPhone/iPad：优先用 Apple Configurator 或 MDM。手工安装后还必须到“设置 > 通用 > 关于本机 > 证书信任设置”启用该根证书的完全信任；Apple 的当前说明见 [手工证书信任文档](https://support.apple.com/102390)。
- 其他受管设备：由客户管理员使用其操作系统/MDM 的“受信任根证书”能力统一下发。不得让用户忽略浏览器证书警告继续访问。

每台正式客户端至少记录：设备类型、系统版本、根 CA 指纹、导入/移除人员、时间、`/healthz`、`/readyz` 和登录结果。根 CA 是客户安全资产，离职设备、报废设备和非授权个人设备必须移除信任。

## 5. Docker 防火墙边界

LAN Compose 覆盖文件把 80/TCP、443/TCP、443/UDP 绑定到 `COPPER_LAN_BIND_IP`，不会绑定公网或其他宿主机地址。80 只做 HTTPS 永久重定向；正式业务始终使用 443。

仅配置 UFW 不足以证明 Docker 发布端口安全。Docker 官方说明，已发布容器端口的流量可能在 UFW 的 `INPUT`/`OUTPUT` 规则前被转发；`DOCKER-USER` 是 iptables 后端中供用户规则在 Docker 自身转发规则前执行的边界。参见 [Docker 防火墙说明](https://docs.docker.com/engine/network/packet-filtering-firewalls/)和 [Docker iptables 链说明](https://docs.docker.com/engine/network/firewall-iptables/)。

因此 Copper 在每次 `install`、`up` 和 `restart` 前应用专用 `COPPER-LAN` 链：

- 只允许 `COPPER_LAN_INTERFACE` 上来自明确 RFC1918 CIDR 的 443/TCP、443/UDP 和用于重定向的 80/TCP；
- 对同一绑定地址的其他来源丢弃上述流量；
- 不修改 SSH、UFW 默认策略或其他容器规则；
- Docker 没有 `DOCKER-USER` 链时 fail-closed，不退化成只看 UFW；
- 普通卸载移除专用链，但保留证书、配置、数据和备份。

```bash
sudo copperctl lan firewall apply
sudo copperctl lan firewall status
sudo copperctl lan status --json
```

## 6. 地址、证书和端口故障

- 地址变化：恢复原 DHCP 保留/静态地址；不要临时改 DNS 或放宽到 `0.0.0.0/0`。确需换地址时，先为新地址设置保留、更新内网 DNS、修改配置并完成桌面/移动端复验。
- 域名变化：必须先签发包含新 SAN 的证书并部署客户端信任，再在维护窗口修改 `COPPER_DOMAIN`；Cookie 是按域名隔离的，用户需要重新登录属于预期行为。
- 证书未受信：停止交付，核对根 CA 指纹和导入位置；禁止点击“继续访问不安全网站”。
- 证书过期/丢失：按第 3 节重新签发并受控轮换；不得降级 HTTP。
- 80/443 冲突：用 `ss -lntup` 查明归属，不自动停止未知服务。
- 网卡或 Docker 防火墙后端变化：`lan status` 必须阻断；先恢复已认证的 iptables/`DOCKER-USER` 基线，再恢复业务。

## 7. 退出验收

Ubuntu x86_64 自动化与真实主机证据见 [M5-04 局域网安全验收报告](M5_LAN_ACCEPTANCE_REPORT.md)。该报告明确区分已通过的主机/网络门槛与仍待实体设备完成的退出项。

- [ ] 固定地址或 DHCP 保留有客户网络管理员记录。
- [ ] 客户控制域名在 LAN 内唯一解析到绑定私网地址，`.local`/hosts 不计入验收。
- [ ] 客户 CA 根私钥不在服务器，叶证书 SAN/私钥/链/有效期通过。
- [ ] 同一 LAN 的独立桌面与移动设备均无证书警告访问 HTTPS，并完成登录。
- [ ] 地址或证书变化后，登录 Cookie、上传、打印和备份下载仍可用。
- [ ] 允许网段可访问 443，80 只重定向；非允许来源被拒绝。
- [ ] 从独立客户端扫描证明 3306、8191 和恢复端口不可达。
- [ ] 禁止公网端口转发、UPnP、DMZ、弱 HTTP 和浏览器忽略证书警告。
