#!/bin/bash
# 以数据库批量写入方式生成千万/亿级 benchmark 数据。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -eq 0 ]; then
    cat >&2 <<'USAGE'
用法：
  bin/generate-benchmark-data.sh \
    --jdbc-url jdbc:mysql://127.0.0.1:13306/consilens_demo \
    --username "$MYSQL_USER" --password "$MYSQL_PASSWORD" \
    --rows 100000000 --diff-ratio 0.05 --batch-size 5000

可选：--source-missing-ratio 0.01 --target-missing-ratio 0.01 --seed 42 --resume
USAGE
    exit 2
fi

cd "$ROOT_DIR"
./mvnw -pl consilens-benchmark -am package -DskipTests -q
exec ./mvnw -pl consilens-benchmark -Pbenchmark exec:java \
    -Dexec.mainClass=com.consilens.benchmark.data.BenchmarkDataMain \
    -Dexec.args="$*"
