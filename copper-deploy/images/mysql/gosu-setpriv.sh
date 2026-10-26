#!/bin/sh
# MySQL's official entrypoint invokes `gosu mysql <command>` when it starts as
# root.  This is a deliberately narrow compatibility wrapper: util-linux's
# setpriv performs the same UID/GID drop without retaining the vulnerable Go
# gosu binary from the upstream image.
set -eu

if [ "$#" -lt 2 ]; then
  echo "usage: gosu USER[:GROUP] COMMAND [ARG...]" >&2
  exit 64
fi

identity="$1"
shift
user="${identity%%:*}"
group="${identity#*:}"
[ "$group" = "$identity" ] && group="$user"

if [ -z "$user" ] || [ -z "$group" ]; then
  echo "gosu identity must include a non-empty user and group" >&2
  exit 64
fi

exec setpriv --reuid="$user" --regid="$group" --init-groups -- "$@"
