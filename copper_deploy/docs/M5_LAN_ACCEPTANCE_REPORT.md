# M5-04 局域网 HTTPS 与防火墙验收报告

> 跟踪 Issue：<https://github.com/soyia/copper_deploy/issues/64>
> 验收日期：2026-07-15
> 历史结论：部署实现与真实 x86_64 主机门槛通过；Mac/手机/A4局部实体证据已在M5-06完成，但本地主机、Windows宿主VM和稳定LAN全生命周期未完成。
>
> M7-06决定：本地/LAN已移出 `1.0.0` 首发标准兼容清单，Issue #64按 `not planned for 1.0` 收口；不是验收通过，也不产生本地 `Certified`。详见 [M7-06范围决定](M7_06_MULTI_ENVIRONMENT_SCOPE_DECISION.md)。

## 1. 验收环境

- 全新 Ubuntu 24.04 LTS x86_64 AWS 测试主机；Docker Engine 29.1.3、Docker Compose 5.1.4。
- 测试前 `/etc/copper`、`/var/lib/copper`、`/var/backups/copper`、`/var/lib/copper-restores` 均不存在，容器数量为 0，80/443/3306/8191 均未监听。
- 使用已在主机校验的 amd64 离线镜像 ID：
  - Web：`sha256:cdb833377a89e1a9efe30876024ee2a7a551b650b9eb97207ae1efaf36923e70`
  - Backend：`sha256:bbd1a9bcf24588ec96382c0e02bbe75f16d94281c0487723afbcf16256128096`
  - MySQL 8.4：`sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`
- 客户控制测试域名：`copper.m5-lan.pwagrowing.com`；受控测试 LAN 从 `192.168.250.1` 迁移到 `192.168.250.4`。
- 独立客户端使用 Linux network namespace、独立来源地址和独立 DNS；这能证明真实转发、Docker NAT 与防火墙边界，但不能替代实体桌面和移动设备验收。

## 2. 已通过场景

| 场景 | 结果 | 可复核要点 |
| --- | --- | --- |
| 空白安装 | PASS | 首次配置门槛、客户 CA、受控 DNS、固定地址、Compose 三层健康均通过 |
| TLS 身份 | PASS | 根 CA 为 `CA:TRUE`、叶证书 SAN/私钥/链/30 天有效期通过；拒绝单张自签名叶证书和弱私钥/签名 |
| 最小端口 | PASS | Web 仅绑定指定 RFC1918 地址的 80/TCP、443/TCP、443/UDP；3306、8191、18080 从独立客户端不可达 |
| 来源控制 | PASS | 允许来源可访问；同一测试 LAN 的非允许来源被 `DOCKER-USER`/`COPPER-LAN` 拒绝 |
| HTTP 行为 | PASS | 80 仅以 308 跳转到同域 HTTPS；未导入客户 CA 时 HTTPS 失败 |
| Cookie 与首次管理员 | PASS | `Secure`、`HttpOnly`、`SameSite=Strict`；首次改密撤销旧会话并销毁一次性凭据，新密码重新登录成功 |
| 主机重启 | PASS | LAN 网卡、DNS、Docker、Copper、三容器和防火墙自动恢复，既有登录会话仍有效 |
| 地址迁移 | PASS | 更新绑定地址、DNS 和允许 CIDR 后旧地址停止服务，新地址健康，业务会话保持 |
| 叶证书轮换 | PASS | 只重启 Web 容器并等待健康；MySQL/Backend 不重启；服务端证书序列号确实变化，登录会话保持 |
| 完整导出下载 | PASS | 地址/证书变化后经 LAN HTTPS 创建并下载完整业务导出；任务 SHA、响应头 SHA 与文件 SHA 一致 |
| 加密整站备份 | PASS | 地址/证书变化后完成备份创建、正式校验、离线密钥解密验证和恢复演练登记；业务恢复健康且会话保持 |
| 永久清理 | PASS | 正式配置、数据、备份、恢复目录、容器、网络、测试 namespace、systemd unit 和 `COPPER-LAN` 链全部清除 |

## 3. 真实验收发现并修复的问题

1. 初版防火墙放行了请求包，但全局 DROP 误杀返回包。已改为仅允许回到同一 LAN 网卡、同一允许 CIDR、同一原始目标端口的 `ESTABLISHED,RELATED` 回程流量，没有扩大入站来源。
2. Caddy `redir ... permanent` 实际返回 301。已改成明确 308，避免误发到 HTTP 的非 GET 请求被客户端改变方法。
3. 初版验收错误要求改密后的旧 Cookie 继续有效。已按后端安全契约改为验证旧会话失效，再使用新密码建立新会话。
4. 证书文件原子替换后，`docker compose up` 不会让配置未变的 Caddy 重新读取证书。已改为只重启 Web 并等待健康；若新证书失败则恢复 root-only 旧证书并再次恢复 Web。
5. 验收运行目录曾包含 Cookie 原始响应头和临时凭据。最终脚本只保留脱敏布尔结果与 SHA-256，清理阶段销毁 Cookie、管理员密码、离线身份和其他运行秘密。

## 4. 证据与清理状态

- AWS 证据目录：`/opt/copper-m5-04-acceptance/evidence`。
- 目录与文件均为 `root:root`，目录模式 `0700`，证据清单模式 `0600`。
- 共 36 个证据文件；`SHA256SUMS` 已逐项复验通过。
- `SHA256SUMS` 文件本身 SHA-256：`f912593df8722a5da3610fae0dc20155721b96bc31acb8cf67619b63b9be9f2c`。
- 完整导出文件验证后已销毁，只保留 SHA-256：`4b39cca2db76e7baeab1a4b739b3e7326e46d6b8bcf8a44498f99716f2cfdf7d`。
- 最终代码另完成 132 项 Python 回归、静态契约和 CLI 契约；真实 x86_64 隔离网络合同再次通过，并实际拒绝 RSA 1024 私钥和 SHA-1 叶证书。脱敏网络合同证据位于 `/opt/copper-m5-04-network-contract-evidence`，其中证书工作目录和所有私钥已销毁。
- 客户根 CA 私钥未进入 Copper 正式目录；验收运行秘密和两次失败重跑目录均已删除。
- 清理后正式数据路径均不存在、容器数量为 0、`COPPER-LAN` 链和测试 namespace 均不存在。

## 5. 尚未完成的退出门槛

- 在实际 Windows 宿主机上选定虚拟化产品，验证 Ubuntu 客体桥接/受控 LAN 网卡以及随 Windows 启动和重启恢复。
- 在同一真实 LAN 的独立桌面和移动设备导入客户根 CA，确认无证书警告并完成登录。
- 按 M5-06 使用真实浏览器、打印机/打印预览和实际附件上传复验；本次仅证明网络、会话、导出下载和备份链路在地址/证书变化后不被破坏。

上述未完成项不再作为 `1.0.0` 首发能力。未来重新纳入本地/LAN时必须另开版本化复验任务，不能用本报告直接晋级 `Certified`。
