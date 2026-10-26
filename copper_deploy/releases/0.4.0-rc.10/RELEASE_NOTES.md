# Copper 0.4.0-rc.10 内部镜像清理与离线验收候选

`INTERNAL_REHEARSAL_ONLY`。rc.9 的真实构建、冻结记录和扫描结果保留：其安全扫描通过，但正式离线导出失败，未形成可安装介质。实际 OCI 检查另发现 mysql-shell RPM 删除后留下未归属包的 Python 缓存。本候选必须重新构建三镜像、冻结实际输入、扫描、生成介质并验收；历史扫描结果不替代新摘要的证据。

## 功能变化

在删除未使用的 mysql-shell RPM 后，精确清理镜像内 `/usr/lib/mysqlsh` 与 `/root/.mysqlsh`，并在生产 RUN 链确认两路径不存在。此清理覆盖残留 certifi 字节码和上游生成日志，保留 mysql、mysqldump、mysqlbinlog 的实际工具检查及全部备份恢复入口。不执行按名称模糊匹配的删除，也不改变 MySQL 8.4.10 与已固定 Oracle CA 修复包。

针对 rc.9 实际扫描中 15 条常规 OL9 High，限定更新 openssl、openssl-libs、curl、libcurl、libevent、glib2、libxml2、sqlite-libs、pcre2、pcre2-syntax、libacl 共 11 个包及必要依赖；只启用常规 baseos 源，并以原生 RPM 版本比较强制修复下限。构建前后核对全部 mysql* RPM 身份和 mysqld/mysql/mysqldump 文件摘要不变，保留固定 mysqlbinlog RPM 摘要校验。此举不执行全系统升级、不切换 FIPS 通道。另 5 条 FIPS 跨流匹配须结合本候选实际包与官方通道证据核查，不因本修复自动批准例外或宣布 High 已归零。

修复 Docker 29 按摘要导出时 OCI index 可能不带名称注解的兼容问题：没有精确名称匹配时，只允许请求的 `repository@sha256` 与唯一 descriptor 的 manifest 摘要完全相等；tag 没有该匹配路径，config ID、错误摘要、重复 descriptor、篡改对象和非 amd64 仍拒绝。继续验证 manifest/config 的实际 SHA，不以任意首个镜像代替缺失引用。

发布工厂与介质桥接推进 rc.10，拒绝 rc.9 及更早候选输入混用。离线 Docker 清单与 rc.9 已审五个 DEB 逐字节一致，仍须按实际包摘要、独立扫描和权利原件验收。沿用公网 IPv4 引导/IP TLS、持久 ACME、同版本 repair/continue 保客户配置和备份公钥。

## 数据库影响

目标 schema、迁移清单及摘要从实际冻结 Backend 计算。本次镜像清理不新增或修改 Flyway。既有 schema 68/92 FK 的结果仍须在本候选的真实安装、备份恢复链中复验。

## 已知问题

验收机仍为已授权的 Ubuntu 24.04 x86_64 主机 18.142.134.26。每阶段状态以本轮实际产物、源提交和镜像摘要为准；此说明不是构建、封装或安装验收通过回执。摘要引用修复仍须在本候选正式三图离线导出路径复验，不能用复制 rc.9 归档代替。许可证工程材料须按新镜像重新绑定，外部法律/会计/独立安装者/客户见证与浏览器手机步骤沿用既有明确延期，不伪称第三方通过。

## 升级步骤

仅作为 `freshInstallOnly:true`、直接升级来源为空的新装候选。完成真实 HTTPS、经营及两份附件、全量/增量加密备份、旁路 stage/switch、继续经营及 down/up 后，再强制创建新 full 并独立解密、重新 stage 验证新经营数据。未实测来源不放入升级矩阵。

## 停机与回退

按正式停写边界固定数据库、附件和允许配置。保留已切换恢复的原站 rollback 槽，不以旧镜像写入或逆向 SQL 回滚 schema。主机不映射 Backend 8191 或 MySQL 3306；本轮不公开 Release、不建标签、不部署客户生产环境。
