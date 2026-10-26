# M6-03 第三方权利与 SBOM 工程验收报告

> 日期：2026-07-21
>
> 候选版本：`0.4.0-rc.6`（`INTERNAL_REHEARSAL_ONLY`）
>
> 工程结论：`ENGINEERING_PASS`
>
> 法律结论：`REQUIRED`，未替代 M6-05 知识产权律师复核
>
> Issue：[`soyia/copper_deploy#69`](https://github.com/soyia/copper_deploy/issues/69)
> 法律复核：[`soyia/copper_web#60`](https://github.com/soyia/copper_web/issues/60)

## 1. 精确候选边界

本次证据只对应下列冻结输入，不能用于其他提交、镜像或版本：

| 输入 | 精确值 |
| --- | --- |
| Web 源码 | `c1b3dbb4a9aad586629da11c22c2fb52461d8d9c` |
| Backend 源码 | `c6511e62ea6996355b21dfc3e99c02c56a231185` |
| Deploy 源码 | `887a92c258a40a1f8ea333ede5b193ce9b4e74e0` |
| Web amd64 镜像 | `sha256:36bceaebb151bd2d8865556dcedd93d30edca6d6c80611cd12763e75fffacd06` |
| Backend amd64 镜像 | `sha256:859b8a74fff7ed53739aa0b88bd5065cebeaaa534f562e4519a5e654161a4fbd` |
| MySQL amd64 镜像 | `sha256:6d6e55a3f7c5410076768ed98269a75d0684133f71771f75c5aaa2a680aaf5de` |
| 发布清单 SHA-256 | `478002bc3f167a2aba66634d7b52307dd88b7421d737728f4486d1695daa9345` |
| 六角色安全证据 SHA-256 | `cfa09c9e96fb08c22cf73f54e22a97dbff809c851f78474ce796ad9cdb41f2d3` |

三仓均使用独立、干净、精确 HEAD 的浅克隆生成内容清单和源码 SBOM；三张镜像在 Ubuntu 24.04
x86_64 发布工厂中按不可变摘要/本地映射复核。运行中的业务站点没有重启或替换镜像，验收后
`copperctl monitor check` 保持全项正常。

## 2. 实际工程证据

已生成并在发布工厂与 Mac 两端独立离线验证：

- 文件：`copper-rights-0.4.0-rc.6.tar.gz`
- 字节数：`30,611,432`
- SHA-256：`7ae03a0d0f003c3a9807a8df239e75a7d07f4a47619c464652beaf4eb36a4af8`
- 格式：`copper-third-party-rights/v1`
- 工程状态：`ENGINEERING_PASS`
- 法律状态：`REQUIRED`

包内门槛结果：

| 检查项 | 结果 |
| --- | ---: |
| CycloneDX 1.6 SBOM | 6 份 |
| 唯一组件权利记录 | 683 项 |
| 构建、扫描、交付和运行引用 | 18 项 |
| 内容资产 | 3 个文件、3 条逐文件摘要 |
| 被证据引用的许可证/版权材料 | 702 份 |
| 包内受索引文件 | 713 个 |
| 未知权利 | 0 |
| 许可证冲突 | 0 |
| 缺失归属或授权 | 0 |
| 临时例外 | 0 |

许可证材料来自精确 npm、Go、Maven、PyPI 包，上游 GitHub 动作/工具仓库，以及三张精确镜像内的
Ubuntu、Oracle Linux 和 Alpine 包数据库、版权与许可证文件。每个条目均记录来源、权利人、许可证
表达式、全文文件、使用方式、归属/NOTICE 要求、复核角色和复核时间；SBOM 的原始非标准许可证拼写
作为 `LicenseRef-SBOM-*` 透明保留，没有静默改写或用 `UNKNOWN` 通过门槛。

Canonical Pebble 的精确 Go buildinfo 伪版本提交已不在当前公开引用中。工程证据明确保留该限制，并
同时保存其相邻官方 `v1.31.0`、`v1.32.0` 两个标签一致的 GPL-3.0 全文；此项必须连同其余许可证
兼容性一起交给 M6-05 律师复核，不能据此宣传已经取得正式法律结论。

## 3. 内容资产结论

当前三仓内容清单只发现 Web 仓库中的 3 份 Copper 自制 Office 文档。逐文件 SHA-256 已绑定精确
Web 提交，Office 包检查未发现嵌入的第三方图片或字体，工程台账记录为
`LicenseRef-Copper-Proprietary`。宏泰官网快照、外部图片和抓取脚本不在当前候选树中；旧 GitHub
平台 PR refs 是否仍可到达继续由 M6-02 和 GitHub Support case `#4575811` 处理。

## 4. 可复核验证

对保存的 sidecar 执行：

```bash
shasum -a 256 copper-rights-0.4.0-rc.6.tar.gz
scripts/rights-evidence.py verify \
  --bundle copper-rights-0.4.0-rc.6.tar.gz
```

预期输出必须同时包含：

```text
status=ENGINEERING_PASS
legalReview=REQUIRED
sha256=7ae03a0d0f003c3a9807a8df239e75a7d07f4a47619c464652beaf4eb36a4af8
```

任何内容追加、缺失、路径穿越、摘要变化、组件/工具/资产台账不唯一、未知许可证、`BLOCKED` 条目或
未绑定例外都会失败关闭。证据包不含服务器私钥、客户数据、GitHub 凭据或运行秘密。

## 5. 结论与剩余门槛

M6-03 的真实六角色 SBOM、逐组件/工具/内容资产权利台账、完整许可证材料、确定性证据包和双端离线
复核已经完成，工程状态由 `FAIL_CLOSED` 收口为 `ENGINEERING_PASS`。Issue `#69` 的工程工作可以关闭。

但以下事项不属于 M6-03 工程结论，仍保持 `FORMAL_RELEASE_BLOCKED`：

1. M6-05 知识产权律师对许可证兼容、Copyleft 源码提供义务、最终 `LICENSE`/`NOTICE`、双授权、贡献
   和商标治理作出书面结论；
2. M6-02 等待 GitHub Support 完成 48 项平台托管历史 PR refs 清理并复扫为 0；
3. 正式客户源码包或公开发布必须重新确认其版本、提交、镜像和本证据包完全一致。

因此，本报告只允许支持后续内部演练和 M6-05 事实复核，不授权公开仓库、正式客户交付或“已经开源”
宣传。
