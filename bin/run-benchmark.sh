#!/bin/bash
# ============================================================
# Consilens 性能基准套件执行器
# ============================================================
# 覆盖三类入口：
#   micro - JMH 算法微基准（M01/M02，不依赖数据库）
#   e2e   - CLI 子进程跑 examples yaml（E02/E03/join，需要数据库凭据）
#   all   - 微基准 + 端到端
#
# 前置条件：
#   - JDK 11+
#   - e2e 需要 CLI fat jar 已构建：
#       ./mvnw -pl consilens-cli -am package
#   - e2e 数据库凭据通过环境变量注入（参考 examples/README.md）：
#       MYSQL_USER/MYSQL_PASSWORD、PG_USER/PG_PASSWORD
#
# 可选环境变量：
#   CONSILENS_CLI_JAR          - CLI fat jar 路径
#   CONSILENS_EXAMPLES_DIR     - examples 目录路径
#   CONSILENS_BENCHMARK_SKIP_BUILD - 设为 1 跳过自动构建
#   BENCHMARK_BASELINE         - 基线 JSON 路径
#   BENCHMARK_OUTPUT_DIR       - 报告输出目录
#
# 用法：
#   ./bin/run-benchmark.sh [micro|e2e|all] [--scenario E02,E03] [--update-baseline] \
#       [--forks N] [--warmup-iterations N] [--measurement-iterations N]
#       [--e2e-warmup-runs N] [--e2e-measurement-runs N]
#       [--dataset-id ID] [--key-distribution TYPE] [--difference-type TYPE]
#       [--expected-differences SCENARIO=COUNT,...]
# ============================================================

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

MODE="${1:-micro}"
shift || true

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[PASS]${NC} $1"; }
log_error()   { echo -e "${RED}[FAIL]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[SKIP]${NC} $1"; }

case "$MODE" in
    micro|e2e|all) ;;
    *)
        log_error "Unknown mode: $MODE (expected micro|e2e|all)"
        exit 1
        ;;
esac

# 收集透传给 Main 的参数，并固定基线/输出目录为绝对路径
MAIN_ARGS=("$@")
BENCHMARK_BASELINE="${BENCHMARK_BASELINE:-$ROOT_DIR/consilens-benchmark/src/main/resources/baseline/benchmark-baseline.json}"
BENCHMARK_OUTPUT_DIR="${BENCHMARK_OUTPUT_DIR:-$ROOT_DIR/consilens-benchmark/target/benchmark/report}"
MAIN_ARGS+=(--baseline-path "$BENCHMARK_BASELINE" --output-dir "$BENCHMARK_OUTPUT_DIR")

cd "$ROOT_DIR"

if [ "${CONSILENS_BENCHMARK_SKIP_BUILD:-0}" != "1" ]; then
    log_info "Building consilens-benchmark and dependencies..."
    ./mvnw -q -pl consilens-benchmark -am package -DskipTests
fi

log_info "Running benchmark mode: $MODE"
log_info "Baseline: $BENCHMARK_BASELINE"
log_info "Output: $BENCHMARK_OUTPUT_DIR"

set +e
BENCH_CLASSPATH_FILE="$ROOT_DIR/consilens-benchmark/target/benchmark-classpath.txt"
./mvnw -q -pl consilens-benchmark dependency:build-classpath \
    -Dmdep.outputFile="$BENCH_CLASSPATH_FILE"
# JMH forked VM 需要完整 java.class.path；exec:java 不传递。
java -cp "$ROOT_DIR/consilens-benchmark/target/classes:$(cat "$BENCH_CLASSPATH_FILE")" \
    com.consilens.benchmark.runner.Main --mode "$MODE" "${MAIN_ARGS[@]}"
EXIT_CODE=$?
set -e

if [ "$EXIT_CODE" -ne 0 ]; then
    log_error "benchmark failed with exit code $EXIT_CODE (回归或执行失败，报告见 $BENCHMARK_OUTPUT_DIR)"
    exit "$EXIT_CODE"
fi

log_success "benchmark finished: $MODE"
