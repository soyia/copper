# M7-02 零在线支持安装演练手册

> `INTERNAL_REHEARSAL_ONLY`：本手册和配套介质仅用于内部演练，不是正式发布、公开源码包或客户交付物。
>
> `FORMAL_RELEASE_BLOCKED`：M6-02 GitHub 平台历史引用、M6-03 权利与安全证据、M6-05 外部专业复核及既有实体延期项仍须在正式发布前关闭。

本手册面向能够使用 Mac 终端、SSH 和 `sudo` 的经营者。目标是仅依靠一个已校验介质，在全新 Ubuntu 24.04 x86_64 主机完成安装、首次改密、最小主数据、加密备份、客户离线解密、旁路恢复和完整导出。

安装和安装后数据保护已由 [Copper 一站式安装与维护入口](M7_GUIDED_INSTALLER.md) 取代原有的手工生成密钥、复制文件、登录服务器、解压、导入镜像、编辑配置和逐条检查步骤。新的独立样本必须使用同时带 `copper-install-from-mac`、`copper-manage-from-mac` 的 v0.2.0 或更高版本介质；下文第 4-7 节及第 10-12 节旧命令只保留为既有样本的历史，不再作为新样本操作说明。

## 1. 零支持判定规则

开始前可以检查主机、DNS、SSH 和介质是否齐备。产品负责人明确记录 `TIMER_STARTED` 后：

- 只能使用本手册和介质内文件；不得向研发询问新命令、错误解释或下一步。
- Agent 不得登录目标机、修改目标机、远程代操作或提供本手册之外的新排障命令。
- 命令结果与本手册不一致、页面缺少指定入口、必填字段无法理解或步骤无法继续时，立即停止并记录 `SUPPORT_REQUIRED`。
- 不要凭经验绕过失败检查，不要手改数据库、Compose、状态文件或 `/var/lib/copper`。
- 操作者可以保存无秘密的退出码、摘要、状态和截图；不得记录管理员密码、数据库密码、Cookie、私钥或完整业务数据。

发生 `SUPPORT_REQUIRED` 不代表介质损坏。它表示手册或产品仍不足以支持零在线帮助，应结束本次样本，演练外再修复。

## 2. 开始前必须齐备

- 一台全新 Ubuntu Server 24.04 LTS x86_64 主机：至少 2 vCPU、4 GiB 内存、20 GiB 可用本地磁盘。
- 可使用 `sudo` 的 SSH 用户；AWS Ubuntu 默认用户通常是 `ubuntu`。
- 一个已解析到新主机公网 IP 的独立测试域名；不能与正在运行的 Copper 站点共用。
- 云安全组只开放 SSH 来源和公网 TCP 80/443、UDP 443；不得开放 3306、8191、18080。
- Mac 可以使用终端、`ssh` 和 `scp`；若未安装 `age` 且已有 Homebrew，Mac 入口会询问是否自动安装。
- Mac 上准备一处主私钥目录和一处第二副本目录。第二份推荐位于外接盘；同盘副本不能作为正式离线备份证据。
- AWS 等云服务器的 SSH 私钥文件；该文件只在 Mac 上用于连接服务器，不上传。
- 完整介质目录，其根目录至少包含：

```text
START-HERE.md
OPERATOR_VALUES.env
SHA256SUMS
copper-install
copper-install-from-mac
copper-deploy-<commit>.tar.gz
tools/copper-key-offline
images/copper-web-amd64.tar.gz
images/copper-backend-amd64.tar.gz
images/copper-mysql-amd64.tar.gz
```

`OPERATOR_VALUES.env` 只包含版本、来源提交、镜像标签和导入后运行 ID，不包含任何密码或私钥。

## 3. Mac 一键生成密钥、备份、上传并安装

进入介质目录：

```bash
MEDIA_DIR="$HOME/Desktop/copper-m7-guided-installer-v0.2.0"
cd "$MEDIA_DIR"
./copper-install-from-mac
```

只按屏幕提示输入或选择：

