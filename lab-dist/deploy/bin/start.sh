#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$APP_HOME"

if [ ! -f config/.env ]; then
    echo '请先复制 config/.env.example 为 config/.env，并填写服务器配置。' >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo '未找到 Java，请安装 Java 17 并配置 PATH 或 JAVA_HOME。' >&2
    exit 1
fi

mkdir -p var/logs
exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$APP_HOME/novid.jar" \
    "--lab.env-file=$APP_HOME/config/.env" \
    "--spring.config.additional-location=file:$APP_HOME/config/" \
    "--logging.config=$APP_HOME/config/log4j2-spring.xml" "$@"
