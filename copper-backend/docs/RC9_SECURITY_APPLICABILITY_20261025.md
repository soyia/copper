# RC9 后端安全修复与 Spring 适用性证据

本次改动基于 GitHub `main` 的 `a5fb8a52e9ce07fa35a3db527142b4808382488e`。
它提供修复和应用适用性证据；最终发布结论仍须绑定重建镜像的摘要、实际 SBOM 和扫描结果。

## 依赖与镜像修复

- 将 Spring Boot 管理的三个嵌入式 Tomcat 组件统一升级至 `10.1.61`，保持 Java 17 和 Spring Boot `3.5.16`。
  [Apache 官方公告](https://tomcat.apache.org/security-10.html)说明 `10.1.58` 发布投票未通过，其修复在 `10.1.59` 交付；10 月 20 日公告还列出影响至 `10.1.60` 的问题，因此不采用扫描器原先提示的 `10.1.58`。
- 将 Jackson BOM 统一升级至 `2.21.8`。实际报告中的 core/databind High 修复要求至少 `2.21.7`，而 [FasterXML 10 月 20 日公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)另列出影响至 `2.21.7` 的问题，其同系列修复为 `2.21.8`。
- 刷新官方 Temurin `17-jre` 固定多平台摘要至 `sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8`，实际 amd64 配置为 `jdk-17.0.20.1+1`、Ubuntu `26.04`。
  在构建时从该镜像的官方 APT 源升级已安装的 curl/OpenSSL 组件，逐项校验 [Ubuntu curl 公告](https://ubuntu.com/security/CVE-2026-92392)和 [OpenSSL 公告](https://ubuntu.com/security/CVE-2026-84782)所列安全版本下限；软件源未提供补丁时构建失败。实际安装版本须记录到新镜像 SBOM。
- 从最终 JRE 层删除未使用的 `/usr/bin/pebble`。Copper 的显式 `ENTRYPOINT` 直接运行 Java，没有调用该 Go supervisor；实际 RC8 SBOM 将 Go `1.26.3` 和 `golang.org/x/net v0.40.0` 定位到该文件。
  重建后必须再次确认文件消失、SBOM 不再包含该二进制引入的组件，并执行容器健康检查。源码改动本身不构成扫描通过证据。

## 两项 Spring 命中的实际适用条件

保留扫描器的 Critical 原始命中和组件版本 `spring-webmvc:6.2.19`，不降低严重度，也不以风险接受替代修复。
以下属于该应用当前配置的适用性分析，供发布工具作有事实依据的 `FALSE_POSITIVE` 处置；不是对 Spring 组件整体的安全保证。

| 扫描命中 | 官方说明的必要条件 | Copper 当前实现 |
| --- | --- | --- |
| `GHSA-pc63-qcmh-9cmg` / `CVE-2026-47884` | MVC 使用 `XsltView`，并有导致隐式视图名渲染的 `/**` 映射 | 业务控制器均为 `@RestController`；没有 XSLT 视图、解析器或业务通配视图映射 |
| `GHSA-j9f9-w8pj-32f8` / `CVE-2026-47890` | MVC/WebFlux 将视图片段通过 SSE 发给客户端，攻击者能控制流向他人的内容 | 没有 WebFlux 栈、SSE 映射、SSE emitter 或视图片段返回路径 |

官方原始说明：[Spring XsltView 公告](https://spring.io/security/cve-2026-47884/)、[Spring SSE fragments 公告](https://spring.io/security/cve-2026-47890/)。
两个公告将 `7.0.9` 列为 OSS 修复，将 `6.2.20` 列为企业支持修复。此次没有把 Spring 7 / Boot 4 的大版本迁移混入当前发布。
配置或功能改变时必须重新评估，不能沿用本次应用适用性结论。

## 可复核边界

- `config/WebConfig.java` 只配置 CORS 和安全拦截器；这里的 `/**` 是拦截范围，不是控制器视图映射。
- 业务控制器没有引用 `XsltView`、`XsltViewResolver`、`SseEmitter`、`ResponseBodyEmitter`、`FragmentsRendering`、`ModelAndView` 或 MVC `View`。
- `application.yml` 未配置 `spring.mvc.view`，资源目录没有视图模板。
- `RenderingAttackSurfaceMySqlAcceptanceTest` 启动真实应用 WebContext（Mock Servlet 环境，不绑定服务端口），核对实际注册的业务控制器、映射、泛型返回类型、XSLT bean/解析器和 WebFlux classpath。
  Spring Boot 自带的错误处理和静态资源处理不被误算为业务隐式 XSLT 视图。
- 该回归已纳入 `tests/run-current-release-mysql.sh`，由 `COPPER_RELEASE_SECURITY_E2E=true` 启用。
  独立执行时也必须给出隔离数据库和完整运行目录配置，不能对用户数据库运行。

## 验证记录

- Java 17 单元与契约：`227` 项通过，`0` 失败、错误、跳过；显式排除实库和可选 OCR 实机套件。
- 当前实库回归和真实 WebContext：Oracle 签名验证的原生 MySQL `8.4.13`，任务独占 loopback `33068`、全新数据库与独立测试卷；`23` 个套件、`98` 项通过，`0` 失败、错误、跳过。
  实际注册 `29` 个业务控制器、`523` 个处理方法，XSLT / SSE fragment 路径断言通过。文本搜索出现的第 30 个 `@RestController` 前缀是 `@RestControllerAdvice`，不计作路由控制器。
  主机可用空间比例不足 10% 时，首轮实库测试正确触发容量保护；改用任务自有 APFS 稀疏测试卷后完整重跑通过，未修改或 mock 容量保护。
- JUnit、实际测试 classpath/JAR SHA、两份 Spring 公告原件 SHA 和生产源码树摘要记录在 [RC9_SECURITY_EVIDENCE_20261025.json](RC9_SECURITY_EVIDENCE_20261025.json)。测试启动默认 profile；生产源码没有按 profile 切换的视图 bean 或额外模板配置，`prod` 差异是环境参数校验。正式镜像仍须按实际 `prod` 启动验证。
- RC9 容器构建、实际 SBOM、重新扫描和容器业务/恢复验收：由发布主流程另行执行并绑定产物。

本次不修改业务服务、数据库迁移、金额数量口径或 API 契约。
