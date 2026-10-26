# Copper Web M1 生产镜像

本文记录 M1-02 前端生产镜像的构建和运行契约。正式 Compose、TLS、域名与安装命令由后续 M1-04/M1-06 的 `copper_deploy` 资产统一编排。

## 1. 冻结基线

| 项目 | 冻结值 |
| --- | --- |
| Dockerfile frontend | `docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e` |
| Node 构建器 | `node:22.22.3-alpine3.22@sha256:8d6a8a2f25f8950664594b4c64678909718af0f567c99a85e44b11d0b44e059d` |
| pnpm | `9.15.7`，以 `packageManager` 校验值和 `--frozen-lockfile` 安装 |
| Caddy 运行镜像 | `caddy:2.11.4-alpine@sha256:98eb57d882ccd5213d1688764db10c1ca2c58a1ca3a6717a3411ad798f7a423a`；最终层移除健康检查不需要的 `curl`，继续使用 BusyBox `wget` |
| 运行身份 | 非特权用户 `copper:copper`，默认 UID/GID `10001` |
| 容器端口 | `8080`，M1-04 负责映射宿主机 80/443 |
| API 上游 | `COPPER_BACKEND_URL`，默认 `http://copper-backend:8191`，不得包含 `/api` |
| 上传请求上限 | `12MB`，与 Backend `spring.servlet.multipart.max-request-size` 一致 |

运行镜像只包含 Caddy、Vite 静态产物、Caddy 配置与构建元数据，不包含 Vite 开发服务器、`node_modules`、源码目录、Git 历史或 `.env` 文件；Caddy 使用镜像内显式创建的非特权用户运行。

## 2. 构建

三个构建参数缺一不可。构建时间必须由发布流程显式传入，不能在 Dockerfile 内自动生成，否则同一输入无法复算。

```bash
docker build \
  --build-arg COPPER_VERSION=0.1.0 \
  --build-arg COPPER_REVISION=<完整的40位提交SHA> \
  --build-arg COPPER_BUILD_TIME=<ISO-8601时间> \
  --tag copper-web:0.1.0 .
```

镜像使用 OCI 标签记录产品版本、提交 SHA 和构建时间；`/usr/share/copper/build-info.json` 与 `/usr/share/copper/production-dependencies.json` 记录构建运行时及生产依赖清单。健康接口不返回这些版本信息。

## 3. 路由与安全边界

- 浏览器始终请求同源 `/api`；Caddy 将原始 URI 转发给 Backend，不剥离或重复拼接 `/api`。
- Caddy 不添加 CORS 响应头，也不改写 Backend 的 `Set-Cookie`；生产 Cookie 的 `Secure`、`HttpOnly` 和 `SameSite=Strict` 由 Backend M1-03 统一控制。
- `/assets/*` 是带内容哈希的 Vite 资源，缓存一年并标记 `immutable`；HTML 与 History 回退响应禁止缓存，发布后不会继续引用旧资源。
- 非 `/api` 且不存在的路径回退到 `index.html`，保证 Vue History 路由直接访问和刷新不出现 404。
- `/healthz` 只验证 Caddy 和静态站点存活；`/readyz` 转发 Backend 的最小就绪状态，供 M1-04 Compose 使用。
- Caddy 只把访问日志写到标准输出，不记录请求体、密码或 Cookie 内容。

## 4. 验证门槛

```bash
pnpm install --frozen-lockfile
pnpm test:production
pnpm build
```

容器验收必须在 `local-database` 或后续标准 Ubuntu x86_64 验收机执行，不在开发电脑启动 Docker。至少验证：镜像构建、非 Node 运行时、`/healthz`、`/readyz`、`/api` 原路径、History 刷新、缓存头、12MB 请求门槛、Cookie 同源登录、镜像元数据及重建一致性。

## 5. 2026-07-13 隔离环境验收

本次候选镜像仅在 `local-database` 的临时网络、临时数据库和 `127.0.0.1:18080` 上验收，没有启动或占用正式 `8191`。验收环境使用 Docker 29.5.3、MySQL 8.4.10 和独立的 M1 Backend 镜像，结果如下：

- Caddy 以 UID/GID `10001` 运行，运行镜像中不存在 Node、Vite、前端源码或构建工作区。
- `/healthz` 与 Backend `/readyz` 均通过；`/api` 上下文保持不变，没有出现 `/api/api` 重复路径。
- Vue History 地址可直接访问和刷新，静态资源使用长期不可变缓存，HTML 禁止缓存。
- 同源登录和会话读取链路通过；Cookie 保持 `Secure`、`HttpOnly`、`SameSite=Strict`，访问日志中的 Cookie 已脱敏。
- 真实 13MB multipart 请求返回 HTTP 413；前端网关 12MB 上限与 Backend 请求上限一致。
- 镜像内的版本、完整提交 SHA、构建时间、Node/pnpm/Caddy 版本和生产依赖清单均可读取，健康接口不泄露这些信息。
- 真实浏览器访问 History 地址后正确进入应用登录流程，刷新后页面仍可用且控制台无错误。

`local-database` 为 ARM64，因此这里只证明 ARM64 隔离运行链路。标准 Ubuntu 24.04 AMD64 已由 M1-07 使用不可变镜像完成安装、同源 API、健康和业务闭环验收；中国大陆公网 Chrome/Edge 访问等待域名备案后补验。
