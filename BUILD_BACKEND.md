# 从源码构建后端

本文位于发行仓根目录；后端在 `copper_backend/`，相邻前端和部署源码分别在 `copper-web/`、`copper_deploy/`。这些目录随本源码快照提供，构建后端不需要克隆原私有仓库。

## 构建

使用 JDK 17 和 Maven；既有 Docker 构建基线使用 Maven 3.9.9。本快照没有 Maven Wrapper。首次构建需要访问 Maven Central 下载声明的依赖。

```bash
cd copper_backend
java -version
mvn -version
mvn -B -ntp -DskipTests package
```

产物为 `target/copper-v2-backend-0.1.0-SNAPSHOT.jar`；该 Maven 工件版本不等于整套产品发行版本。此命令编译、打包，不启动后端或连接数据库，也不构成测试通过记录。

## 本机独立启动

先准备**自有、可丢弃的空 MySQL 8.4 数据库**及仅对该数据库有权限的非 root 账号。首次启动会由 Flyway 创建并迁移数据库至当前 schema；不要使用客户库或已有正式库。数据库由使用者自行安装、创建，本源码包不携带数据库或业务数据。

以下是 Bash 中的环境变量示例。将 URL、账号、密码文件和 HTTPS 来源改为自己的值。密码文件须预先创建，内容为单行随机密码（至少 16 字符），仅运行者可读；不要把密码放入命令、源码或 Git。示例的 `useSSL=false` 仅用于明确的本机 loopback 数据库连接；远程数据库应配置经过验证的 TLS 连接。

```bash
# 在 copper_backend/ 中、未加载其他 Copper 站点配置的新终端运行。
umask 077
export SPRING_PROFILES_ACTIVE=prod
export COPPER_PORT=8193
export COPPER_TIME_ZONE=Asia/Shanghai
export COPPER_DB_TIME_ZONE_OFFSET=+08:00
export COPPER_DB_URL='jdbc:mysql://127.0.0.1:3307/copper_source_dev?useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
export COPPER_DB_USERNAME=copper_source_dev
export COPPER_DATA_ROOT="$HOME/.local/share/copper-source-dev"
export COPPER_DB_PASSWORD_FILE="$COPPER_DATA_ROOT/secrets/db-password"
unset COPPER_DB_PASSWORD
export COPPER_FLYWAY_ENABLED=true
export COPPER_FLYWAY_BASELINE_ON_MIGRATE=false
export COPPER_FILE_STORAGE_PATH="$COPPER_DATA_ROOT/files"
export COPPER_EXPORT_PATH="$COPPER_DATA_ROOT/exports"
export COPPER_UPLOAD_TEMP_PATH="$COPPER_DATA_ROOT/tmp"
export COPPER_LOG_FILE="$COPPER_DATA_ROOT/logs/backend.log"
export COPPER_LOG_LEVEL=INFO
export COPPER_STORAGE_CAPACITY_FILE="$COPPER_DATA_ROOT/runtime/storage-capacity.json"
export COPPER_BOOTSTRAP_USERNAME=admin
export COPPER_BOOTSTRAP_CREDENTIAL_FILE="$COPPER_DATA_ROOT/secrets/initial-admin.txt"
export COPPER_ALLOWED_ORIGINS=https://copper.example.com
export COPPER_ALLOW_PRIVATE_NETWORK_ORIGINS=false
export COPPER_COOKIE_SECURE=true
export COPPER_COOKIE_SAME_SITE=Strict
export COPPER_OCR_ENABLED=false
mkdir -p "$COPPER_DATA_ROOT"/{files,exports,tmp,logs,secrets,runtime}
test -r "$COPPER_DB_PASSWORD_FILE"
```

最后一行未成功时，先在指定位置安全配置数据库密码文件，勿直接继续。`COPPER_DATA_ROOT` 应为自己的持久目录，不能是 `/tmp`。`.env.example` 是配置参考，Spring Boot 不会自动加载它；它也不是可以直接 `source` 的 shell 脚本。当前生产校验另外要求上例中的 `COPPER_STORAGE_CAPACITY_FILE`。

先分别检查端口占用（可用 `lsof -nP -iTCP:8191 -sTCP:LISTEN` 和 `lsof -nP -iTCP:8193 -sTCP:LISTEN`）。8193 已被占用时停止并确认原因；不要停止或替换 8191 的正式服务。随后运行：

```bash
COPPER_PORT=8193 java -jar target/copper-v2-backend-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

另一个终端可检查 `curl --fail http://127.0.0.1:8193/api/actuator/health/readiness`。该健康检查不代表完整业务验收。首次管理员凭据写入配置的 `initial-admin.txt`，应私下读取并首次登录后改密；成功改密会销毁该一次性文件，不要提交或公开它。

这里使用 `prod` 的 Secure / SameSite=Strict Cookie。浏览器登录需要配置与 `COPPER_ALLOWED_ORIGINS` 一致的 HTTPS 反向代理；loopback HTTP 仅用于健康检查。正式网络边界及必要配置见 [生产安全说明](copper_backend/docs/M1_PRODUCTION_SECURITY.md)；后端端口和 MySQL 不应直接暴露公网。源码中的 `blank-test` 是仅供独立空库本机调试的非生产配置，不应替代生产安全设置。

## 有界验证

在 `copper_backend/` 中可先运行不依赖真实数据库或 OCR 引擎的单元与契约测试：

```bash
mvn -B -ntp '-Dtest=!*MySqlAcceptanceTest,!PaddleOcrLiveAcceptanceTest' test
```

查看本次 `target/surefire-reports/` 的失败、错误和跳过项。JUnit/Mockito 需要支持其 instrumentation 的 JDK；遇到 JVM attach 限制时保留错误并修正测试运行环境，不应删测试或把跳过算作通过。

需要真实经营回归时，另备全新 `copper_release_acceptance_*` 数据库，私下设置 `COPPER_RELEASE_DB_URL`、`COPPER_RELEASE_DB_USERNAME`、`COPPER_RELEASE_DB_PASSWORD`，在未启用 `prod` profile、未遗留 `COPPER_DB_PASSWORD_FILE` 的独立测试终端执行 `bash tests/run-current-release-mysql.sh`。该脚本会迁移并写入测试数据；不要指向已有经营数据库。独立迁移破坏性测试另见 `tests/run-core-referential-integrity-mysql.sh`，不要与普通启动混用。可选 OCR 实机测试需要单独部署引擎和自己的合成测试图，不属于默认构建要求。

已有后端源码验收记录为 [RC9 安全与适用性记录](copper_backend/docs/RC9_SECURITY_APPLICABILITY_20261025.md)及其引用的 JSON：Java 17 下 227 项单元/契约、98 项隔离真实 MySQL 回归通过。这是所记录源码与环境的既有证据；本构建说明没有重新运行这些测试，也不宣称新构建的 JAR、镜像或任意部署已经验收。
