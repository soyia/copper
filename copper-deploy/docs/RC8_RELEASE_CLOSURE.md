# rc.8 构建、介质与安装恢复收口

产品负责人已于 2026-10-25 授权 `18.142.134.26` 作为本轮验收机。主机授权不等于构建或验收通过；逐阶段真实状态以本轮实际产物、精确三仓提交、摘要和 checkpoint/验收报告为准，本页不固化执行结果。rc.7 原始冻结和历史 NO-GO 记录保持不变。本次仅授权内部工程收口，不创建公开 Release、标签或客户生产部署。第三方和浏览器/手机延期范围沿用产品负责人 2026-10-16 指令；逐组件许可证/NOTICE 工程材料仍需完成。

## 1. 最短路径与输入

本轮目标是授权的 Ubuntu Server 24.04 LTS x86_64 专用验收机，Docker Engine 29.x（containerd image store）、Compose Plugin 5.x；本机系统 Docker Unix socket。主机需满足至少 2 CPU、4 GiB 内存、20 GiB 可用空间，镜像构建和扫描另留足空间。需要 git、Python 3、Node、tar/gzip、sha256sum、ss、Docker buildx；安装恢复阶段还需真实测试域名/TLS 及现有介质所要求的工具。工厂不能有宿主机 8191 监听；脚本拒绝 Mac、ARM、远端 Docker context、Docker/BuildKit endpoint 环境覆盖，不启动/修改 Docker daemon。

在新目录从 GitHub 克隆三仓并获取完整历史，由开发方核对三个 main 后记录完整 SHA。Web 的上下文修复已合入 2a5febfec7eac00dc576ef16c8324b549fac7c0b；这只是准备时记录，运行时必须重新核对冻结。Backend 与 Deploy 若有新的 OCR/收口修改，须先合入、快进同步并测试再冻结。脚本从精确 git archive 构建，不打包开发目录的 .idea、node_modules 或未提交文件。

另需实际下载并审核的 ACME tar.gz（名称和 SHA-256），以及真实不可变 registry、BuildKit 工具镜像摘要。不得填写示例摘要；可从工具镜像官方发布和实际 docker pull/inspect 核对得到，逐项纳入后续权利台账。构建与扫描允许联网拉取锁定依赖；业务恢复使用 synthetic 测试数据和临时密钥。

## 2. 第一阶段：生成真实输入

以下变量由执行者填入已核实的路径/身份；这些是输入名称，不是可用的镜像摘要或已完成证据：

```bash
python3 "$DEPLOY/scripts/close-release-candidate.py" build-inputs \
  --web-repo "$WEB" --web-commit "$WEB_SHA" \
  --backend-repo "$BACKEND" --backend-commit "$BACKEND_SHA" \
  --deploy-repo "$DEPLOY" --deploy-commit "$DEPLOY_SHA" \
  --registry-image "$REGISTRY_PINNED_IMAGE" --buildkit-image "$BUILDKIT_PINNED_IMAGE" \
  --acme-archive "$ACME_ARCHIVE" --acme-sha256 "$ACME_SHA256" \
  --output "$WORK/rc8-inputs"
```

只在真实构建、push 到临时 loopback registry、归档校验、重新 load 并核对实际导入 ID、清场全部成功后生成输出。Docker 29 producer config ID 可以不同于导入 manifest ID；本地 provenance imageId 必须等于实际重新导入 ID，producerImageId 仅作旁证。三个镜像均来自实际源码，不伪造 MySQL 或 Actions Run。临时 registry 只用于获得可核对的不可变引用，两个阶段结束均删除；离线 READY 按归档实际 ID 运行，客户 Compose 不包含 registry。

输出 `BUILD_INPUTS.json` 绑定实际三个源码提交、镜像引用、producer/import ID、归档摘要、provenance 和锁定工具；`INPUT_CHECKSUMS.json` 是排除自身的全目录闭集摘要。只有此阶段执行成功才出现 components.env。该清单验证级别暂记 L1，不能据此声称安装恢复已通过。未实测的直接升级来源保持空数组，并显式声明 `freshInstallOnly:true`；只有 RC 候选允许此全新安装模式。稳定版或未声明模式的空来源矩阵仍失败，`find-source` 不放行任何升级。

中断后先检查持久的 `closure-state-<runId>.json`；在同一授权主机执行 `cleanup --state PATH`。清场只删除匹配 runId 的 registry、含 driver ownership marker 的专用 builder 和带 ownership label 的本次镜像，拒绝未知归属；不运行全局 prune。先清理中断资源，再用新的输出目录重跑，不覆盖已成功产物。已下载的公共基础/工具镜像保留为缓存。

## 3. 第二阶段：开发方核对并冻结输入，再封装

```bash
python3 "$DEPLOY/scripts/close-release-candidate.py" stage-inputs --inputs "$WORK/rc8-inputs"
```

