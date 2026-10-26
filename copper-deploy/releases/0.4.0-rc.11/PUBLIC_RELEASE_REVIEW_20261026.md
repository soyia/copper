# Copper 公开开源发布复查 — 2026-10-26

本轮未确认新的业务或备份恢复故障；rc.11 内部工程验收 PASS 保持。用户现已授权“除已记录问题外如无其他问题则公开开源”，本轮核出公开发布专属工作尚未完成，因此没有直接把三个原私有仓设为 public，也没有创建公开 tag 或 Release。律师、会计、真实设备和客户签署仍按用户授权延期；没有重新要求外部专业人员批准。

| 公开发布缺口 | 本轮实时事实与处理路径 |
|---|---|
| 最终公开许可证 | Backend/Web/Deploy 均无根 LICENSE，GitHub license 均为 null。此前 AGPLv3＋商业双授权仅为目标，没有最终授权落盘。已向负责人询问最终采用原定 AGPL-3.0 还是 Apache-2.0；未收到选择前不擅自赋予不可逆公开许可。律师复核继续延期，不能把“无 LICENSE”说成已经授予开源许可。 |
| 原私有仓平台历史 | 从 GitHub 新克隆三仓并实际抓取全部普通分支、标签与 317 个 PR heads 后，strict 扫描 64 个候选（Backend32、Web18、Deploy14）：62 个历史候选，2 个当前路径命中。不是64个已确认真实泄露。两个当前命中已独立证实只是已脱敏合成工程证据/驱动器压缩包的扩展名规则误报；未批准其余62历史候选，未改原审计结果，也未审计全部 Actions 日志。直接原仓改公开会扩大暴露范围；推荐独立空白公开发行仓，只导入干净源码快照，不复制旧历史或 PR refs，不等待 Support 才能准备新快照。 |
| 公开说明与发行入口 | 当前 Backend/Web 无公开 README；没有最终主许可/NOTICE和统一公开贡献入口。既有source-rehearsal、安装器与最终介质仍为内部格式，不能改名上传为正式公开安装版。公开源码应给出以公开快照相对路径构建的说明，不能依赖私有 GitHub SHA 的认证 clone。若发二进制，还需独立公开格式与对应源码/NOTICE提供方式。 |

准备工作已经完成：从 Backend ECD、Web d497、Deploy e791 的精确 HEAD tree 导出全部 **1062** 个 tracked 文件，文件字节、权限、来源 commit/tree 和 SHA256 闭合集合已逐项复核。没有带入 `.git`、旧平台引用、ignored 本地环境或客户运行目录；保留四份历史 govulncheck 测试原件，避免 export-ignore 漏掉测试输入。

待确认源码预览包为 `/private/tmp/copper-public-release-review-20261026/UNPUBLISHED_SOURCE_PREVIEW_RC11.tar.gz`，3,408,815 字节，SHA256 `1ea90cf17e8db7de243c81d85eea68b4ef676f46692b122a495540086cde0dcb`；状态明确 `UNPUBLISHED_PENDING_LICENSE_DECISION`，没有公开许可授予或匿名构建通过的声明。该草稿不替代之后补 LICENSE、README/NOTICE 和最终公开快照的审计。新公开 commit、许可证 commit 与已验收运行来源必须分别记录：应用 ECD/d497、Deploy runtime f74、metadata freeze10b、run50e；不能宣称新的许可/文档提交本身已被旧镜像构建。

更正：已实际 SSH 只读核验冻结介质 OPERATOR_VALUES 的键，**没有预填站点、域名或备份公钥**；合成安装答案位于介质之外。之前“介质预填验收站点/公钥”的中间说明已撤回。镜像构建来源中的 loopback reference 也不等于离线运行需要该临时 registry；离线运行已按实际镜像 ID 验收。真正公开安装版的差距是内部格式与分发材料/对应源码义务。

两个 current 路径误报以精确 finding ID 和压缩包 SHA 单独记录；原 strict 报告仍 BLOCK，不把历史候选一起改成通过。客户数据与真实密钥没有因此获得公开授权。原rc.11冻结介质未改、三个源仓代码未改、原验收站点未重启。

[机器可读回执](PUBLIC_RELEASE_REVIEW_20261026.json)及[实时脱敏全引用报告](public-release-review-20261026/platform-all-refs-audit.json)可复核；[精确技术说明](public-release-review-20261026/exact-technical-dispositions.json)仅覆盖两个当前合成工程归档。

依据：GitHub说明[无许可证不等于授予开源使用权](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)，以及[仓库公开会公开包括Actions历史/日志等范围](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)。