- 访问域名、服务器公网 IPv4；
- 站点代号；
- SSH 地址、登录用户和 Mac 上的 SSH 私钥文件位置；
- 本机主恢复私钥目录；
- 第二份恢复私钥目录，推荐外接盘。

脚本自动完成：

1. 校验介质内全部文件，Mac 的 `.DS_Store` 等附加文件不会上传；
2. 检查域名是否只指向填写的公网 IP；
3. 检查 SSH 和 `sudo`；
4. 在 Mac 生成或复用同一套 age 恢复密钥；
5. 复制并逐文件核对第二份私钥副本；
6. 只把 `age1` 公钥写入临时安装答案；
7. 上传闭集介质并在 Ubuntu 上自动运行安装；
8. 安装答案执行后自动从服务器删除。

最终确认会显示：

```text
"INSTALL copper-m7-zero"
```

只输入双引号内的字符，不输入双引号。脚本完成后会显示主私钥和第二份副本的本机路径；禁止把其中的 `copper-age-identity.txt` 发到聊天、工单或服务器。

脚本因 DNS、SSH、证书或服务器环境暂停时，不会覆盖已有密钥。修正屏幕提示后重新运行同一个 Mac 入口，会复用已核对的同一套密钥。若结果与本文不一致，记录 `SUPPORT_REQUIRED`，不得手工拼命令绕过。

安装成功后不要继续抄写本手册中的历史命令。先在服务器运行 `sudo copper-maintain` 完成首次管理员登录、改密和凭据销毁复查；再回到介质目录运行 `./copper-manage-from-mac`，选择“完成数据保护”。后者会自动创建并下载备份、校验侧车、使用本机私钥离线验证、复制到第二位置、上传无秘密回执并启用每日计划。需要恢复演练或从已删除站点的整站备份重建时，仍从同一 Mac 菜单进入；不要手工复制本手册中的历史命令。

v0.4.1 或更高版本的灾难恢复会在空白安装前核对备份与介质的三个程序版本。切换后，操作者只需在浏览器确认账号和关键数据；恢复后的新备份、下载、离线验证、第二位置副本、每日计划、最终监控和恢复回执均由同一入口自动完成。除恢复计划、备份时管理员密码确认和浏览器数据确认外，不应要求操作者拼接命令。若出现额外命令需求，记录 `SUPPORT_REQUIRED`，不要在样本内手工绕过。

## 4. 历史步骤：手工生成密钥和传输（新样本禁止使用）

以下命令仅用于解释首份样本做过什么，不再供新样本执行：

```bash
./tools/copper-key-offline generate ...
scp -r ... ubuntu@目标IP:...
ssh ubuntu@目标IP
```

新样本不得手工复制 `age1`、手工创建答案 JSON、手工切换远程目录或手工运行安装命令；这些动作必须由 `copper-install-from-mac` 完成。

## 5. 历史步骤：安装 Deploy 和 Docker（第二份样本禁止使用）

先加载介质固定值并确认没有示例占位符：

```bash
set -a
. ./OPERATOR_VALUES.env
set +a
printf '%s\n' "$COPPER_RELEASE_VERSION" "$COPPER_DEPLOY_COMMIT" \
  "$COPPER_WEB_IMAGE" "$COPPER_BACKEND_IMAGE" "$COPPER_MYSQL_IMAGE"
```

创建版本目录并解压 Deploy：

```bash
sudo install -d -o root -g root -m 0755 "/opt/copper/releases/$COPPER_RELEASE_VERSION"
sudo tar -xzf "copper-deploy-$COPPER_DEPLOY_COMMIT.tar.gz" \
  -C "/opt/copper/releases/$COPPER_RELEASE_VERSION"
cd "/opt/copper/releases/$COPPER_RELEASE_VERSION"
sudo ./scripts/bootstrap-ubuntu.sh
sudo ./copperctl host preflight --json
```

预检必须满足：`status=PASS`、`writeAllowed=true`、Ubuntu 24.04、x86_64、CPU/内存/磁盘通过，且 80/443 未被其他程序占用。否则记录 `SUPPORT_REQUIRED` 并停止。

## 6. 历史步骤：导入并核对三张离线镜像（第二份样本禁止使用）