这里只写本候选的小文件并拒绝覆盖不同内容。开发方复核实际映射、提交（含 `[skip ci]`）、按既有授权快进同步 GitHub main，无需再次请求用户逐步确认；不要伪造 origin/main。二阶段要求 Deploy 干净且 HEAD 等于实际同步的 origin/main，第一阶段以来仅允许 rc.8 记录和 release-policy 的变化；任何运行时代码变化须重新构建。复制源码的 Web/Backend 仍需精确相同提交。

```bash
python3 "$DEPLOY/scripts/close-release-candidate.py" package \
  --inputs "$WORK/rc8-inputs" --web-repo "$WEB" --backend-repo "$BACKEND" \
  --output "$WORK/rc8-package"
```

此阶段恢复同一 loopback 地址的临时 registry，重新导入归档、实际 push/inspect 校对摘要和 ID，调用现有 `build-secure-release.sh --candidate` 与 `build-release-delivery.sh offline`，生成当前源码保密扫描、六角色 CycloneDX SBOM、Grype 和 Gitleaks 证据及离线交付闭集。扫描问题正常失败关闭，不用延期第三方的指令豁免工程失败；不调用 Actions、不推 GHCR。旧源码和镜像不可代用。清场成功后才原子发布 package 目录。

成功目录为 release/、offline/、PACKAGE_STATUS.json、PACKAGE_CLEANUP.json、PACKAGE_CHECKSUMS.json；最后一个是排除自身的全目录闭集 SHA-256 字典。状态仍是 `PACKAGED_INSTALL_RESTORE_ACCEPTANCE_PENDING`。后续使用 `build-guided-medium-from-candidate.py` 将这些真实输入、成功 package、校验过的 Docker 安装 bundle 与 ACME 组装当前 guided 介质；不会拿旧 guided 源介质替代本候选。

## 4. 许可证工程与真实安装恢复

安全包只是许可证工程的输入。对新 manifest/security bundle 执行 `scripts/rights-evidence.py discover` 生成组件工作清单，按 `docs/M6_THIRD_PARTY_RIGHTS_SBOM.md` 再执行 inventory/build/verify。旧 rc.6 的 683 项台账和 702 份正文只能在身份、版本、用途、来源和文本摘要逐项一致时复用；工具没有自动 reconcile 命令。新增/变更组件、镜像、registry/BuildKit 工具及内容资产均须补齐实际许可证全文与 NOTICE，未知结论失败关闭。律师结论继续明确 REQUIRED/owner-deferred，不能伪造律师签字。

完整 guided 介质生成后，在专用空白 Ubuntu amd64 主机按当前入口完成：介质校验→离线安装→真实业务和 API 附件→停写整站加密备份→旁路恢复验证（schema/Flyway、实际 92 FK/零孤儿、表计数、按商品数量/成本、应收应付、过账/凭证、十类附件引用与文件 SHA）→切换→继续真实业务→停止/重建后数据保留。OCR 启用时另验引擎依赖及关闭路径。密钥只能用临时 synthetic 站点密钥并在结束清理；不触碰用户生产 8191。

历史 `run-m3-final-x86-acceptance.sh` 固定旧版本/schema，不能直接作 rc.8 证据；M4 故障脚本需要改系统时钟，未经专用主机授权不能运行。原生 MySQL 恢复的已通过证据不替代以上容器、离线工具链与 copper-backup/v1 演练。每一步记录真实命令、时间、主机、提交、摘要和退出码；出现失败先修复再新建候选或重新冻结，不改写 rc.7。

## 5. 引导安装的公网 IPv4 路径

直接在真实 guided 介质目录执行 `sudo ./copper-install install --media-dir "$MEDIA" --answers "$ANSWERS" --yes`，或使用 Mac 入口。`--answers` 的 `domain` 可填写本机固定公网 IPv4，且必须与 `publicIp` 一致。向导自动选择 `COPPER_TLS_MODE=ip`，核对正式 ip-tls.sh 固定版本/SHA 后将介质 offline/ 下的 ACME 归档持久保存到 `/opt/copper/offline/`；安装和中断继续不依赖临时介质路径中的客户端。公开 HTTP-01 入口与可信 HTTPS、续期 timer 均需分别实测。

同版本 repair/continue 不会静默更换备份公钥，保留客户 OCR、远程访问和备份策略。只接受公网单播 IPv4；IPv6、私网、共享、回环、多播和保留地址拒绝。跨域名/IP 模式或不同 IP 的 change-domain 安全停止，本轮不提供证书迁移事务。备份旁路保持原有 loopback HTTP 验证；同站切换保留现有正式 TLS/ACME 配置，不把短期叶证书当作长期恢复凭证。

Docker 已在线准备时，向导复用兼容环境；本轮可据实际记录证明 Copper 镜像离线 load 和产品安装，不能把该复用当成 Docker 离线安装测试。
