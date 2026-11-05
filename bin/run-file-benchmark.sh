#!/bin/bash
# 本地文件（CSV/Parquet）端到端比对基准。
# 用法：bin/run-file-benchmark.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CLASSPATH_FILE="$ROOT_DIR/consilens-benchmark/target/benchmark-classpath.txt"

cd "$ROOT_DIR"
./mvnw -q -pl consilens-benchmark -am package -DskipTests
./mvnw -q -pl consilens-benchmark dependency:build-classpath \
    -Dmdep.outputFile="$CLASSPATH_FILE"

# JMH 的 forked VM 需要完整的 java.class.path；exec:java 不传递，直接启动。
exec java -cp "$ROOT_DIR/consilens-benchmark/target/classes:$(cat "$CLASSPATH_FILE")" \
    com.consilens.benchmark.runner.FileBenchmarkMain
