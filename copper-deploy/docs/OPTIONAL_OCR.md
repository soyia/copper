# 可选 OCR 的容器部署

正式 Compose 默认 `COPPER_OCR_ENABLED=false`。Backend 镜像只有 Java 运行时，**没有捆绑 PaddleOCR、Python、模型或 OCR 服务**。OCR 未配置、关闭或识别失败时，人工建档、采购、入库、库存、销售、出库和收付款仍可完整操作。识别只提供待人工确认的候选信息，不自动记库存或资金。

## 启用前提

运维方先自行部署、授权并维护可用的私有 OCR 引擎；服务需兼容 Backend 的 PaddleOCR `/ocr` HTTP 接口，版本、模型、CPU/内存和实际单据识别效果需单独验收。本仓库不负责安装该引擎，也不宣称默认可用。

`backend` 的 `data` 网络是 `internal:true`。仅填写宿主机/私网 URL 不会赋予它网络可达性。可选 `compose.ocr.yaml` 将 Backend 加入一个**预先创建、独立且实际 Internal=true 的 Docker 网络**；MySQL 仍只在 `data` 网络。OCR 服务只能通过该网络的专用别名访问，不发布端口，不复用 Copper 的 data 网络。不要使用 `127.0.0.1`、`host.docker.internal`、`backend` 或 `mysql` 作为 OCR 地址。

在目标 Linux Docker 主机创建网络（名称按本站点单独选择）：

```sh
docker network create --internal copper-private-ocr
```

然后由引擎维护方将**已经部署的 OCR 服务容器**接入该网络并设置 `copper-ocr` 网络别名。该服务须监听容器接口，例如 `0.0.0.0:8080`；只监听容器 `127.0.0.1` 无法被 Backend 访问。服务不应同时桥接公网或数据库网络。模型下载/更新应在维护阶段完成，运行时不依赖互联网。

在本站点受限的 `copper.env` 明确设置：

```dotenv
COPPER_OCR_ENABLED=true
COPPER_OCR_ENDPOINT=http://copper-ocr:8080/ocr
COPPER_OCR_NETWORK=copper-private-ocr
COPPER_OCR_ENGINE=PADDLEOCR_LOCAL
COPPER_OCR_MODEL=PP-OCRv6-medium
COPPER_OCR_TIMEOUT_SECONDS=120
```

超时范围为 5–300 秒；引擎/模型名须与真实服务相符。地址不允许口令、查询参数、片段或回环/公有 IP。当前集成不提供 OCR 认证凭据配置；需要认证或其他网络结构时应先设计并验收私网适配，不能把凭据塞入 URL。

正常使用 `copperctl config`、`copperctl up` / `restart`。不要单独调用 overlay 绕过校验。启动、重启、安装和 doctor 会核对 Docker 实际网络的 Internal 标志，缺失或使用 data 网络会给出可修复错误；`down` / `stop` / 旁路清场不查询 OCR 网络，即使该网络已不存在仍可停止 Copper。外部 OCR 服务和网络由维护方管理，不随 Copper 清场删除。

启用后用一张授权测试单据在应用中识别，检查品牌、单位、数量及候选字段，再人工确认。网络校验通过不等于引擎健康或字段正确，最终 Compose 私网连通及单据回归仍须在标准 Ubuntu 主机验收。恢复人工模式时设 `COPPER_OCR_ENABLED=false`，清空 endpoint/network，并恢复示例中的 engine/model/timeout 后重新 `up`；不会更改单据、库存或金额。

## 备份、恢复与升级

完整和增量备份的运行配置允许清单保留以上 6 个非凭证字段；未知、缺项或含敏感 URL 的配置拒绝归档/恢复。旧备份完全没有 OCR 字段仍受支持。

旁路恢复使用**目标机当前显式配置**，在 stage env 中保留这 6 项；不会用来源包中的 URL/网络名称静默启用 OCR。来源设置保留在备份中供运维核对。新主机默认关闭，若确实需要 OCR，应先准备独立内部网络和服务，再显式启用。缺少网络时启动明确失败，可回到人工模式重试。标准升级保留非镜像配置。OCR 开关不会修改备份中的记录数、业务合计、附件或业务过账结果。

恢复工具依赖同一 Deploy 版本的 `scripts/ocr-config.py`；官方工具介质归档整个已提交 Deploy 树，不能单独复制旧的某个恢复脚本。

## 2026-10-16 验证范围

[脱敏实机证据](OPTIONAL_OCR_ACCEPTANCE_20261016.json)：macOS CPU、独立临时模型 cache、真实 PaddleOCR 引擎和生产 `PaddleOcrClient` 识别授权私有发货单图，返回 68 行并命中两个可见文档锚点。仅保存输入/响应摘要与检查结果，未归档原图、客户原文或识别全文；不声称特定包装标签字段已经通过。原模型 cache 全文件 SHA-256 未变，自有进程、临时 cache/日志均已清理。

这份证据不代表 OCR 已被捆绑进镜像，也不代表标准 Ubuntu Compose 网络或正式备份恢复已实跑通过。Docker 调度/网络错误路径和新旧备份合同由独立 stub/元数据测试覆盖；标准主机的实际网络连通仍是启用前验收项。
