# Copper 铜材经营管理 — 开源源码版

Copper 面向小微铜材经销商，覆盖商品/SKU、采购、入库、库存、销售、出库、应收应付、收付款和可追溯经营记录。当前公开版本为 **0.4.0-rc.11 源码预发布版**，Copper 自有代码采用 **AGPL-3.0-only**；第三方代码保留原许可。

本仓库完整提供三套组件源码、迁移、锁文件、测试和构建脚本；不需要访问原私有仓库。首次构建会从公共软件仓下载依赖。公开仓库不带旧 Git/PR/Actions 历史、本地环境文件或客户运行数据。

```sh
git clone https://github.com/soyia/copper.git
cd copper
python3 scripts/verify-source-release.py
```

| 目录 | 用途 | 构建说明 |
|---|---|---|
| copper_backend/ | Java 17 / Spring Boot 后端、Flyway 迁移 | [后端构建与配置](BUILD_BACKEND.md) |
| copper-web/ | Vue / TypeScript 前端、帮助文档、Caddy 构建输入 | [前端构建与联调](BUILD_WEB.md) |
| copper_deploy/ | 唯一部署/备份/恢复/升级定义及既有内部工具 | [部署源码说明](BUILD_DEPLOY.md) |

运行和经营操作规则见 [老板经营与账务安全 SOP](copper-web/docs/OWNER_OPERATIONS_AND_FINANCE_SOP.md)。不要把业务数据库、附件、备份或恢复私钥提交到源码仓；开发使用自己的隔离空库，后端使用8193。

来源、原提交/tree及打包元数据变更见 [SOURCE_ORIGIN.json](SOURCE_ORIGIN.json) 和 [release/SOURCE_FILE_INDEX.json](release/SOURCE_FILE_INDEX.json)。原始源码中保留的私有 GitHub URL、规划/试运营文档和内部状态是历史资料；新公开源码以本仓根许可证、说明及精确tag为准，不依赖认证克隆旧仓库。组件内部版本不等于产品组合版本。

本次只分发源码，不提供官方 JAR、dist、容器、Docker安装包或一站式安装介质。此前 rc.11 的真实安装/全量增量恢复/切换/连续经营验收属于所记录源码和环境的内部证据；本次公开不声称重新完成二进制验收、跨版本升级、所有设备认证或专业/客户签署。实际本次源码构建验证与已知限制见 [发布说明](RELEASE_NOTES.md)。

修改后提供网络交互服务时，请提供醒目且免费的、对应实际运行修改版的完整源码入口；分发编译产物时另外遵守相应许可证/通知/源码义务。见 [LICENSING.md](LICENSING.md)、[NOTICE](NOTICE) 和 [第三方范围](THIRD_PARTY.md)。本源码版未授予单独商业闭源例外。

[问题反馈](https://github.com/soyia/copper/issues) · [贡献规则](CONTRIBUTING.md) · [安全报告](SECURITY.md) · [支持边界](SUPPORT.md)
