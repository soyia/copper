# M6-02 三仓历史、秘密、客户数据与隐私审计

> 状态：三仓普通 GitHub 引用和历史 Release 入口已清理，三仓 `main` 严格审计为 `PASS`；GitHub 托管历史 PR refs 仍为 `BLOCK`，M6-02 不得关闭
> 审计日期：2026-07-16
> 跟踪 Issue：[`soyia/copper_web#59`](https://github.com/soyia/copper_web/issues/59)
> 适用仓库：`copper_web`、`copper_backend`、`copper_deploy`

本文记录 M6-02 的审计合同、首次脱敏结果和剩余处置门槛。它不是“仓库安全”证明，也不授权公开仓库、客户源码交付或 GitHub Release。普通引用已严格通过，但平台管理的旧 PR 对象仍须清理，严格发布门禁必须继续失败关闭。

## 1. 已落地的审计合同

`scripts/security/audit-repositories.mjs` 和 `config/m6-confidentiality-audit.json` 建立以下机器门槛：

- 扫描当前工作树，包括未跟踪和被 Git 忽略、但不属于依赖/构建缓存目录的文件。
- 扫描 `git rev-list --objects --all` 返回的全部可达对象，覆盖本地分支、远程引用和标签可达的 blob、提交消息及标签消息。
- 单独聚合 Git 作者、提交者和标签签署者身份，报告只保存身份集合指纹和数量。
- 检查私钥、云/代码托管令牌、JWT、URL 凭据、具体凭据赋值、鉴权头、环境凭据文件、备份/导出、附件/上传、日志、截图、身份证、手机号和邮箱等规则。
- 浅克隆、读取失败、超出审计上限的大文件或大对象直接产生 `audit_incomplete` 阻塞项，禁止静默跳过。
- 通过 `--candidate-dir` 扫描待发布的已展开源码/Release 资产目录；未展开的大归档会因无法完整审计而阻塞。

审计报告格式为 `copper-confidentiality-audit/v1`。报告只包含规则编号、类别、严重性、作用域、Git 对象标识、脱敏路径提示、路径 SHA-256、数量和处置状态；不保存原始路径、命中片段、秘密值、个人信息值或它们的单独哈希。报告文件权限为 `0600`。

## 2. 两种门禁语义

### 2.1 PR 新增泄漏门禁

`config/m6-known-confidentiality-findings.json` 记录普通 GitHub 引用的 PR 新增泄漏基线。当前普通引用严格审计为零，因此 baseline 为空，不授予任何豁免：

- 任何未来 PR 命中都属于新增项并阻断。
- 任何路径、内容、对象或规则变化都会生成新 ID 并阻断 PR。
- baseline 不是误报清单、豁免、合规结论或发布许可。
- GitHub 托管的 `refs/pull/*` 不写入 baseline；它们必须通过平台清理和独立严格复核解决。

Web、Backend 和 Deploy 的 PR 工作流均使用完整历史 checkout；Deploy 的正式/候选发布入口也在打包前运行 `--gate strict`。新泄漏、浅克隆和大对象不完整审计会失败；脱敏报告在成功或失败时均作为短期 CI 证据上传。

### 2.2 客户源码与 Release 严格门禁

`--gate strict` 会把新发现和 baseline 中全部既存发现都视为阻塞。Web 镜像发布工作流已在任何构建/推送前执行严格门禁；人工候选也必须运行：

```bash
node scripts/security/audit-repositories.mjs \
  --repo copper_web=/full/clone/copper-web \
  --repo copper_backend=/full/clone/copper_backend \
  --repo copper_deploy=/full/clone/copper_deploy \
  --candidate-dir release_assets=/expanded/release-candidate \
  --config config/m6-confidentiality-audit.json \
  --baseline config/m6-known-confidentiality-findings.json \
  --gate strict \
  --scope all \
  --report /root-only/evidence/m6-confidentiality.json
```

三仓必须是 GitHub 权威源的完整非浅克隆。本命令不读取、写入或同步 Gitee。

## 3. 首次 baseline 与当前权威候选复扫

### 3.1 首次广口径 baseline

扫描绑定以下本地 GitHub `origin/main` 快照：

| 仓库 | 提交 |
| --- | --- |
| `copper_web` | `7832f27316ad4931c548201670b29d91472e851d` |
| `copper_backend` | `3214e4d0688a122bac2f027718aee0eb064ab4a8` |
| `copper_deploy` | `8833f3de459b90ffa7fec2e09f1385944fd16532` |

三个仓库均为非浅克隆；共发现 610 个当前文件和 2,762 个可达 Git 对象，没有因大小而跳过的文件或对象。首次严格结果：

| 口径 | Web | Backend | Deploy | 合计 |
| --- | ---: | ---: | ---: | ---: |
| 当前树规则发现 | 19 | 8 | 8 | 35 |
| 历史规则发现 | 20 | 38 | 13 | 71 |
| 秘密/鉴权启发式候选 | 0 | 20 | 14 | 34 |
| 个人信息候选 | 39 | 26 | 7 | 72 |
| 严格阻塞总数 | 39 | 46 | 21 | 106 |

本轮没有命中私钥头、AWS Access Key、GitHub Token、JWT 或含账号密码 URL 等确定性高的专用规则；34 项“秘密”均来自具体凭据赋值或鉴权头启发式规则，仍必须逐项在受控本地环境复核，不能据此宣称它们是误报。个人信息候选主要分布于公开商品目录快照/清单、测试脱敏样例、历史数据脚本、验收文档及三仓 Git 身份集合；公开可见、测试用途或业务联系人属性都不自动等于可公开再发布。

首次报告仅生成在 root-only/临时证据位置，没有提交命中值或原始路径。baseline 保存首次 106 个精确脱敏 ID，用于阻止后续新增泄漏；它本身不会伪造一个仍存在的 finding。

### 3.2 PR #63 后的三仓单分支候选

Web PR `#63` 移除了未授权宏泰当前树资料。随后使用三个全新、非浅、仅检出 GitHub `origin/main` 的本地候选复扫：

| 仓库 | 提交 |
| --- | --- |
| `copper_web` | `10585dd0b2ec797b8a84cf97548a6e859713376b` |
| `copper_backend` | `3214e4d0688a122bac2f027718aee0eb064ab4a8` |
| `copper_deploy` | `8833f3de459b90ffa7fec2e09f1385944fd16532` |

`new` 门禁为 `PASS`，新增发现为 0；`strict` 门禁仍为 `BLOCK`，共复现 85 项（当前树 16、历史 69；秘密/鉴权启发式 34、个人信息候选 51）。与 106 项首次 baseline 的差异来自已移除的 Web 当前资料及首次扫描包含的非 main 本地引用。

### 3.3 产品负责人授权的 Web 单根历史重置

产品负责人明确允许放弃 Web 旧历史并重新推送。执行前通过 GitHub 权威源盘点：29 条普通远端分支、41 条 GitHub PR head、0 标签、0 Release、0 开放 PR。私有完整回退 bundle 包含 70 个引用，`git bundle verify` 通过，文件权限为 `0600`，SHA-256 为：

`933509f5d32818b3bd2aee902a632b9f393a6a25e2ab9aff64c841b930a072b2`

新 Web 候选只保留一个根提交，源码树与重置前 `main` 一致，并使用 GitHub 公开 noreply 作者身份。宏泰网页、图片和抓取脚本在当前树及新普通 Git 历史中均为 0。三个全新非浅单分支候选复扫结果：

| 口径 | Web | Backend | Deploy | 合计 |
| --- | ---: | ---: | ---: | ---: |
| 当前树规则发现 | 0 | 8 | 8 | 16 |
| 历史规则发现 | 1 | 36 | 13 | 50 |
| 秘密/鉴权启发式候选 | 0 | 20 | 14 | 34 |
| 个人信息候选 | 1 | 24 | 7 | 32 |
| 严格阻塞总数 | 1 | 44 | 21 | 66 |

Web 唯一项是新根提交的 GitHub noreply 身份留档，不是宏泰内容。该 66 项快照是历史审计阶段证据，不再作为普通引用的豁免 baseline。删除普通分支并强制更新 `main` 不能删除 GitHub 平台管理的 `refs/pull/*`、PR 页面和缓存对象；须向 GitHub Support 提交清理请求并复核旧对象不可访问，才能声称平台侧彻底清除。

### 3.4 三仓重置、Release 清理与 PR refs 独立复核

产品负责人随后授权对 Backend、Deploy 执行同等单根历史重置，并确认删除 Deploy 的旧 RC 发布入口。三仓 `main` 的当前提交均使用 `Tian` 与 GitHub noreply 身份；每仓只保留一个普通远端分支 `main`。Deploy 的 5 个 prerelease、22 个资产及 6 个旧标签均已删除，删除前私有回退 bundle 已验证并限制为 `0600`。

使用三个全新、非浅、单分支 GitHub `main` 克隆执行 `--gate strict --scope all`，结果为 `PASS`、0 项阻塞。审计覆盖当前树、完整普通可达历史、提交/标签消息和 Git 身份；Deploy 同时通过其新 PR 门禁与发布前 strict 门禁的真实 CI。

为避免把 GitHub 平台对象误当作已删除，又在隔离克隆中显式抓取 `refs/pull/*/head`：Web 41 个、Backend 20 个、Deploy 54 个，共 115 个历史 PR head。严格审计仍为 `BLOCK` 48 项，全部位于历史 PR refs：Web 18 项（邮箱 17、Git 身份 1）、Backend 26 项（身份证 1、手机号 16、邮箱 3、疑似凭据赋值 5、Git 身份 1）、Deploy 4 项（身份证 1、手机号 1、疑似凭据赋值 1、Git 身份 1）。报告保持 root-only 脱敏状态，不含路径、命中值或个人信息；普通 Git 命令无法删除这些 GitHub 托管 refs。

平台清理请求模板、提交范围和验收标准见 [M6-02 GitHub 平台清理申请](M6_02_GITHUB_PLATFORM_REMEDIATION_REQUEST.md)。在 GitHub 书面确认并复核前，这 48 项不能标记为误报、批准例外或已清理。

## 4. 处置流程

每个 finding ID 必须选择且证明以下一种处置：

1. **确认秘密**：先吊销/轮换并验证旧凭据失效，再从当前树移除；之后形成历史重写影响清单，不能由扫描器自动改史。
2. **客户数据或个人信息**：删除、匿名化或替换为有证据的合成样例，同时检查旧标签、Release、缓存和已下载副本。
3. **授权保留**：记录来源、权利人、处理目的、公开范围、责任人和有效期；涉及公开采集内容时必须与 M6-03/M6-05 权利及法律复核一致。
4. **有证据的误报**：只能按精确 finding ID 写入 `approvedFindings`，并包含决定、责任人、理由和到期日。路径级或规则级永久忽略不被接受。

历史重写前必须另行完成不可变备份、受影响提交/标签/Release/克隆清单、凭据轮换、协作者切换、旧引用销毁及回退方案。M6-02 工具绝不自动重写历史、删除文件、轮换凭据或更改 Release。

## 5. 尚未完成的退出门槛

- [x] 当前树、完整可达历史、commit/tag 消息和 Git 身份的脱敏扫描器与配置。
- [x] 浅克隆、大对象、读取失败和报告失败的失败关闭行为。
- [x] Web PR 新增泄漏门禁、严格发布门禁及候选资产目录接入合同。
- [x] 首次三仓非浅克隆扫描、PR #63 后 85 项复扫，以及 Web 单根历史候选 66 项复扫摘要。
- [x] 宏泰网页、图片和抓取脚本从 Web 当前树及新普通 Git 历史移除；改史前完整私有 bundle 已验证。
- [x] Backend、Deploy 已接入同等 PR/Release 门禁并通过 PR 合入 `main`；三仓普通 `main` 严格扫描为零。
- [x] GitHub 权威盘点已确认三仓每仓只保留 `main`；Deploy 的 6 个标签、5 个 prerelease 和 22 个资产已按产品负责人确认删除。
- [x] 已完成历史 Release 源码包、元数据和 Copper 应用层审计；基础镜像/依赖的许可与 SBOM 结论仍由 M6-03 管理。
- [x] 已提交 GitHub Support case `#4575811`，申请清理平台管理的 115 个 PR refs/缓存；case 仍为 Open，不能把提交申请视为已清理。
- [ ] 收到 GitHub 书面处置结果后，复核 48 个历史对象不可访问。
- [ ] 在 GitHub 平台清理后重新抓取全部 PR refs，取得三仓、普通引用、PR refs、Release 候选和客户源码包均为 `PASS` 的严格证据。

在剩余未勾选项完成前，Issue `#59` 必须保持打开，M6-04/M6-05 不得把 M6-02 写成前置已通过，仓库不得公开，也不得创建客户源码或公开 Release。
