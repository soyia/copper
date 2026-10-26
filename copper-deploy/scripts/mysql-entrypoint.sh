#!/usr/bin/env bash
set -Eeuo pipefail

upstream=/usr/local/bin/docker-entrypoint.sh
[[ -f "$upstream" && ! -L "$upstream" ]] || {
  echo "错误：MySQL 官方入口脚本不存在、不是普通文件或是符号链接" >&2
  exit 1
}

patched="$(mktemp /tmp/copper-mysql-entrypoint.XXXXXX)"
cleanup() {
  rm -f "$patched"
}
trap cleanup EXIT

# MySQL 官方入口会把 *_FILE 读取出的口令导出，并最终继承给长期运行的
# mysqld。只在唯一的最终 exec 前清除明文变量；初始化和账户创建仍完整
# 使用官方脚本。上游结构变化或出现多个匹配时失败关闭，避免静默失效。
if ! awk '
  /^[[:space:]]*exec "\$@"[[:space:]]*$/ {
    replaced += 1
    if (replaced != 1) exit 91
    print "\tunset MYSQL_PASSWORD MYSQL_ROOT_PASSWORD"
    print
    next
  }
  { print }
  END { if (replaced != 1) exit 92 }
' "$upstream" > "$patched"; then
  echo "错误：MySQL 官方入口结构已变化，拒绝在无法确认秘密清理点时启动" >&2
  exit 1
fi

chmod 0555 "$patched"
exec "$patched" "$@"
