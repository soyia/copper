# 构建 Copper Web 源码

本说明面向公开仓库的源码快照，目录布局为：

```text
copper/
├── copper-web/
├── copper_backend/
└── copper_deploy/
```

本次公开范围是源码；没有在这里发布新容器、安装介质或在线产品。源码快照中的 Web 来自提交 `d497d7958e6148cd31a47248fc36c85f2e0c24ed`，准确来源映射以公开快照的来源清单为准。`copper-web/package.json` 的 `0.4.0-rc.7` 是现有组件版本；产品组合版本和运行版本以相应发布清单为准。

## 工具链与依赖

使用 Node.js 22；现有生产构建声明的准确版本为 `22.22.3`，pnpm 固定为 `9.15.7`。源码构建需要网络下载锁定的 npm 依赖，本说明没有承诺断网安装。

从公开仓库根目录执行：

```sh
cd copper-web
corepack enable
corepack prepare pnpm@9.15.7 --activate
node --version
pnpm --version
pnpm install --frozen-lockfile
pnpm test:production
pnpm build
```

`pnpm --version` 应为 `9.15.7`。保留 `pnpm-lock.yaml`，不要以删除锁文件或运行无关升级来解决安装问题。`pnpm build` 依次执行 TypeScript 检查和 Vite 生产构建，输出位于 `copper-web/dist/`。

请完整保留 `copper-web/docs/help/`：`src/help/manuals.ts` 会按相对路径导入其中的 Markdown 原文。只复制 `src/` 会丢失构建输入。

## 与隔离后端联调

先检查 `8191`、`8193` 和 Web 开发端口 `8076` 的占用情况。`8191` 留给站点拥有者正式运行的接口；调试不得停止、替换或重启该进程。配置后端的隔离数据库和必要环境变量后，在仓库根目录的另一个终端执行：

```sh
cd copper_backend
COPPER_PORT=8193 mvn spring-boot:run
```

再从 `copper-web/` 启动 Vite：

```sh
COPPER_API_TARGET=http://127.0.0.1:8193 pnpm dev --host 127.0.0.1
```

Vite 页面位于 `http://127.0.0.1:8076`。按 BUILD_BACKEND.md 使用 prod/Secure Cookie 时，完整登录联调还需要与允许来源一致的 HTTPS 反向代理；不能据此认为 HTTP 页面已经通过认证业务验收。`/api` 经 Vite 转发到隔离后端 `8193`。未设置 `COPPER_API_TARGET` 时，现有配置会默认转发到 `8191`，因此 Agent 联调必须显式设置该变量。前端不内置数据库、初始用户或客户数据；按后端说明初始化自己的隔离环境。

构建后的本地预览可执行：

```sh
COPPER_API_TARGET=http://127.0.0.1:8193 pnpm preview --host 127.0.0.1
```

预览端口为 `8077`。Vite 开发和预览服务器用于本地验证，不作为生产部署方案。实际生产安装、备份和恢复请参照 `copper_deploy/` 说明及对应版本的真实发布材料。

## 许可证、通知与后续运行版本

本公开版本的 Copper 自有源码按根目录 `LICENSE` 标注的 `AGPL-3.0-only` 提供。直接复制的第三方文件继续保留原许可证，例如 `copper-web/docker/caddy/main.go` 的 Apache-2.0 头部，以及相邻 `LICENSE`、`NOTICE`；根许可证不替换这些原通知。

若修改程序并通过网络向用户提供交互服务，应提供醒目、免费、与实际运行版本一致的完整对应源码入口。构建脚本、锁文件、必要配置说明及实际修改均应可取得；只链接不断变化的 `main` 或旧原版源码不足以识别具体修改版本。AGPL 第 13 条及对应源码定义见 [许可证正文](https://opensource.org/license/agpl-3.0)。

正式分发 `dist/`、容器或离线依赖时，应随实际产物提供适用的许可证、第三方归属及对应源码获取说明。纯源码公开、网络运行和二进制分发的范围须分别记录。源码发布不把既有内部 M6 审批、第三方复核或客户签署改为通过。