返回介质目录并导入：

```bash
cd /home/ubuntu/copper-media
sudo docker load --input images/copper-web-amd64.tar.gz
sudo docker load --input images/copper-backend-amd64.tar.gz
sudo docker load --input images/copper-mysql-amd64.tar.gz
```

核对标签、运行 ID 和架构：

```bash
sudo docker image inspect "$COPPER_WEB_TAG" --format '{{.Id}}|{{.Architecture}}'
sudo docker image inspect "$COPPER_BACKEND_TAG" --format '{{.Id}}|{{.Architecture}}'
sudo docker image inspect "$COPPER_MYSQL_TAG" --format '{{.Id}}|{{.Architecture}}'
printf '%s\n' \
  "$COPPER_WEB_IMAGE|amd64" \
  "$COPPER_BACKEND_IMAGE|amd64" \
  "$COPPER_MYSQL_IMAGE|amd64"
```

前三行必须逐行等于后三行。不要使用 Actions 旧证据中的 OCI config digest，也不要根据 `docker load` 文案猜测 ID。

## 7. 历史步骤：生成配置并完成安装（第二份样本禁止使用）

首次运行用于安全创建目录、配置模板和随机数据库密钥：

```bash
cd "/opt/copper/releases/$COPPER_RELEASE_VERSION"
set +e
sudo ./copperctl install
status=$?
set -e
printf 'FIRST_INSTALL_EXIT=%s\n' "$status"
```

首次必须因真实域名和镜像尚未填写而退出 `3`；密钥和目录已创建、容器尚未启动。然后编辑：

```bash
sudo editor /etc/copper/copper.env
```

只修改以下非秘密字段，值来自本次介质、公钥文件和你准备的域名：

```text
COPPER_SITE_ID=copper-m7-zero
COPPER_RELEASE_VERSION=<OPERATOR_VALUES.env 中的值>
COPPER_DEPLOY_MODE=standard
COPPER_DOMAIN=<本次独立测试域名>
COPPER_TLS_MODE=domain
COPPER_IMAGE_DELIVERY=offline
COPPER_WEB_IMAGE=<OPERATOR_VALUES.env 中的 COPPER_WEB_IMAGE>
COPPER_BACKEND_IMAGE=<OPERATOR_VALUES.env 中的 COPPER_BACKEND_IMAGE>
COPPER_MYSQL_IMAGE=<OPERATOR_VALUES.env 中的 COPPER_MYSQL_IMAGE>
COPPER_BACKUP_AGE_RECIPIENT=<Mac recipient.txt 中的 age1 公钥>
```

不得修改数据库密码、会话密钥或其他随机秘密。保存后只读复核：

```bash
sudo awk -F= '$1 == "COPPER_SITE_ID" || $1 == "COPPER_RELEASE_VERSION" || $1 == "COPPER_DEPLOY_MODE" || $1 == "COPPER_DOMAIN" || $1 == "COPPER_TLS_MODE" || $1 == "COPPER_IMAGE_DELIVERY" || $1 == "COPPER_WEB_IMAGE" || $1 == "COPPER_BACKEND_IMAGE" || $1 == "COPPER_MYSQL_IMAGE" || $1 == "COPPER_BACKUP_AGE_RECIPIENT" {print}' /etc/copper/copper.env
```

确认 10 行正确后完成安装：

```bash
sudo ./copperctl install
sudo copperctl doctor
sudo copperctl status
sudo copperctl monitor check
sudo copperctl host autostart status
```

MySQL、Backend、Web 必须全部 `healthy`，主服务必须 `enabled/active`。首次备份前监控只有“可恢复”因缺少备份和离线回执而 `BLOCKED`，这是预期状态。

## 8. 首次管理员交付

```bash
sudo copperctl admin init
sudo copperctl admin init --show
```

第二条命令只在操作者本人可见的终端执行。不要截图或复制密码。立即访问 `https://本次独立测试域名`，以 `admin` 登录、强制改密、退出并用新密码重新登录。然后：

```bash
sudo copperctl admin init
```

必须显示没有待交付的一次性凭据文件。

