# Copper 0.4.0-rc.11 — AGPL public source prerelease

发布日期：2026-10-26。当前发布为开发者可构建的源码版，原始 Copper 代码正式采用 AGPL-3.0-only。三套组件完整源文件、迁移、锁文件、测试及构建脚本均在本仓，不依赖旧私有仓的访问权限；历史私有 Git/PR/Actions 对象没有导入。第三方 Caddy 入口及其 Apache 通知保留原样，没有被改授 AGPL。

本轮新源码验证：Java17 后端227项单元/契约测试及JAR打包通过；Web176项测试、TypeScript及Vite构建通过；Deploy386项Python测试、static/M3合同通过。所有运行时代码保持来源字节。为保留自动源码归档里的历史测试输入，只修改了 copper_deploy/.gitattributes 的打包属性。验证细节和实际工具版本见 release/VALIDATION.json。最初的沙盒instrumentation/随机loopback限制和缺少离线缓存均保留诊断，已在不改源代码的环境重试后通过。

原已验收功能修复保留：停用往来单位/身份禁止新增经营过账；数量六位与金额两位的口径分离；整站附件引用补齐；生产checksum前导./读取兼容保持严格路径保护；闭合binlog区间的连续性与实际文件集合检查。原Ubuntu24.04 amd64/MySQL8.4实库schema68/92外键、全量＋增量stage/switch、恢复后继续经营、三容器重建和新全量独立解密/恢复已有精确来源的内部实际证据。本次源码公开没有重新签发这些镜像身份或冒充新的二进制验收。

该源码版是RC，未发布官方JAR、Web dist、OCI容器、Docker DEB或一站式安装介质；原内部介质不改名上传。引用的npm/Maven/Go依赖由使用者构建时另行下载，所获依赖及日后编译产物仍须遵守其各自许可/源码/通知要求，见THIRD_PARTY.md。当前无直接升级来源、稳定版/LTS或商业SLA承诺。

真实浏览器/承诺手机设备、律师与会计、客户非开发者独立安装、客户持钥见证及商业交接签署按负责人决定延期，不作为本次公开源码版的交付阻挡，不填写通过结论。M6专业意见和已知第三方材料、Docker上游项保留原状态；它们不被说成已经修复或审批完成。运行修改版网络服务时应给实际使用者提供对应源码入口。商业闭源例外未授予。

源码下载请优先使用本Release的 copper-0.4.0-rc.11-source.tar.gz 和独立SHA256SUMS；也可按精确tag匿名克隆本仓。用 scripts/verify-source-release.py 校验源码闭集后，再按根README与BUILD文档构建。匿名克隆、公开资产下载和最终publiccommit将记录在发布后的独立PUBLICATION_RECEIPT.json，不在提交前预写PASS。
