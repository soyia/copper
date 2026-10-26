# 本地构建来源与历史 Actions 兼容

`copper-release/v1` 继续读取历史发布清单及真实 `COPPER_WEB_WORKFLOW_RUN` / `COPPER_BACKEND_WORKFLOW_RUN`。每个角色现在可以选择两种且只能一种来源：

1. 历史 Actions：保留真实正整数 Run ID，不附加本地构建证据。
2. 本地构建：省略该角色的 `WORKFLOW_RUN`，设置 `COPPER_<ROLE>_BUILD_PROVENANCE_FILE` 与 `COPPER_<ROLE>_BUILD_PROVENANCE_SHA256`。证据必须是清单同目录普通文件，不能是符号链接。

本地证据格式 `copper-local-build/v1` 必须包含以下字段：

| 字段 | 内容 |
| --- | --- |
| `format` | `copper-local-build/v1` |
| `role` | `web` 或 `backend` |
| `sourceCommit` | 与发布清单一致的精确 Git SHA |
| `buildId` | 本次构建的唯一非秘密标识，只用字母、数字、点、下划线、短横线 |
| `builtAt` | 实际构建 UTC 时间，`YYYY-MM-DDTHH:MM:SSZ` |
| `platform` | 与清单一致的 `linux/amd64` |
| `image` | 与清单一致的不可变 `registry@sha256:...` |
| `imageId` | 实际镜像内容 `sha256:...` |
| `builder` | 发布工厂或隔离环境的非个人标识 |
| `command` | 已执行的构建命令，不得含口令、Token、私钥、客户数据或本机敏感路径 |
| `tools` | 实际工具名到版本字符串的非空对象 |

发布生成器、交付索引和预加载镜像校验共同验证来源，不接受未绑定文件、摘要不一致、源码或镜像不一致及伪填 Actions。证据文件随发布清单进入交付索引和 root-only `READY` 目录，交付后的篡改仍被拒绝。

只完成源码冻结、测试或 Maven/Vite 构建而尚未构建目标 Linux 镜像时，不能生成“已完成镜像构建”的证据。应在独立候选快照中记录 `IMAGE_BUILD_PENDING`，待实际构建完成后生成新的真实证据。该状态不能通过完整 `copper-release/v1` 镜像发布合同。