## 9. 最小业务与铜排预设验收

在网页按顺序完成：

1. 创建人员 `M7零支持操作员`，再创建登录账号 `m7_zero_operator`；只授予销售和采购角色，首次登录改密后不应看到系统管理。
2. 创建仓库 `M7零支持演练仓`，不要填写真实地址。
3. 创建同一个虚构往来单位 `M7零支持往来单位`，同时启用客户、供应商角色，禁止重复建两份主档。
4. 创建商品 `M7零支持铜排 25×3mm`，选择铜排预设 `HTCJ25-3`。

选择预设后必须自动得到：宽度 `25 mm`、厚度 `3 mm`。长度不属于 `25×3` 的两个尺寸，不得把厚度写成长度。若宽度或厚度仍为空，记录 `SUPPORT_REQUIRED` 并停止。

## 10. 登记公钥、创建备份并在 Mac 离线验证

Mac 计算交接记录摘要；内部演练可使用两个明确不同且带 `NOT-FORMAL` 的演练身份，不能冒充正式客户双人交接：

```bash
OFFLINE_KEY_DIR="$HOME/Desktop/copper-m7-zero-recovery-key"
shasum -a 256 "$OFFLINE_KEY_DIR/handover-record.json"
```

在服务器登记：

```bash
sudo copperctl backup key register \
  --handover-sha256 64位小写SHA256 \
  --customer-confirmed-by 'Tian-INTERNAL-NOT-FORMAL' \
  --witness-confirmed-by 'M7-ZERO-WITNESS-NOT-FORMAL'
sudo copperctl backup key audit
sudo copperctl backup create
sudo copperctl backup status
```

从状态中取得完整原文件名。在服务器建立仅当前 SSH 用户可读的临时传输目录，把 `.copperbak` 和同名 `.sha256` 放入其中：

```bash
sudo install -d -o ubuntu -g ubuntu -m 0700 /home/ubuntu/copper-transfer
sudo install -o ubuntu -g ubuntu -m 0600 \
  /var/backups/copper/完整原文件名.copperbak \
  /home/ubuntu/copper-transfer/完整原文件名.copperbak
sudo install -o ubuntu -g ubuntu -m 0600 \
  /var/backups/copper/完整原文件名.copperbak.sha256 \
  /home/ubuntu/copper-transfer/完整原文件名.copperbak.sha256
```

回到 Mac，把两者一起复制到离线密钥目录，禁止改名：

```bash
OFFLINE_KEY_DIR="$HOME/Desktop/copper-m7-zero-recovery-key"
scp -i ~/.ssh/你的密钥 \
  ubuntu@目标IP:/home/ubuntu/copper-transfer/完整原文件名.copperbak \
  "$OFFLINE_KEY_DIR/"
scp -i ~/.ssh/你的密钥 \
  ubuntu@目标IP:/home/ubuntu/copper-transfer/完整原文件名.copperbak.sha256 \
  "$OFFLINE_KEY_DIR/"
```

下载完成后在服务器删除临时副本：

```bash
rm -f /home/ubuntu/copper-transfer/完整原文件名.copperbak \
  /home/ubuntu/copper-transfer/完整原文件名.copperbak.sha256
rmdir /home/ubuntu/copper-transfer
```

Mac 离线验证：

```bash
MEDIA_DIR="$HOME/Desktop/copper-m7-zero-support-0.4.0-rc.3"
"$MEDIA_DIR/tools/copper-key-offline" verify-backup \
  --identity "$OFFLINE_KEY_DIR/copper-age-identity.txt" \
  --archive "$OFFLINE_KEY_DIR/完整原文件名.copperbak" \
  --receipt "$OFFLINE_KEY_DIR/backup-verification-receipt.json"
```

只把回执传回服务器：

```bash
scp -i ~/.ssh/你的密钥 \
  "$OFFLINE_KEY_DIR/backup-verification-receipt.json" \
  ubuntu@目标IP:/home/ubuntu/backup-verification-receipt.json
```

在服务器登记并删除传输副本：

