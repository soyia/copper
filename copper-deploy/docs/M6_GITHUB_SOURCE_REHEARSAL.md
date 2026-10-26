# M6-06 GitHub 全新克隆内部演练

> 状态：`INTERNAL_REHEARSAL_ONLY`
>
> 跟踪：[soyia/copper_deploy#71](https://github.com/soyia/copper_deploy/issues/71)
>
> 正式对外状态：`FORMAL_RELEASE_BLOCKED`

本文定义 M6-06 的第一段可执行证据：在**新建目录**中，只从三仓 GitHub `main` 克隆完整核心，并生成可复核的来源报告。它不把认证的私有 GitHub 克隆说成匿名公开克隆，也不替代 M6-04 的 x86 构建、M6-02 的平台历史审计、M6-03 的权利/SBOM 或 M6-05 的律师复核。

## 1. 固定边界

- 权威来源仅为 `github.com/soyia/copper_web`、`github.com/soyia/copper_backend`、`github.com/soyia/copper_deploy` 的 `main`；本工具不读取、写入或同步 Gitee。
- 生产模式固定来源和 `main`，拒绝自定义 URL、额外 remote、Git 子模块、`.gitmodules`、脏工作区和报告中的 GitHub 凭据。
- 演练报告固定写入 `INTERNAL_REHEARSAL_ONLY`、`FORMAL_RELEASE_BLOCKED` 及 `M6-02`、`M6-03`、`M6-05` 三项门槛。
- 当前仓库未公开时，使用开发者已授权的 GitHub SSH/HTTPS 凭据取得源码，只能标记为 `AUTHENTICATED_GITHUB_INTERNAL`；不得写为“匿名”“第三方”或“公开复现”。
- `--test-mode` 只供自动测试使用，允许 `file://` bare repository；真实演练不得带该开关或覆盖来源。

## 2. 内部演练命令

以下目录必须不存在；工具拒绝覆盖已有目录或报告。凭据仅由本机既有 Git 凭据助手或 SSH agent 提供，不会进入命令参数、报告或归档。

```bash
python3 scripts/github-source-rehearsal.py clone \
  --workspace /secure-work/m6-06-github-clone \
  --report /secure-work/m6-06-github-clone.json

python3 scripts/github-source-rehearsal.py verify \
  --workspace /secure-work/m6-06-github-clone \
  --report /secure-work/m6-06-github-clone.json
```

报告记录每仓规范 GitHub 来源、分支、提交和 Git tree。校验会重新确认 origin 唯一、分支为 `main`、无子模块/`.gitmodules`、工作区干净及提交/tree 与报告一致。

2026-07-17（中国标准时间）的首次真实认证演练记录在 [M6_06_GITHUB_SOURCE_REHEARSAL_20260717.json](M6_06_GITHUB_SOURCE_REHEARSAL_20260717.json)：三仓均在新目录克隆并复核通过。该报告原件的 SHA-256 为 `15ccfc809ac0c25e7acf45565f7f2ec0ff03e89f2a6d889e6b48ad424549f775`；它仍只是一份内部演练证据，不是公开发布证明。

## 3. 与 M6-04/M6-07 的衔接

GitHub 克隆只证明来源获取和三仓快照可复核，不证明镜像、依赖、数据库迁移或业务恢复已经可公开复现。

1. 仅当克隆的三个提交与新冻结的发布清单精确匹配时，才可把目录交给 `scripts/source-rehearsal.py` 生成 M6-04 内部源码归档。
2. 归档、正式离线镜像/依赖介质、x86 空白安装、核心业务、导出、加密备份和旁路恢复仍分别按 M6-04/M6-07 验收；提交不匹配必须失败关闭，不得用“接近的 main”冒充发布候选。
3. `AUTHENTICATED_GITHUB_INTERNAL` 演练通过后，仍须在产品负责人明确启动正式对外动作且 M6-02/M6-03/M6-05 全部通过时，从匿名公开环境重新执行；不得复用本报告替代该验收。

## 4. M6-06 正式退出门槛

以下全部满足前，Issue #71 必须保持打开：

- GitHub 可公开访问且分支保护、标签、Release、校验值、Issue/安全/贡献入口按最终治理文件冻结；
- 独立匿名环境仅凭公开资料克隆三仓，无私有子模块、内部制品库、隐藏脚本、维护者凭据或未公开介质依赖；
- 联网和正式离线资料均能锁定依赖构建，并与发布清单、镜像摘要、SBOM、许可证和 NOTICE 核对；
- 空白 x86_64 环境完成安装、迁移、核心业务、完整导出、加密备份和旁路恢复；
- M6-02 平台历史、M6-03 权利/SBOM、M6-05 法律治理及最终秘密/客户数据/受限资产复扫全部严格通过。

因此，当前内部演练的任何 `PASS` 只能说明本工具所列的认证 GitHub 新目录克隆契约通过，不能解除正式对外状态。
