# M2 增量备份链内部验收（2026-07-21）

> `INTERNAL_REHEARSAL_ONLY`：本记录证明增量数据库和附件恢复核心机制，不构成公开发布、客户交付或持续 RPO 承诺。

## 验收范围

- `copper-backup/v1` 全量基础包续接 `copper-backup/v2` 增量父链。
- MySQL 8.4.10 `ROW` / `FULL` binary log 的全量边界、增量结束边界和顺序回放。
- 铜材库存数量与成本定点数、附件数据库元数据的增量恢复。
- 附件按路径、字节数和 SHA-256 识别新增/变化，并应用删除墓碑。
- MySQL 运行镜像中 `mysqlbinlog 8.4.10` 的固定来源、双架构 RPM SHA-256 和实际可执行性。
- 运行标识隔离、无宿主端口、无现有数据库连接及测试后零容器/零临时目录残留。

## 环境与隔离

- 主机：`local-database`，仅作为内部隔离 Docker 宿主使用。
- 运行标识：`rehearsal-20260721e`。
- 临时容器：`copper-inc-source-rehearsal-20260721e`、`copper-inc-target-rehearsal-20260721e`。
- 两个容器均未发布宿主机端口，使用随机一次性 root 口令；没有连接或修改现有 `vm-mysql`、`copper_v2`、8191 或正式附件目录。
- MySQL 镜像由当前 `images/mysql/Dockerfile` 临时构建；测试结束后删除临时标签、容器及 `/tmp/copper-*20260721a` 目录。

## 真实写入与恢复

基础截点写入一条库存：

```text
warehouse=10 quantity=20.345000 amount_cost=1424.150000
```

全量边界轮换后执行以下增量业务事实：

- 原库存调整为 `18.345000 / 1284.150000`；
- 新增仓库 11 库存 `2.000000 / 140.000000`；
- 新增两条附件数据库记录；
- 一个签字附件内容发生变化，新增一张发票图片，删除一份过期签字附件；
- 附件恢复端从全量文件开始应用内容差异和删除墓碑。

目标 MySQL 先导入全量逻辑备份，再从记录的位置回放已关闭 binlog。目标端库存和附件元数据逐字段与源端比较，附件恢复树逐文件 SHA-256 与当前源树比较。

## 结果

```text
incremental_mysql_runtime=passed
from=binlog.000004:158 to=binlog.000005:158
```

同时通过：

- `tests/test_backup_chain.py`：附件内容差异、删除墓碑、父链连续性、完整链保留、v2 payload/边界/摘要校验；
- `tests/test_backup_metadata.py`、`tests/test_restore_metadata.py`：v1 兼容和恢复安全合同；
- `tests/test_mysql_runtime_contract.py`：固定 MySQL 8.4.10 客户端 RPM 摘要及 `mysqlbinlog` 镜像合同；
- `tests/static-contract.sh`：binlog 参数、部署和静态安全合同。

验收后复核：临时容器、测试镜像标签和临时目录均不存在。现有 `vm-mysql`、`vm-valkey` 与正式业务进程未停止、未重启、未写入。

## 仍需在正式发布执行的门槛

- PR 检查必须重新构建 amd64 Copper MySQL 镜像，并验证 `mysqlbinlog 8.4.10`。
- 正式部署必须使用新发布的 `ghcr.io/soyia/copper_mysql@sha256:...`，不能沿用不含 `mysqlbinlog` 的旧镜像。
- 安装计划任务前必须先创建并离线验证一份新的 v1 全量基础包；只有之后的每日任务才允许生成 v2 增量。
- 当前 `local-database` 与 Mac IDE 附件目录是分离开发拓扑，不在该拓扑直接安装整站定时器；必须在数据库和附件由同一 `copper_deploy` 站点管理的正式拓扑启用。
