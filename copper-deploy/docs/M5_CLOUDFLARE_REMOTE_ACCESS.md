# M5 可选互联网远程访问：Cloudflare Tunnel

> 当前状态：`INTERNAL_REHEARSAL_ONLY`。这是 LAN 部署的可选能力，不是 Copper 核心业务前置条件，也不构成 Cloudflare、网络质量、SLA、中国大陆可用性或合规承诺。正式客户交付前必须在客户真实网络、真实终端和企业合规边界内单独验收。

## 1. 一期范围

一期只支持 Cloudflare Tunnel，不同时接入花生壳、通用 VPN 或其他穿透供应商。命令空间保留为 `remote-access <provider>`，以后可以增加提供商，但不得复用 Cloudflare token、状态或删除语义。

该能力只适用于：

- `COPPER_DEPLOY_MODE=lan`；
- `COPPER_TLS_MODE=lan`；
- 客户控制同一个真实域名；
- LAN 内部 DNS 把该域名解析到 Copper 固定私网地址；
- 公共 DNS 由 Cloudflare Tunnel 路由同一域名；
- Cloudflare Access 对匿名请求默认拒绝，只允许客户明确授权的用户或组。

标准公网部署和 `external-proxy` 部署不使用本边车。不得同时打开路由器端口转发、UPnP、DMZ 主机或直接发布 Backend 8191/MySQL 3306。

## 2. 安全与网络结构

```text
LAN 用户 ──客户 DNS/CA──> Web:443
                              │
互联网授权用户 ── Access ── Cloudflare Edge
                              │ 加密出站 Tunnel
                         cloudflared 边车
                              │ edge 网络内 HTTP
                            Web:8080
                              │ data 内部网络
                     Backend:8191 ── MySQL:3306
```

`cloudflared`：

- 只加入 `edge` 网络，不加入内部 `data` 网络，因此不能直接访问 Backend 或 MySQL；
- 不发布 ERP 业务端口，只把自身 metrics 映射到宿主机 `127.0.0.1:20241`；
- 使用不可变镜像摘要、只读根文件系统、`cap_drop: ALL` 和 `no-new-privileges`；
- 通过 `/run/secrets/cloudflare_tunnel_token` 读取 token，token 不进入 Compose 命令、进程参数、环境变量、状态文件或 Copper 备份；
- 只连接 Web 的 Docker 内部入口 `http://web:8080`。该入口不发布到宿主机。

官方 cloudflared 镜像默认使用非 root 用户，但 Docker Compose 对 `file` 来源的 secret 使用只读 bind mount，不能把宿主机 `root:root 0600` 的 uid/gid 重映射给容器用户。为同时守住宿主机 root-only token，本覆盖文件只在容器内把 cloudflared 用户设为 `0:0`；容器仍丢弃全部 capabilities、禁止提权、根文件系统只读、限制 PID，且不挂载 Docker socket、Copper 数据目录或内部 `data` 网络。不得通过放宽宿主机 token 为 `0644` 来规避该限制。

Cloudflare Tunnel 是一条由客户服务器主动建立的出站连接，不需要公网 IPv4 或路由器入站端口。它仍会让授权用户的 ERP HTTP 请求、响应和附件经过 Cloudflare 数据路径；客户必须据此完成数据处理、跨境路径、行业要求和内部授权评估。

## 3. Cloudflare 控制台准备

由客户的 Cloudflare 管理员在客户自己的账号中完成。Copper 不保存账号级 API token，也不会代替客户删除云端资源。

1. 把域名接入客户 Cloudflare 账号。
2. 创建一个 **remotely-managed tunnel**，为该客户站点单独使用，不与其他客户复用。
3. 复制 Tunnel 安装命令中的 token。任何得到该 token 的人都能运行连接器，因此只能在可信终端中交给本脚本。
4. 为 Tunnel 创建 Public Hostname：
   - Hostname：必须与 `COPPER_DOMAIN` 完全相同；
   - Service：`http://web:8080`。
5. 为同一 hostname 建立 Cloudflare Access Self-hosted 应用：
   - 匹配整站路径；
   - 默认拒绝匿名访问；
   - 只添加客户批准的用户、组或身份提供商策略；
   - 不建立绕过整站的 Bypass 策略。
6. 公共 DNS 保持 Cloudflare 代理路由；客户 LAN DNS 继续覆盖为 Copper 固定私网地址。这样内网用户不绕行公网，互联网用户才进入 Access。

相关官方资料：

- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/)
- [远程管理 Tunnel 与 token 权限](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/remote-tunnel-permissions/)
- [Cloudflared 运行参数](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/run-parameters/)
- [Tunnel metrics](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/monitor-tunnels/metrics/)

## 4. 选项式配置与启用

普通用户不复制 `copperctl` 子命令。优先使用安装介质中的 Mac 一站式维护入口：

1. 打开 `copper-manage-from-mac`；
2. 选择要维护的 Copper 站点；
3. 选择“互联网远程访问（Cloudflare，可选）”；
4. 选择“首次配置”，按提示粘贴两次 Tunnel Token；输入不回显；
5. 按菜单给出的检查清单完成 Cloudflare Public Hostname 和 Access；
6. 返回同一个菜单，选择“启用并验证”。

如果操作者就在 Ubuntu 服务器前，则打开 `copper-maintain`，选择“互联网远程访问（Cloudflare，可选）”，后续选项与 Mac 完全相同。底层 `copperctl remote-access` 只保留给自动化、测试和专业故障诊断，不作为客户操作手册要求复制的步骤。

