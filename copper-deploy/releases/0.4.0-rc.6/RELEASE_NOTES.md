# Copper 0.4.0-rc.6 M6-03 权利与 SBOM 内部候选说明

> `INTERNAL_REHEARSAL_ONLY`：本候选只用于冻结当前三仓、生成六角色 SBOM、第三方权利台账、许可证文本和离线权利证据包。它不是 Git tag、GitHub Release、客户交付物或公开发布。

## 精确候选范围

- Web 与 Backend 使用本候选专用 GitHub Actions 运行产生的私有 GHCR amd64 不可变镜像；Actions 大型中间产物不保留。
- Deploy 提交由发布清单在合并后的 `main` 上绑定，三仓必须是干净的独立 checkout。
- MySQL 继续使用已冻结的 8.4.10 amd64 运行镜像；首发支持范围仍为 Ubuntu Server 24.04 LTS x86_64。

## 数据库与业务影响

- 目标 schema 从 7 增至 19，只追加 V8 至 V19；V1 至 V7 摘要保持不变。
- 新增库存调拨、盘点、采购退货、成本衔接、业务过账、确认单据变更、付款冲正、历史业务补录、履约基础数量、总账和税票勾稽基础。
- 升级只能通过保留旁路和已验证备份执行；不得跳过迁移、直接改余额或覆盖既有历史。

## M6-03 工程目标

- 对当前 Web、Backend、Deploy 源码及 Web、Backend、MySQL 运行镜像生成六份 CycloneDX 1.6 SBOM。
- 对组件、基础镜像、构建/扫描工具和内容资产逐项绑定来源、权利人、许可证文本、NOTICE 和工程再发布结论。
- 生成可离线验证的 `copper-rights-0.4.0-rc.6.tar.gz`；任何未知、冲突、缺文本或未覆盖资产均失败关闭。

## 正式发布阻塞

- 工程通过仍保持 `legalReview=REQUIRED`；M6-05 知识产权律师书面复核不能由自动化或产品负责人自批替代。
- M6-02 GitHub 平台历史引用、正式客户双人交接和匿名独立复现继续标记 `FORMAL_RELEASE_BLOCKED`。
- 未取得正式法律结论前，不得创建稳定标签、公开 Release、客户源码包或宣称已经开源。
