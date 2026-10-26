# M6-03 第三方权利、许可证清单与 SBOM 合同

> 状态：工程合同已实现；当前源码交付/公开候选保持失败关闭
> 跟踪：[`soyia/copper_deploy#69`](https://github.com/soyia/copper_deploy/issues/69)
> 法律复核：[`soyia/copper_web#60`](https://github.com/soyia/copper_web/issues/60)

M6-03 在 M4 已有供应链安全能力之上回答另一个问题：M4 的 CycloneDX SBOM 能说明候选包含什么，
Grype 能说明已知漏洞风险，但二者不能单独证明 Copper 有权把第三方代码、镜像、字体、图标、图片、
文案、商品目录或采集内容交给客户或放入公开仓库。

本合同不创建 Copper 最终 `LICENSE`，不选择 AGPL 或双授权方案，也不构成法律意见。只有 M6-05 的
知识产权律师复核、治理文件和批准结论完成后，才允许将工程 `ENGINEERING_PASS` 提升为正式公开发布
门槛的一部分。

## 1. 复用的 M4 供应链基线

M6-03 不建立第二套 SBOM 生成器，也不增加在线运行依赖。正式输入必须是已经通过
`scripts/security-evidence.py verify` 的 `copper-security/v1`：

- Web、Backend、Deploy 三个精确源码提交的 CycloneDX 1.6 SBOM；
- Web、Backend、MySQL 三张精确 amd64 镜像的 CycloneDX 1.6 SBOM；
- 产品版本、发布清单 SHA-256、三仓提交、三张镜像摘要和 Syft/Grype/Gitleaks 工具镜像摘要；
- 六份 SBOM、漏洞报告、秘密扫描和镜像来源的完整文件集合与 SHA-256。

M6-03 再次验证 M4 证据包，并要求其中的版本、提交和镜像与本次 `copper-release/v1` 完全一致。
旧版本 SBOM、可变标签、开发者工作区或只列直接依赖的手工表格均不能替代该输入。

为避免把 Syft 在镜像层中列出的每个文件误当作独立可再发布软件，工程清单只纳入 CycloneDX 的
`library`、`application`、`operating-system` 等组件记录；`type=file` 仅是文件系统观察记录，不能替代
软件包来源。Copper 自有或第三方内容资产仍由逐文件内容清单单独审计，绝不因排除 `type=file` 而遗漏。

在完整台账尚未形成前，可使用以下命令从**已通过验证**的安全包生成 `REVIEW_REQUIRED` 工作清单。该清单
不会猜测许可证、权利人或可再发布结论，不能用于客户源码包或公开发布：

```bash
scripts/rights-evidence.py discover \
  --manifest /release/copper-release-<version>.env \
  --security-bundle /release/copper-security-<version>.tar.gz \
  --output /review/copper-rights-discovery-<version>.json
```

## 2. 三类权利台账

正式台账使用 `copper-third-party-rights-ledger/v1`，由三个互不替代的分区组成。

### 2.1 直接与传递组件

六份 SBOM 中每个组件必须通过 purl，或在没有 purl 时通过 `type:name@version`，唯一匹配一条
`componentRights`。台账必须记录：

- 组件键和出现的全部 SBOM 角色；
- `direct`、`transitive` 或 `bundled` 关系；
- 来源、权利人、许可证表达式和许可证全文文件；
- Copper 的使用方式、再发布工程状态、归属和 NOTICE 要求；
- 复核人、复核时间和不含秘密的证据摘要。

SBOM 缺少稳定组件身份、版本或组件台账覆盖时直接失败。SBOM 自带的许可证字段只作为交叉检查输入，
不能自动覆盖人工权利台账。

### 2.2 基础镜像、构建工具和扫描工具

`inventory` 从三仓精确提交的 Dockerfile、Compose、包管理清单、安全策略和 GitHub 工作流（含固定
`uses:` 动作）提取实际引用，并额外纳入发布清单的 MySQL 镜像、随发布包交付的 ACME 客户端归档及 M4 的
Syft、Grype、Gitleaks 不可变镜像。每个引用必须由唯一 `toolRights` 覆盖，记录基础镜像、构建镜像、
运行镜像、构建工具、扫描器或交付工具类别及同等权利字段。

本地构建模式还验证发布清单摘要绑定的 Web/Backend `copper-local-build/v1` 文件；其中实际出现的
`tools.buildkitImage` 和 `tools.registryImage` 必须使用不可变摘要并逐项纳入 `toolRights`。
来源文件随权利包一起保留，缺材料、篡改来源或使用可移动工具标签均失败。历史 Actions 来源及
未使用这两类工具镜像的其他本地构建方式保持兼容，不生成虚构引用或 Actions Run。

这可以覆盖不会出现在最终运行镜像中的 Node、pnpm、Maven、JDK、Dockerfile frontend、GitHub Actions
构建动作和 ACME 归档，避免只审计最终镜像而漏掉随客户源码包或发布介质交付的构建链。

### 2.3 字体、图标、图片、文案和采集内容

`copper-content-inventory/v1` 绑定三仓提交，对以下内容逐文件记录路径、分类、字节数和 SHA-256：

- 任意位置的字体、图片/图标、音视频和 Office/PDF 文件；
- `public/`、`assets/`、`content/`、`static/`、`samples/`、`src/assets/`、
  `src/main/resources/static/` 和 `docs/product-catalog/` 中的结构化或文本内容。

每个文件必须唯一匹配一条 `assetRights` 路径前缀。路径前缀只用于让同一权利来源的一组文件共享
复核记录，最终证据仍逐文件保存摘要和 `rightsId`，不能用一个宽泛免责声明隐藏未知文件。

## 3. 失败关闭与例外

三类记录的工程状态只允许：

| 状态 | 结果 |
| --- | --- |
| `ENGINEERING_APPROVED` | 工程台账完整，可进入 M6-05 法律复核 |
| `REVIEW_REQUIRED` | 默认阻断；只有严格绑定且未到期的权利例外可临时通过工程门槛 |
| `BLOCKED` | 始终阻断，例外不得制造不存在的版权或再发布权 |

空许可证、`UNKNOWN`、`NOASSERTION`、`NONE` 或 `UNLICENSED` 均直接失败。缺少权利人、来源、许可证文本、
使用方式、复核责任人或证据摘要同样失败。

权利例外使用 `copper-rights-exceptions/v1`，只接受：

- `WRITTEN_AUTHORIZATION_CONFIRMED`；
- `COPPER_OWNED_CONFIRMED`。

每条例外必须绑定精确 subject type/id、实名或稳定责任角色、具体理由、最长 30 天到期时间、可公开的
证据摘要，以及同目录脱敏证据文件的 SHA-256。过期、摘要不符、未被候选使用或试图覆盖 `BLOCKED`
都会失败。私密授权书正文、合同价格、联系人和签字页不进入公开证据包。

## 4. 生成物

正式入口：

```bash
scripts/build-rights-evidence.sh \
  /release/copper-release-<version>.env \
  /release/copper-security-<version>.tar.gz \
  /source/copper_web /source/copper_backend /source/copper_deploy \
  /review/third-party-rights-ledger.json \
  /review/license-texts \
  /release/copper-rights-<version>.tar.gz \
  /review/rights-exceptions.json
```

例外文件可省略。三个仓库必须干净且 HEAD 精确等于发布清单；任何输出路径已存在时拒绝覆盖。

成功包使用确定性 gzip/tar，并包含：

- `rights-evidence.json`：`copper-third-party-rights/v1` 机器可读主证据；
- `THIRD_PARTY_NOTICES.md`：人可读的第三方许可证与 NOTICE 输入；
- 六份原始 CycloneDX 1.6 SBOM；
- 发布清单、策略、内容清单、权利台账和所有被引用的许可证文本；
- 如使用例外，例外清单和脱敏证据摘要文件；
- 每个内部文件的精确相对路径、字节数和 SHA-256。

离线复核：

```bash
scripts/rights-evidence.py verify \
  --bundle /release/copper-rights-<version>.tar.gz
```

验证器拒绝符号链接/特殊文件、路径穿越、重复文件、未索引文件、缺文件、摘要不符、角色/计数不一致、
缺少六份 SBOM、缺少 NOTICE，以及任何伪造的 `ENGINEERING_PASS`。成功输出仍明确显示
`legalReview=REQUIRED`。

## 5. 当前树内容风险的收口状态

Web PR #63 已合并为 `10585dd0b2ec797b8a84cf97548a6e859713376b`。在该提交的当前树中，
`docs/product-catalog/hongtai-copper/` 以及 `scripts/sync-hongtai-catalog.py` 均已移除；官网 URL 只保留
为 15 类商品范围的业务参考，不再把第三方网页、图片或文案放入当前源码交付候选。因此该项
`BLOCKED` 内容对**当前树**的阻断已解除。

这不等于对旧 Git 对象作出清理或可发布结论。历史对象是否仍可到达、是否进入后续源码包或公开仓库，
继续由 M6-02 历史泄露门禁评估；M6-03 不删除历史、不掩盖旧发现，也不把当前树移除解释为已经取得
第三方再发布授权。

本次只读盘点的三仓提交、文件集合摘要和其他阻断项保存在
[`M6_THIRD_PARTY_RIGHTS_CURRENT_STATUS.json`](M6_THIRD_PARTY_RIGHTS_CURRENT_STATUS.json)，供后续修改后
逐项复核。该状态文件不能替代正式 `copper-rights-<version>.tar.gz`。

若未来需要重新引入任何第三方商品资料，允许的路径只有：

1. 取得能够覆盖精确文件、使用方式、再发布范围和期限的书面授权并由律师复核；
2. 用 Copper 自行创作或取得明确可再发布许可的内容逐项替换；
3. 不纳入公开/客户源码候选，仅在仓库外按内部研究资料的权限和保留规则管理。

15 类商品的**业务支持范围**仍是产品基线；移除第三方照片或网站原文不等于删除商品类别。应保留由
Copper 自行建立的品类名称、结构化规格模型和业务测试，不能继续复制网站图片、排版或大段产品文案。

## 6. M6-03 退出条件

- 当前 M6 候选生成与发布清单精确对应的 M4 六角色 SBOM；
- 六份 SBOM 的每个直接/传递组件都有唯一、完整、可复核的权利台账和许可证文本；
- 所有基础镜像、构建工具和扫描工具引用都有权利台账；
- 三仓全部内容资产逐文件纳入清单并有唯一权利结论；
- 宏泰快照等 `BLOCKED` 内容已取得授权、替换或从候选/必要历史中移除；
- `copper-rights-<version>.tar.gz` 可重复生成、离线验证并随源码交付候选保存；
- 工程证据交给 M6-05 律师复核，不把 `ENGINEERING_PASS` 宣传为“已开源”或法律合规结论。
