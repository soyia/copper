# 项目命名规则

当前公开工程统一使用小写中横线（kebab-case）：

| 用途 | 目录 / 当前组件名 |
|---|---|
| 后端 | copper-backend |
| 前端 | copper-web |
| 部署与运维 | copper-deploy |

目录、cd命令、当前文档链接和构建工具帮助中的实际路径均采用上述名称，不保留旧下划线目录或兼容软链接。产品界面仍称 Copper，业务术语不改。

以下标识具有独立兼容或审计含义，保留原值：私有来源仓URL及repo ID、SOURCE_ORIGIN的来源组件key、SOURCE_FILE_INDEX.component/sourceBlob/originalSha256、已发布tag/既有GHCR镜像路径及摘要/归档/历史证据、审计报告标签、既有v1源码演练归档内部路径、数据库/环境变量/Java-Python标识。它们不是当前文件系统目录，不能为清除下划线而批量改写。

rc.11保持不可变；本次路径修订以新的rc.12源码版本发布。运行时代码、数据库schema、业务契约与生产部署未更改，内嵌既有运行候选和发布策略仍保留各自原版本。
