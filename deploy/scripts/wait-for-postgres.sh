#!/bin/sh
# PostgreSQL 就绪等待脚本
# 从 DATABASE_URL 环境变量解析主机和端口，或通过参数传入
# 用法:
#   DATABASE_URL=postgres://user:pass@host:5432/db ./wait-for-postgres.sh
#   或: ./wait-for-postgres.sh <host> <port> [timeout_seconds]

set -e

# 超时时间（秒），默认 60 秒
TIMEOUT=${3:-60}
START_TIME=$(date +%s)

# 解析 DATABASE_URL 或使用命令行参数
if [ -n "$1" ] && [ -n "$2" ]; then
  PGHOST="$1"
  PGPORT="$2"
elif [ -n "$DATABASE_URL" ]; then
  # 从 postgres://user:pass@host:port/dbname 格式中解析 host 和 port
  # 去除协议前缀
  TMP="${DATABASE_URL#postgres://}"
  TMP="${TMP#postgresql://}"
  # 去除 user:pass@ 部分（如存在）
  case "$TMP" in
    *@*) TMP="${TMP#*@}" ;;
  esac
  # 去除路径部分（如存在）
  TMP="${TMP%%/*}"
  # 分离 host 和 port
  case "$TMP" in
    *:*)
      PGHOST="${TMP%:*}"
      PGPORT="${TMP##*:}"
      ;;
    *)
      PGHOST="$TMP"
      PGPORT="5432"
      ;;
  esac
else
  echo "错误: 未指定数据库地址，请设置 DATABASE_URL 或传入 <host> <port> 参数" >&2
  exit 1
fi

echo "等待 PostgreSQL 就绪: ${PGHOST}:${PGPORT} (超时: ${TIMEOUT}s)"

# 循环检查连接
while true; do
  CURRENT_TIME=$(date +%s)
  ELAPSED=$((CURRENT_TIME - START_TIME))

  if [ "$ELAPSED" -ge "$TIMEOUT" ]; then
    echo "错误: PostgreSQL 连接超时 (${TIMEOUT}s)" >&2
    exit 1
  fi

  # 优先使用 pg_isready（如可用）
  if command -v pg_isready >/dev/null 2>&1; then
    if pg_isready -h "$PGHOST" -p "$PGPORT" -q 2>/dev/null; then
      echo "PostgreSQL 已就绪!"
      exit 0
    fi
  # 回退使用 nc (netcat)
  elif command -v nc >/dev/null 2>&1; then
    if nc -z -w 2 "$PGHOST" "$PGPORT" 2>/dev/null; then
      echo "PostgreSQL 已就绪!"
      exit 0
    fi
  # 回退使用 /dev/tcp (bash 内置)
  elif command -v bash >/dev/null 2>&1; then
    if bash -c "timeout 2 bash -c 'echo > /dev/tcp/${PGHOST}/${PGPORT}'" 2>/dev/null; then
      echo "PostgreSQL 已就绪!"
      exit 0
    fi
  else
    echo "警告: 未找到 pg_isready、nc 或 bash，无法检测 PostgreSQL 状态" >&2
    echo "将在 5 秒后继续..."
    sleep 5
    exit 0
  fi

  # 等待后重试
  REMAINING=$((TIMEOUT - ELAPSED))
  echo "PostgreSQL 未就绪，剩余 ${REMAINING}s..."
  sleep 2
done
