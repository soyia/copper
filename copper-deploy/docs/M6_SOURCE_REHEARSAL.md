# M6-04 内部源码交付演练

> 状态：`INTERNAL_REHEARSAL_ONLY`
> 跟踪：[`soyia/copper_deploy#70`](https://github.com/soyia/copper_deploy/issues/70)

本文定义 M6-04 的内部演练包。它让团队在正式对外前验证三仓源码快照、版本绑定、校验和空白环境构建链路，但它**不是**客户源码包、公开 Release 或许可证授予。

## 1. 对外状态

每个归档都使用 `copper-source-rehearsal/v1`，并固定写入：

- `purpose=INTERNAL_REHEARSAL_ONLY`；
- `formalRelease=FORMAL_RELEASE_BLOCKED`；
- 阻断项 `M6-02`、`M6-03`、`M6-05`。

因此即使演练通过，也不得把归档交给客户、第三方或 GitHub Release；不得宣传为已开源、已正式交付或已完成 M6-04。M6-02 的 GitHub 平台历史复扫、M6-03 的真实权利包和 M6-05 的知识产权律师复核完成后，才可设计独立的正式客户交付格式。

## 2. 生成与离线校验

在三仓都干净、且 HEAD 与发布清单精确匹配时执行：

```bash
python3 scripts/source-rehearsal.py build \
  --manifest /release/copper-source-rehearsal-<version>.env \
  --web-repo /source/copper_web \
  --backend-repo /source/copper_backend \
  --deploy-repo /source/copper_deploy \
  --output /secure-work/copper-source-rehearsal-<version>.tar.gz

python3 scripts/source-rehearsal.py verify \
  --bundle /secure-work/copper-source-rehearsal-<version>.tar.gz
```

对于既有 M6-03 候选，如果它的旧 `components.env` 未含 `COPPER_DEPLOY_COMMIT`，必须新建同版本的
`copper-source-rehearsal-<version>.env` 来补齐三仓精确绑定；禁止回写或覆盖既有字节冻结候选。

生成器拒绝：脏工作区、提交与清单不一致、子模块或符号链接、普通文件以外的 Git 树条目、`.env`、私钥、典型凭据/备份扩展名、已有输出路径和非 `ubuntu-24.04/amd64` 候选。源码从精确 Git tree 读取，绝不包含 `.git` 历史、本机缓存或未跟踪文件。

归档采用固定 `SOURCE_DATE_EPOCH`（发布清单的 `COPPER_RELEASED_AT`）、排序、uid/gid、权限和 gzip 时间戳。相同三仓提交和发布清单必须生成相同字节；校验器拒绝路径穿越、非普通成员、重复成员、未知文件、摘要/字节数/权限不符和任何不含内部演练声明的归档。

## 3. 包内容

归档仅包含：

- `sources/copper_web/`、`sources/copper_backend/`、`sources/copper_deploy/` 的精确已跟踪源码树；
- `release/` 下的精确发布清单；
- `files.json`：逐文件路径、字节数、SHA-256 与权限；
- `provenance.json`：产品版本、三仓提交/tree、迁移摘要、镜像摘要、平台和正式发布阻断状态；
- `SHA256SUMS` 与 `REHEARSAL-NOTICE.md`。

它不包含客户数据库、备份、附件、导出、真实业务日志、生产密钥、私钥、`.git` 历史、镜像、依赖缓存或客户交接材料。镜像和离线依赖介质仍按 M3 发布合同单独准备；正式客户包需要在 M6-02/M6-03/M6-05 通过后另行冻结。

## 4. 内部空白环境演练顺序

1. 从干净的 `origin/main` 精确冻结新的发布清单和三个提交。
2. 生成并离线校验本归档，记录归档 SHA-256。
3. 在隔离 Ubuntu 24.04 x86_64 环境解包，仅使用归档内源码与经 M3 验证的离线镜像/依赖缓存构建。
4. 完成安装、核心业务、完整导出、加密备份和旁路恢复，并把结果作为内部证据。
5. 不得把第 2 至 4 步产物发送给客户；正式交付前重新执行 M6-02/M6-03/M6-05 与独立 M6-06/M6-07 验收。

M6-06 的认证 GitHub 新目录克隆演练、与匿名公开验收的边界和失败关闭规则见 [M6 GitHub 全新克隆内部演练](M6_GITHUB_SOURCE_REHEARSAL.md)。
