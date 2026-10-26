# rc.9 安全修复依据与实际复验

rc.8 三镜像真实构建、导入及清场通过，但 package 的当前扫描因 19 个 Critical 失败。该来源冻结在 `aa119e9e947a96b81912eca103045947a95503f6`；其记录和 inputs 保留，不改写、不复用为 rc.9 的通过证据。

## MySQL 运行镜像

- [Oracle ELSA-2026-88459](https://linux.oracle.com/errata/ELSA-2026-88459.html) 于 2026-10-23 发布 Critical CA 更新，涉及 CVE-2026-114406。固定官方 `ca-certificates-2026.2.92_v9.0.319-90.3.el9_8.noarch.rpm`，SHA256 为 `c69abcbaa031fddd4819ee451738ab28dfc4aae27c3f32d42e18c5bf61ad93ce`。开发阶段从 [Oracle 官方仓库](https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/ca-certificates-2026.2.92_v9.0.319-90.3.el9_8.noarch.rpm) 下载 994716 字节，实际摘要与公告完全一致。构建中还须再次校验摘要及安装版本，不能只更新文档或扫描例外。
- [certifi 原维护方公告 GHSA-6q48-6c7w-9gw2](https://github.com/certifi/python-certifi/security/advisories/GHSA-6q48-6c7w-9gw2) 的修复版本为 2026.10.19。原 MySQL 镜像的独立 mysql-shell 内置旧 certifi；Copper 的安装、查询、整站 dump 和 binlog 恢复均使用 mysql/mysqldump/mysqlbinlog，没有 mysqlsh 调用。精确删除 mysql-shell RPM，不按模糊路径删除、不清除其依赖或升级整个发行版。镜像内断言该包/命令消失，且三个正式数据库工具仍可运行。正式 API/备份恢复继续走原入口。

## 新候选证据

工厂和介质桥接仅接受 rc.9；rc.8 输入即便重新计算闭集摘要，也不能被重标成新候选。新候选目录为 `releases/0.4.0-rc.9`，输出目录也必须全新。三仓源修复后重新构建、重扫当前漏洞库、生成六份 SBOM、核对许可证全文和 NOTICE，再生成介质并进行真实安装恢复。安全门槛没有变化；源码合同测试不等同于运行镜像或漏洞扫描通过。