脚本把正式 token 原子写入：

```text
/etc/copper/remote-access/cloudflare/tunnel-token
```

文件必须是 `root:root 0600`。自动化内部演练如使用一次性 root-only 来源文件，由操作者按本单位秘密销毁流程处理；程序不会擅自删除用户选择的来源文件。

配置完成后仍是停用状态。只有操作者返回菜单并选择“启用并验证”，程序才会启动连接器。

启用按顺序执行：

1. 校验 LAN 模式、同源 hostname、不可变 cloudflared 摘要、token 权限和端口；
2. 只在管理员显式启用时拉取缺失的摘要镜像；离线环境应先通过受控介质导入同一摘要；
3. 校验 Compose，并启动 Web 与 cloudflared；
4. 从 Web 容器内部验证 `http://127.0.0.1:8080/readyz`；
5. 从本机 loopback metrics 验证 `cloudflared_tunnel_ha_connections > 0`；
6. 通过公共 DNS 和 Cloudflare 边缘地址发起无 Cookie 的匿名 HTTPS 请求，必须重定向到 Cloudflare Access。

三项健康检查必须分别通过。Cloudflare 显示 Tunnel `Healthy` 只证明连接器到 Cloudflare 的连接，不能替代 origin 或 Access 策略检查。任一步失败，脚本会停止并移除 cloudflared 容器，保持 `COPPER_REMOTE_ACCESS_ENABLED=no`，不会留下一个未受 Access 保护的入口。

## 5. 日常维护

从 Mac 或服务器的“互联网远程访问（Cloudflare，可选）”菜单直接选择：

- “检查状态”：报告 token 文件、内部 Web origin、cloudflared 容器、HA 连接和 Access 匿名拦截，不显示 token；
- “轮换 Tunnel token”：不回显地输入两次新 token，已启用站点会自动重建、复验，失败时恢复旧 token；
- “停用”：停止并移除 cloudflared 容器，保留本地配置和 token；
- “移除本地 Cloudflare 配置与 token”：再次选择当前站点后执行精确删除，不要求复制站点确认命令。

状态结果分别报告：

- token 文件权限与格式；
- Web 内部 origin；
- cloudflared 容器和 HA 连接数；
- 公网匿名请求是否进入 Access；
- LAN 核心业务影响固定为 `NONE`。

已启用状态下会用新 token 重建连接器并复验连接与 Access；失败时恢复旧 token。客户还必须按 Cloudflare 控制台的 token 轮换/撤销流程确认旧 token 已失效。仅替换本机文件不等于云端撤销。

停用只停止并移除 cloudflared 容器，保留本地配置和 token，LAN、数据库、图片与备份继续运行。

移除会删除本地 connector、token 和状态，保留 Copper 数据、附件、镜像和备份，并在 `/var/lib/copper/remote-access/cloudflare-removal.json` 记录不含秘密的回执。回执明确标记 `cloudCleanup=PENDING_CUSTOMER_ACTION`；客户仍须在 Cloudflare Dashboard 删除 Tunnel、Public Hostname 和 Access 应用/策略。

## 6. 备份、恢复、升级与域名变更

- `copper-backup/v2` 不包含 Tunnel token、Cloudflare 账号配置或远程访问运行状态。
- 恢复站点不得自动恢复公网入口。恢复验证完成后，由客户重新创建或确认 Tunnel 与 Access，再从维护菜单依次选择“首次配置”和“启用并验证”。
- 发布包升级必须重新扫描固定摘要的 cloudflared 镜像、更新 SBOM/第三方权利证据，并重跑本页受影响验收。
- 更换域名前先从维护菜单选择“停用”；随后同时变更 LAN DNS、客户 CA 证书、Cloudflare Public Hostname 和 Access 应用，最后从菜单重新配置并启用。不得只改 Copper 域名造成 origin、Cookie、CORS 或 Access 范围不一致。

## 7. 故障与验收口径

Cloudflare、公共 DNS、身份提供商或互联网故障时：

- 远程访问可降级或停止；
- LAN 固定地址、客户 DNS、客户 CA HTTPS 和本地登录继续可用；
- 采购、入库、库存、销售、出库、应收应付、收付款和本地备份不能依赖 Tunnel；
- `doctor` 只对可选远程访问发出提醒，不把 Cloudflare 故障解释为 LAN 核心业务失败。

正式客户网络退出验收至少包括：

- [ ] 独立互联网终端匿名访问只能进入 Cloudflare Access，不能直接看到 Copper 登录页。
- [ ] 授权用户完成登录、刷新、退出、附件上传下载和超时重认证。
- [ ] 未授权用户、撤销用户和错误组织被拒绝。
- [ ] LAN 桌面和移动终端仍通过私网地址访问，不绕行 Cloudflare。
- [ ] 外部扫描确认宿主机未新增公网 80/443/8191/3306 入站暴露。
- [ ] 断开公共互联网后，LAN 核心业务与本地备份继续运行。
- [ ] 轮换 token、停用、重新启用和本地移除均有不含秘密的证据。
- [ ] 客户确认 Cloudflare 套餐、账号所有权、Access 用户上限和支持边界。
- [ ] 中国大陆实际运营商网络完成时延、丢包、登录、上传下载和连续运行测试。
- [ ] 企业负责人、信息安全/法务或其他适用专业人员确认数据路径与合规边界。

没有完成以上真实验收时，只能表述为“已提供 Cloudflare 可选连接器”，不得承诺免费 SLA、稳定跨境访问、固定中国大陆质量或正式客户交付完成。
