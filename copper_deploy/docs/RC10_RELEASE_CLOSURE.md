# rc.10 镜像清理与重新取证范围

rc.9 已冻结于 `1d4731a2e8375fa182b4b9dea3b648186b8c8156`。真实安全扫描通过，正式离线导出随后失败；两项结果分别保留，不能把扫描通过称为介质已就绪。实际白化后的 OCI 还含 177 条 mysqlsh 相关路径，其中 126 个常规文件共 3045785 字节，主要为未归属 RPM 的 Python 缓存（含 certifi 字节码）和上游日志。RPM/主可执行文件已删除不等于这些路径已清除；没有据此推断漏洞可触达或伪造安全例外。

rc.9 实际 MySQL 扫描另有 20 条 High（原 JSON SHA `0609ca7f97d829808e685fac763765076c611c89a5ad827c87d523ec5f4286bb`），其中 15 条可由 11 个常规 OL9 包补丁处理，5 条涉及 FIPS 跨通道版本匹配。rc.10 限定更新 openssl/openssl-libs、curl/libcurl、libevent、glib2、libxml2、sqlite-libs、pcre2/pcre2-syntax、libacl；只启用 `ol9_baseos_latest`，不全系统升级，不为清零扫描切换 FIPS 流。每包安装值须通过原生 `rpm.vercmp` 修复下限，拒绝 epoch 10 与 `_fips`。所有 mysql* RPM 及 mysqld/mysql/mysqldump 的实际 SHA 前后须完全相同；固定 mysqlbinlog 下载 SHA 与三工具版本检查继续生效。

常规补丁依据为 Oracle ELSA-2026-77396、69126、67910、42089、71585、58936、81532、42736；FIPS 50075/500448/50346 与常规对应 1473/20612 保留各自官方原件、CVE、包 SHA 与通道，不把不同 epoch/通道直接当作可混用的升级目标。5 条 FIPS 匹配仍须逐身份核查并由既有精确证据合同判定；本代码不新增或批准豁免。实际事务、启动/业务/恢复和新漏洞库扫描决定整改结果，本说明不预称 15 条已消除。

本轮在新 `0.4.0-rc.10` 目录取证，保留 rc.7/rc.8/rc.9 的说明、组成清单、镜像摘要、构建输入及实际失败记录。代码只精确清理镜像内 `/usr/lib/mysqlsh`、`/root/.mysqlsh`，随后断言两路径不存在；mysql、mysqldump、mysqlbinlog 的生产检查保持。合同测试重放实际 RUN 链时，所有生产绝对路径均映射到自有临时目录，禁止在开发电脑执行真实根路径删除。

离线导出另修复 Docker 29 的摘要引用兼容：按 `repository@sha256` 保存的 OCI index 可能缺少 `io.containerd.image.name`。仅在无精确名称匹配时，按请求中的 manifest 摘要寻找唯一相同 descriptor，再复验 manifest/config SHA 与 amd64。tag 不回退，config ID、错误摘要、重复 descriptor、篡改对象与错误架构继续失败关闭。原 rc.9 归档的复现验证作为问题与修复旁证保留，不能代替 rc.10 的完整三镜像导出及安装证据。

## 执行与绑定

沿用 [工厂与正式安装恢复流程](RC9_RELEASE_CLOSURE.md) 的两阶段工具，但当前 VERSION、输入/输出目录、release 清单、package 和 guided 介质必须全部为 rc.10。执行者读取三仓实际 GitHub main 完整提交；以授权 Ubuntu 24.04 amd64 主机执行实际构建、临时 loopback registry、归档/load/import ID 核对及清场。不运行本机 Docker 或 GitHub Actions。

```bash
python3 "$DEPLOY/scripts/close-release-candidate.py" build-inputs \
  --web-repo "$WEB" --web-commit "$WEB_SHA" \
  --backend-repo "$BACKEND" --backend-commit "$BACKEND_SHA" \
  --deploy-repo "$DEPLOY" --deploy-commit "$DEPLOY_SHA" \
  --registry-image "$REGISTRY_PINNED_IMAGE" --buildkit-image "$BUILDKIT_PINNED_IMAGE" \
  --acme-archive "$ACME_ARCHIVE" --acme-sha256 "$ACME_SHA256" \
  --output "$WORK/rc10-inputs"
python3 "$DEPLOY/scripts/close-release-candidate.py" stage-inputs --inputs "$WORK/rc10-inputs"
```

这些变量须取实际核实值，不能直接复制占位符。开发方核对真实输出并按既有授权提交、快进同步后，再运行 `package --inputs "$WORK/rc10-inputs" --web-repo "$WEB" --backend-repo "$BACKEND" --output "$WORK/rc10-package"`。如确有经过核验的不受影响证据，可显式传本候选的 `--security-exceptions FILE`；仍沿用精确组件身份、证据 SHA、期限和既有 Critical 门槛，不生成默认豁免。介质桥接默认使用 `releases/0.4.0-rc.10/docker-ubuntu-amd64.env`，其字节与 rc.9 已审清单相同；工具独立漏洞报告与授权范围如实保留。

实际新 OCI 必须证明上述两目录及 certifi/mysqlsh 残留均不存在、三个正式工具仍存在；对完整归档、config/source label、逐层白化语义和实际 RPM 身份取证。新图所有 OS/JRE/第三方原件重新绑定；只有包身份、版本、固定源码与材料 SHA 都一致的旧缓存可复用。安全扫描、正式离线三图导出、guided 介质闭集及清场须分别成功，不能拿 rc.9 字节作为 rc.10 图像输入。

## 安装恢复完成条件

实际阶段状态只由本轮产物/时间/提交/摘要/退出码证明，不写预期 PASS。本轮范围仍为内部工程验收，无公开 Release、标签或客户生产部署授权。第三方与浏览器/手机的既有明确延期不改为虚构签字，逐组件许可证工程不因此省略。

在已授权站点完成真实 IP HTTPS、业务/原附件和审核快照、full v1 真解密、incremental v2 链回放、stage/switch 和继续经营、普通 down/up 数据保留。恢复后强制重新创建 full，独立解密并在新旁路 stage 比较新经营数据；验证后仅 discard 该新旁路，不误删首个 SWITCHED 的原站 rollback 槽。不得使用测试模式绕过正式确认，不占用 8191。
