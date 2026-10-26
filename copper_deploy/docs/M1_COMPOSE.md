# M1-04 正式 Compose 编排

## 支持范围

M1 仅支持 Ubuntu Server 24.04 LTS、amd64、Docker Engine 29.x、Compose Plugin 5.x、MySQL 8.4.x LTS和单站点部署。ARM64 的 `local-database` 只承担隔离开发验收，不能替代 M1-07 的标准 amd64 验收。

Compose 只接受发布清单中的前后端不可变镜像摘要。MySQL 冻结为 `mysql:8.4.10@sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`，该清单同时包含 amd64 和 arm64 镜像。

## 网络与 TLS 模式

### 标准公开域名模式

`COPPER_DEPLOY_MODE=standard` 时，Web/Caddy 是唯一宿主机入口：

- 发布 TCP 80、TCP 443 和用于 HTTP/3 的 UDP 443。
- Caddy 根据 `COPPER_DOMAIN` 自动申请、续期证书并把 HTTP 重定向到 HTTPS。
- Backend 8191 与 MySQL 3306 仅加入 `internal: true` 网络，没有宿主机端口映射。
- `/api` 原样转发到 Backend，不剥离也不重复添加 `/api`。

公开域名必须提前把 A/AAAA 记录指向服务器，并在云安全组放行 80/443。证书状态持久化在 `/var/lib/copper/caddy`。

### 客户已有反向代理

`COPPER_DEPLOY_MODE=external-proxy` 时，脚本自动合并 `compose.external-proxy.yaml`：

- 完全替换 80/443 发布规则，只监听 `127.0.0.1:COPPER_PROXY_PORT`。
- 客户代理负责公网 TLS、域名证书和转发来源地址。
- 客户代理必须把完整路径转发到 Copper，不得剥离 `/api`。
- Backend 仍要求 `Secure` Cookie，浏览器必须通过外部 HTTPS 域名访问，不能把内部 HTTP 地址直接交给用户。

没有公开域名、没有可信证书且没有客户反向代理时，不属于当前正式登录基线；安装程序不得静默降级成不安全 HTTP。

### 客户局域网模式

`COPPER_DEPLOY_MODE=lan` 与 `COPPER_TLS_MODE=lan` 时，脚本合并 `compose.lan.yaml`，只在客户保留的 RFC1918 地址发布 80/443，并使用客户 CA 签发的证书。地址、DNS、证书信任、轮换和 `DOCKER-USER` 防火墙合同见 [M5-04 手册](M5_LAN_ACCESS.md)。该模式不启用 ACME，不要求公网入口，也禁止用 `.local`、单机 hosts 或单张自签名叶证书冒充正式多人交付。

## 持久数据

| 数据 | 宿主机目录 | 容器删除后 |
| --- | --- | --- |
| MySQL | `/var/lib/copper/mysql` | 保留 |
| Backend 数据根 | `/var/lib/copper/backend` | 保留 |
| 附件 | `/var/lib/copper/files` | 保留 |
| Backend 日志 | `/var/lib/copper/logs` | 保留 |
| 应用备份工作目录 | `/var/lib/copper/backups` | 保留 |
| `copperctl` 停机备份包 | `/var/backups/copper` | 保留，且不与数据目录递归打包 |
| Caddy 证书与状态 | `/var/lib/copper/caddy` | 保留 |
| 站点配置与随机密钥 | `/etc/copper` | 保留 |
| 首次管理员一次性凭据 | `/etc/copper/credentials/initial-admin.txt` | 首次改密成功后由 Backend 删除 |

普通 `stop`、`restart`、`down` 和 `uninstall` 都不会删除上述目录，也不使用 `docker compose down -v`。永久删除只能运行独立脚本，并同时提供正确站点标识和输入二次确认文本；确认后会同时删除 `/etc/copper`、`/var/lib/copper` 和 `/var/backups/copper`。

## 安装准备

```bash
sudo ./scripts/prepare-host.sh
sudo editor /etc/copper/copper.env
sudo ./scripts/compose.sh config
sudo ./scripts/compose.sh up
sudo ./scripts/compose.sh status
```

`prepare-host.sh` 可重复执行：已有配置、密钥和数据目录不会被覆盖。首次执行随机生成 MySQL root 和 Copper 业务账号口令，密钥不会出现在 Compose 环境变量、镜像或 Git 中。

## 健康门槛