```bash
sudo copperctl backup key record-restore-test /home/ubuntu/backup-verification-receipt.json
rm -f /home/ubuntu/backup-verification-receipt.json
sudo copperctl backup key audit
sudo copperctl backup key status
sudo copperctl monitor check
```

四层监控此时应全部 `NORMAL`。

## 11. 旁路恢复与私钥销毁

在 Mac 确认服务器 `/run` 是 tmpfs，然后仅通过 SSH 标准输入把私钥临时写入 `/run`：

```bash
OFFLINE_KEY_DIR="$HOME/Desktop/copper-m7-zero-recovery-key"
test "$(ssh -i ~/.ssh/你的密钥 ubuntu@目标IP 'findmnt -n -o FSTYPE /run')" = "tmpfs"
ssh -T -i ~/.ssh/你的密钥 ubuntu@目标IP \
  'sudo tee /run/copper-restore-identity.txt >/dev/null && sudo chown root:root /run/copper-restore-identity.txt && sudo chmod 0400 /run/copper-restore-identity.txt' \
  < "$OFFLINE_KEY_DIR/copper-age-identity.txt"
ssh -i ~/.ssh/你的密钥 ubuntu@目标IP \
  'sudo age-keygen -y /run/copper-restore-identity.txt'
```

输出公钥必须与 `recipient.txt` 完全一致。在服务器执行：

```bash
cleanup_identity() { sudo rm -f /run/copper-restore-identity.txt; }
trap cleanup_identity EXIT HUP INT TERM
status=0
sudo copperctl backup restore stage \
  /var/backups/copper/完整原文件名.copperbak \
  --identity /run/copper-restore-identity.txt \
  --restore-id m7-zero-restore-a --port 18080 || status=$?
cleanup_identity
sudo test ! -e /run/copper-restore-identity.txt && echo VOLATILE_IDENTITY_DESTROYED
trap - EXIT HUP INT TERM
printf 'RESTORE_EXIT=%s\n' "$status"
```

退出码必须为 `0`。核对：

```bash
sudo copperctl backup restore status m7-zero-restore-a
curl --fail http://127.0.0.1:18080/readyz
sudo ss -lntp | grep 18080
sudo test ! -e /run/copper-restore-identity.txt && echo VOLATILE_IDENTITY_ABSENT
sudo copperctl monitor check
```

状态必须为 `VALIDATED`，18080 只能监听 `127.0.0.1`，正式站点保持 `NORMAL`。本次只验证、不切换，随后执行：

```bash
sudo copperctl backup restore discard m7-zero-restore-a
```

按提示输入 `DISCARD m7-zero-restore-a`。

## 12. 完整导出与自动备份

管理员进入“完整数据导出”，创建完整导出任务，等待完成后下载 ZIP 到 Mac。页面任务 SHA-256、下载响应摘要和 Mac 实算值必须一致：

```bash
shasum -a 256 /实际下载路径/copper-export-*.zip
unzip -t /实际下载路径/copper-export-*.zip
```

ZIP 校验必须通过，CSV 可用 UTF-8 打开，不能出现密码、会话、数据库密钥或私钥。最后启用并复核自动备份：

```bash
sudo copperctl backup schedule install
sudo copperctl backup schedule status
sudo copperctl backup status
sudo copperctl doctor
sudo copperctl monitor check
```

## 13. 结束记录

全部完成后记录 `TIMER_STOPPED`，至少保存以下无秘密证据：

- 总墙钟时间；是否发生 `SUPPORT_REQUIRED`。
- Deploy、Web、Backend、MySQL 来源提交和三个运行 ID。
- 主机预检结论、安装健康、首次凭据销毁。
- 普通用户无系统管理、铜排预设自动填入 `25 × 3`。
- 备份 ID、外层 SHA-256、离线回执 SHA-256、旁路恢复状态和销毁结果。
- 完整导出 SHA-256、备份计时器和最终四层监控。

只有全程没有新增命令、研发登录、代操作和实时排障，才能记为 `ZERO_LIVE_SUPPORT_SAMPLE_PASSED`。否则工程流程可以通过，但自助结论必须是 `SUPPORT_REQUIRED`。
