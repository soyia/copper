# M3-04 联网/离线发布物验收

> 验收日期：2026-07-14
>
> 环境：`local-database`，ARM64 隔离补充运行时；Docker Engine 29.5.3、Compose 5.1.4
>
> 结论：`copper-delivery/v1`、联网/离线暂存、失败保护、READY 完整性和主机隔离通过

## 1. 验收命令

```bash
sudo COMPOSE_PROGRESS=quiet ./tests/run-m3-delivery-local-database.sh
```

最终复验耗时 3 秒。脚本使用 `docker import` 创建包含本次随机身份文件的 ARM64 测试镜像，同一镜像仅用于模拟 Web、Backend、MySQL 三个交付角色；正式发布引用仍由测试 `copper-release/v1` 中三个独立的 `registry@sha256` 值约束。该镜像和所有介质、HTTP 服务、READY 目录在退出时删除。

## 2. 通过项

1. 发布方从同一份发布清单生成 online 和 offline 两类 `copper-delivery/v1`；Deploy、升级矩阵、说明、ACME、三张发布镜像及离线镜像归档均由文件名、字节数和 SHA-256 绑定。
2. 联网暂存只通过回环 HTTP 测试服务器下载；错误独立索引摘要在文件落地后立即拒绝，未产生 READY。
3. 联网镜像拉取故障注入在 READY 前停止；没有改变测试外的配置、数据、容器或升级状态。
4. 联网成功后写入 `mode=online` READY；同一索引再次暂存返回 `idempotent=true`。
5. 删除本机测试镜像后，已有 READY 的 `release status` 立即失败，不能把缺失镜像的发布继续报告为可升级。
6. 在无可用代理配置下执行离线暂存；`docker load` 恢复精确本机镜像 ID，过程未执行 `curl` 或 `docker pull`，写入 `mode=offline` READY。
7. 离线同一索引重复暂存幂等；READY 目录权限为 root:root 0700，收据为 root:root 0400。
8. 重新生成一个外层摘要完全正确、但内容不是 Docker 归档的离线介质；`docker load` 失败且没有产生 READY。
9. 41 项 Python 单元测试通过，其中 5 项专门覆盖合法交付、缺失/多余文件、内容篡改、符号链接、路径穿越、错误模式、错误摘要、同版本冲突、权限篡改和正式架构门槛。
10. CLI、备份、M3 版本策略和静态契约全部通过。

最终运行输出：

```text
online_pull_failure_no_ready=passed
online_digest_failure_no_ready=passed
online_stage_and_idempotency=passed
ready_missing_image_detected=passed
offline_no_registry_stage_and_idempotency=passed
offline_import_failure_no_ready=passed
m3_delivery_runtime=passed
runtime_seconds=3
host_8191_unchanged=passed
shared_vm_mysql_unchanged=passed
shared_vm_valkey_unchanged=passed
production_env_unchanged=passed
```

## 3. 主机隔离与清理证据

- 宿主机 8191 测试前后均无监听。
- `vm-mysql` 容器 ID 始终为 `cf8ab3860858acb595ad9852e6a8966b8d64d89d29704091395c216add75dcff`，验收后仍运行。
- `vm-valkey` 容器 ID 始终为 `73bbd1f0275fe0e356c71150156867ded5e070d82e1efca308e02110fa564282`，验收后仍运行。
- 如正式 `/etc/copper/copper.env` 存在，其 SHA-256 测试前后相同。
- 验收后 `/opt` 下 M3-04 测试目录为 0，`copper-m304-delivery-test:*` 镜像为 0。

## 4. 未覆盖边界

本次遵守用户要求，只在 `local-database` 运行容器。ARM64 测试镜像不属于发布物，不证明正式 amd64 镜像的业务可运行性，也没有创建真实 `0.2.0-rc.1` 标签或 GitHub Release。

M3-05 仍需在 Ubuntu 24.04 x86_64 使用正式 READY 介质，验证上一正式版本到目标版本的真实 Flyway 结构变化升级、故障恢复以及目标版本备份到空白新机器的恢复。

## 5. 验收中发现并修复的问题

加入正式目标平台检查后的首次复验发现，测试模式“跳过 Ubuntu 24.04 x86_64”提示写入标准输出，污染了 `release status --json`。平台提示已固定重定向到标准错误；修复后的最终复验中 JSON 可独立解析且全部路径通过。