- MySQL：`mysqladmin ping` 表示数据库进程可以接受连接。
- Backend：Compose 健康检查使用 readiness，只有 Flyway 成功且数据库可用时才健康；镜像内 liveness 仍可单独诊断进程。
- Web：容器内部 `127.0.0.1:8081/healthz` 只判断 Caddy 存活；公开 `/readyz` 继续检查 Backend readiness。
- Web 依赖 Backend 健康，Backend 依赖 MySQL 健康；迁移失败时 Web 不会被 Compose 判定为整站就绪。

## 防火墙边界

Docker 发布端口的流量可能在到达 Ubuntu UFW 的常规 INPUT/OUTPUT 规则前被转发，因此不能只看 `ufw status` 就宣称端口安全。正式验收必须同时检查云安全组或 LAN 允许网段、`DOCKER-USER`、`docker compose config`、`docker ps`、`ss -lntup` 和从外部网络实际探测的端口。标准模式只允许 80/443；LAN 模式还只绑定指定私网 IP 并按 CIDR 收口；外部代理模式只允许回环地址上的代理端口。

## 卸载与永久删除

保留数据卸载：

```bash
sudo ./scripts/compose.sh uninstall
```

永久删除必须单独执行，且不可恢复：

```bash
sudo ./scripts/permanently-delete-data.sh --site-id copper-prod
```

M1 的基础备份只用于同版本停机式恢复；跨版本升级、自动回滚、异机完整恢复和对象存储保留属于 M2/M3，当前不得对客户宣传为已完成。

## 2026-07-13 隔离验收记录

本次只在 `ec2-user@local-database` 使用隔离目录和本地测试镜像，不在开发电脑运行容器。Copper 编排自身没有发布宿主机 8191/3306；该机器原有 `vm-mysql` 的 3306 映射不属于 Copper 站点。

- Docker Engine 29.5.3、Compose Plugin 5.1.4、Linux ARM64 和 MySQL 8.4.10。
- 标准模式实际绑定 TCP 80、TCP/UDP 443，Caddy 内部 TLS成功；Backend/MySQL 无宿主机端口。
- 外部代理模式实际只绑定 `127.0.0.1:18080`，合并后不存在遗留 80/443 规则。
- 空库自动执行 Flyway V1，Web、Backend readiness、MySQL 三层健康依赖通过；登录 Cookie 保持 `Secure`、`HttpOnly`、`SameSite=Strict`。
- 随机密钥重复准备不变；停止、启动、重启、强制容器重建和卸载后，数据库管理员记录、Flyway 状态与附件探针均保留。
- 故意替换错误数据库密钥后 Backend 无法健康，Web 不进入整站就绪；恢复原密钥后服务和原数据恢复。
- 错误站点标识或错误二次确认均不会执行永久删除。
- 在 `/etc/copper`、`/var/lib/copper` 和 `/opt/copper-m1-04-test` 建立持久化隔离站点后执行真实宿主机重启，启动标识从 `a4e4f62c-fcf4-407f-ad36-8f88546a7abe` 变为 `12e221ac-511a-49e5-9a38-e4cb473e048c`。
- 重启后 Copper 的 MySQL、Backend、Web 自动恢复为 healthy，既有 `vm-mysql`、`vm-valkey` 也按原有 `restart=always` 自动恢复；回环入口仍只有 `127.0.0.1:18080`，Copper Backend/MySQL 仍无宿主机端口。
- 重启前后的数据库业务口令、MySQL root 口令、首次管理员凭据、环境配置和附件探针 SHA-256 完全一致；Flyway 成功记录 1 条、管理员 1 条、业务库 88 张表，首次管理员实际登录、`/healthz` 和 `/readyz` 均返回 200。
- 重启后再次执行 `prepare-host.sh` 与 `compose.sh up`，原密钥、数据和附件继续保留，三层健康门槛再次通过。

M1-04 的 `local-database` 隔离验收门槛已关闭。M1-07 仍需在标准 Ubuntu 24.04 amd64 空白机重复完整验收；ARM64 结果不能替代最终交付环境认证。

## 官方行为依据

- [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https)：公开域名、80/443、可写持久化数据目录和正确 DNS 是自动签发与续期的前提。
- [Docker Compose 多文件合并](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)：外部代理 override 必须通过最终 `docker compose config` 核对端口合并结果。
- [Docker 防火墙说明](https://docs.docker.com/engine/network/packet-filtering-firewalls/)：Docker 发布端口流量可能绕过 UFW 常规链，必须同时使用云防火墙和外部端口探测验收。
