#!/bin/bash
# ============================================================
# Consilens 数据比对引擎回归测试执行器
# ============================================================
# 覆盖两套矩阵：
#   1. cross-db/   : 10 个 MySQL -> 目标端 pair（01-11 场景）
#   2. same-db/    : 11 种数据源同库自比（01 identical / 02 join-diff / 03 normalization）
# 每个配置跑完后与 test-baselines.json 中的基线断言比对。
#
# 前置条件：
#   - 数据库运行中（deploy/ 或本地实例）
#   - consilens-cli fat jar 已构建：
#       ./mvnw -pl consilens-cli -am package
#   - 数据库凭据通过环境变量注入（仓库不落明文）
#
# 可选环境变量：
#   CONSILENS_CLI_JAR        - CLI fat jar 路径
#   CONSILENS_CHECK_BASELINE - 设为 0 跳过基线断言
#   CONSILENS_RUN_PERFORMANCE - 设为 1 运行 200 万行性能用例（06）
#   MYSQL_HOST/PORT, CLICKHOUSE_PORT, TIDB_PORT, STARROCKS_PORT,
#   DORIS_PORT, ORACLE_PORT, SQLSERVER_PORT, OCEANBASE_PORT,
#   TRINO_PORT, PRESTO_PORT  - 数据源端口覆盖
#   *_USER / *_PASSWORD      - 各数据源凭据
#
# 用法：
#   ./run-comparison-test.sh [all|cross-db|same-db|<pair>]
# ============================================================

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_JAR="${CONSILENS_CLI_JAR:-${SCRIPT_DIR}/../consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar}"
BASELINE_FILE="${SCRIPT_DIR}/test-baselines.json"
CHECK_BASELINE="${CONSILENS_CHECK_BASELINE:-1}"
RUN_PERFORMANCE="${CONSILENS_RUN_PERFORMANCE:-0}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-13306}"
CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"
TIDB_PORT="${TIDB_PORT:-4000}"
STARROCKS_PORT="${STARROCKS_PORT:-9030}"
DORIS_PORT="${DORIS_PORT:-9030}"
ORACLE_PORT="${ORACLE_PORT:-1521}"
SQLSERVER_PORT="${SQLSERVER_PORT:-1433}"
OCEANBASE_PORT="${OCEANBASE_PORT:-2881}"
TRINO_PORT="${TRINO_PORT:-8081}"
PRESTO_PORT="${PRESTO_PORT:-8085}"

PASSED=0; FAILED=0; SKIPPED=0
RESULTS=()

log_info()   { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success(){ echo -e "${GREEN}[PASS]${NC} $1"; }
log_error()  { echo -e "${RED}[FAIL]${NC} $1"; }
log_warn()   { echo -e "${YELLOW}[SKIP]${NC} $1"; }

require_env() {
    if [ -z "${!1:-}" ]; then
        log_error "Missing required environment variable: $1"
        exit 1
    fi
}

require_env MYSQL_USER
require_env MYSQL_PASSWORD
require_env PG_USER
require_env PG_PASSWORD
require_env CLICKHOUSE_USER
require_env CLICKHOUSE_PASSWORD
require_env TIDB_USER
require_env TIDB_PASSWORD
require_env STARROCKS_USER
require_env STARROCKS_PASSWORD
require_env DORIS_USER
require_env DORIS_PASSWORD
require_env ORACLE_USER
require_env ORACLE_PASSWORD
require_env SQLSERVER_USER
require_env SQLSERVER_PASSWORD
require_env TRINO_USER
require_env TRINO_PASSWORD
require_env PRESTO_USER
require_env PRESTO_PASSWORD
require_env OCEANBASE_USER
require_env OCEANBASE_PASSWORD

check_cli() {
    if [ ! -f "$CLI_JAR" ]; then
        log_error "consilens CLI JAR not found at: $CLI_JAR"
        log_info "Please build the project first: ./mvnw -pl consilens-cli -am package"
        exit 1
    fi
}

run_comparison() {
    local config_path="$1"
    local test_name="$2"
    log_info "Running: $test_name"
    log_info "Config: $config_path"

    local output
    output="$(java -jar "$CLI_JAR" diff --config "$config_path" 2>&1)"
    local exit_code=$?

    if [ $exit_code -ne 0 ]; then
        echo "$output"
        log_error "$test_name"
        FAILED=$((FAILED + 1))
        RESULTS+=("FAIL: $test_name")
        return 1
    fi

    if [ "$CHECK_BASELINE" = "1" ]; then
        if ! assert_baseline "$config_path" "$test_name" "$output"; then
            log_error "$test_name"
            FAILED=$((FAILED + 1))
            RESULTS+=("FAIL: $test_name")
            return 1
        fi
    fi

    log_success "$test_name"
    PASSED=$((PASSED + 1))
    RESULTS+=("PASS: $test_name")
    return 0
}

assert_baseline() {
    local config_path="$1"
    local test_name="$2"
    local output="$3"

    local relative_path="${config_path#${SCRIPT_DIR}/}"
    local source_missing target_missing mismatch total
    source_missing="$(echo "$output" | sed -n 's/.*Source missing rows: \([0-9][0-9]*\).*/\1/p' | head -1)"
    target_missing="$(echo "$output" | sed -n 's/.*Target missing rows: \([0-9][0-9]*\).*/\1/p' | head -1)"
    mismatch="$(echo "$output" | sed -n 's/.*Mismatched rows: \([0-9][0-9]*\).*/\1/p' | head -1)"
    total="$(echo "$output" | sed -n 's/.*Total differences: \([0-9][0-9]*\).*/\1/p' | head -1)"

    if [ -z "$total" ]; then
        log_error "Cannot parse diff statistics for $test_name"
        echo "$output"
        return 1
    fi

    local expected_total expected_min expected_sm expected_tm expected_mm
    expected_total="$(python3 -c "import json,sys; b=json.load(open('$BASELINE_FILE'))['baselines']; d=b.get('$relative_path', {}); print(d.get('total', ''))" 2>/dev/null)"
    expected_min="$(python3 -c "import json,sys; b=json.load(open('$BASELINE_FILE'))['baselines']; d=b.get('$relative_path', {}); print(d.get('totalMin', ''))" 2>/dev/null)"
    expected_sm="$(python3 -c "import json,sys; b=json.load(open('$BASELINE_FILE'))['baselines']; d=b.get('$relative_path', {}); print(d.get('sourceMissing', ''))" 2>/dev/null)"
    expected_tm="$(python3 -c "import json,sys; b=json.load(open('$BASELINE_FILE'))['baselines']; d=b.get('$relative_path', {}); print(d.get('targetMissing', ''))" 2>/dev/null)"
    expected_mm="$(python3 -c "import json,sys; b=json.load(open('$BASELINE_FILE'))['baselines']; d=b.get('$relative_path', {}); print(d.get('mismatch', ''))" 2>/dev/null)"

    if [ -z "$expected_total" ] && [ -z "$expected_min" ]; then
        log_warn "No baseline entry for $relative_path; assert exit code only"
        return 0
    fi

    local ok=1
    if [ -n "$expected_total" ] && [ "$total" != "$expected_total" ]; then
        log_error "  $test_name: total differences expected $expected_total but got $total"
        ok=0
    fi
    if [ -n "$expected_min" ] && [ "$total" -lt "$expected_min" ]; then
        log_error "  $test_name: total differences expected at least $expected_min but got $total"
        ok=0
    fi
    if [ -n "$expected_sm" ] && [ "${source_missing:-0}" != "$expected_sm" ]; then
        log_error "  $test_name: source missing rows expected $expected_sm but got ${source_missing:-0}"
        ok=0
    fi
    if [ -n "$expected_tm" ] && [ "${target_missing:-0}" != "$expected_tm" ]; then
        log_error "  $test_name: target missing rows expected $expected_tm but got ${target_missing:-0}"
        ok=0
    fi
    if [ -n "$expected_mm" ] && [ "${mismatch:-0}" != "$expected_mm" ]; then
        log_error "  $test_name: mismatched rows expected $expected_mm but got ${mismatch:-0}"
        ok=0
    fi

    if [ $ok -eq 1 ]; then
        log_info "  Baseline ok ($test_name): total=$total, sourceMissing=${source_missing:-0}, targetMissing=${target_missing:-0}, mismatch=${mismatch:-0}"
    fi
    return $((1 - ok))
}

load_mysql_data() {
    local sql_file="$1"
    log_info "Loading MySQL data: $sql_file"
    env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" < "$sql_file" 2>/dev/null || {
        log_warn "Failed to load MySQL data from $sql_file (may already exist)"
    }
}

load_target_data() {
    local pair="$1"
    local sql_file="$2"
    local loader="$3"
    shift 3

    if [ ! -f "$sql_file" ]; then
        log_warn "Target load SQL not found: $sql_file"
        return 0
    fi
    if ! command -v "$loader" >/dev/null 2>&1; then
        log_warn "Target loader '$loader' not installed; load $sql_file manually for pair $pair"
        return 0
    fi
    log_info "Loading target data for $pair: $sql_file"
    local has_placeholder=false
    local args=()
    local arg
    for arg in "$@"; do
        if [ "$arg" = "{SQL_FILE}" ]; then
            has_placeholder=true
            args+=("$sql_file")
        else
            args+=("$arg")
        fi
    done
    if [ "$has_placeholder" = true ]; then
        "${args[@]}" || {
            log_warn "Failed to load target data from $sql_file (may already exist)"
        }
    else
        "${args[@]}" < "$sql_file" || {
            log_warn "Failed to load target data from $sql_file (may already exist)"
        }
    fi
}

load_cross_db_pair() {
    local pair="$1"
    local pair_dir="${SCRIPT_DIR}/cross-db/${pair}"

    if [ ! -d "$pair_dir" ]; then
        log_warn "Directory not found: $pair_dir"
        SKIPPED=$((SKIPPED + 1))
        return
    fi

    echo ""
    echo "--------------------------------------------"
    log_info "Testing cross-db pair: $pair"
    echo "--------------------------------------------"

    if [ -f "${pair_dir}/load-mysql.sql" ]; then
        load_mysql_data "${pair_dir}/load-mysql.sql"
    fi

    case "$pair" in
        mysql-pg)
            load_target_data "$pair" "${pair_dir}/load-postgresql.sql" psql \
                env PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$PG_USER" -d postgres
            ;;
        mysql-clickhouse)
            load_target_data "$pair" "${pair_dir}/load-clickhouse.sql" clickhouse-client \
                clickhouse-client --host 127.0.0.1 --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD"
            ;;
        mysql-tidb)
            load_target_data "$pair" "${pair_dir}/load-tidb.sql" mysql \
                env MYSQL_PWD="$TIDB_PASSWORD" mysql -h 127.0.0.1 -P "$TIDB_PORT" -u "$TIDB_USER"
            ;;
        mysql-starrocks)
            load_target_data "$pair" "${pair_dir}/load-starrocks.sql" mysql \
                env MYSQL_PWD="$STARROCKS_PASSWORD" mysql -h 127.0.0.1 -P "$STARROCKS_PORT" -u "$STARROCKS_USER"
            ;;
        mysql-doris)
            load_target_data "$pair" "${pair_dir}/load-doris.sql" mysql \
                env MYSQL_PWD="$DORIS_PASSWORD" mysql -h 127.0.0.1 -P "$DORIS_PORT" -u "$DORIS_USER"
            ;;
        mysql-oceanbase)
            load_target_data "$pair" "${pair_dir}/load-oceanbase.sql" mysql \
                env MYSQL_PWD="$OCEANBASE_PASSWORD" mysql -h 127.0.0.1 -P "$OCEANBASE_PORT" -u "$OCEANBASE_USER"
            ;;
        mysql-sqlserver)
            load_target_data "$pair" "${pair_dir}/load-sqlserver.sql" sqlcmd \
                sqlcmd -S "127.0.0.1,${SQLSERVER_PORT}" -U "$SQLSERVER_USER" -P "$SQLSERVER_PASSWORD" -i {SQL_FILE}
            ;;
        mysql-trino)
            load_target_data "$pair" "${pair_dir}/load-trino-target.sql" mysql \
                env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER"
            ;;
        mysql-presto)
            load_target_data "$pair" "${pair_dir}/load-presto-target.sql" mysql \
                env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER"
            ;;
        mysql-oracle)
            if command -v sqlplus >/dev/null 2>&1; then
                log_warn "Oracle load is a manual step; run load-oracle.sql through sqlplus with ORACLE_PASSWORD substituted"
            else
                log_warn "sqlplus not installed; load ${pair_dir}/load-oracle.sql manually for pair $pair"
            fi
            ;;
    esac

    for config_file in "${pair_dir}"/[0-9][0-9]-*; do
        if [ -f "$config_file" ]; then
            local basename=$(basename "$config_file")
            if [[ "$basename" == 06-* ]] && [ "$RUN_PERFORMANCE" != "1" ]; then
                log_warn "Skipping performance scenario: cross-db/${pair}/${basename} (set CONSILENS_RUN_PERFORMANCE=1 to run)"
                SKIPPED=$((SKIPPED + 1))
                continue
            fi
            run_comparison "$config_file" "cross-db/${pair}/${basename}" || true
        fi
    done
}

load_same_db_pair() {
    local name="$1"
    local pair_dir="${SCRIPT_DIR}/same-db/${name}"

    if [ ! -d "$pair_dir" ]; then
        log_warn "Directory not found: $pair_dir"
        SKIPPED=$((SKIPPED + 1))
        return
    fi

    echo ""
    echo "--------------------------------------------"
    log_info "Testing same-db pair: $name"
    echo "--------------------------------------------"

    local seed_file="$(ls "${pair_dir}"/load-*.sql 2>/dev/null | head -1)"
    if [ -n "$seed_file" ] && [ -f "$seed_file" ]; then
        case "$name" in
            mysql)
                load_mysql_data "$seed_file"
                ;;
            tidb)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$TIDB_PASSWORD" mysql -h 127.0.0.1 -P "$TIDB_PORT" -u "$TIDB_USER"
                ;;
            starrocks)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$STARROCKS_PASSWORD" mysql -h 127.0.0.1 -P "$STARROCKS_PORT" -u "$STARROCKS_USER"
                ;;
            doris)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$DORIS_PASSWORD" mysql -h 127.0.0.1 -P "$DORIS_PORT" -u "$DORIS_USER"
                ;;
            oceanbase)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$OCEANBASE_PASSWORD" mysql -h 127.0.0.1 -P "$OCEANBASE_PORT" -u "$OCEANBASE_USER"
                ;;
            postgresql)
                load_target_data "$name" "$seed_file" psql                     env PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$PG_USER" -d postgres
                ;;
            clickhouse)
                load_target_data "$name" "$seed_file" clickhouse-client                     clickhouse-client --host 127.0.0.1 --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD"
                ;;
            sqlserver)
                load_target_data "$name" "$seed_file" sqlcmd                     sqlcmd -S "127.0.0.1,${SQLSERVER_PORT}" -U "$SQLSERVER_USER" -P "$SQLSERVER_PASSWORD" -i {SQL_FILE}
                ;;
            trino)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER"
                ;;
            presto)
                load_target_data "$name" "$seed_file" mysql                     env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER"
                ;;
            oracle)
                if command -v sqlplus >/dev/null 2>&1; then
                    log_warn "Oracle same-db load is a manual step; run $seed_file through sqlplus with ORACLE_PASSWORD substituted"
                else
                    log_warn "sqlplus not installed; load $seed_file manually for same-db/$name"
                fi
                ;;
        esac
    fi

    for config_file in "${pair_dir}"/[0-9][0-9]-*; do
        if [ -f "$config_file" ]; then
            run_comparison "$config_file" "same-db/${name}/$(basename "$config_file")" || true
        fi
    done
}

main() {
    echo "============================================"
    echo " Consilens Data Comparison Regression Tests"
    echo "============================================"
    echo ""

    check_cli

    local target="${1:-all}"

    local cross_pairs=("mysql-pg" "mysql-clickhouse" "mysql-tidb" "mysql-starrocks"
                       "mysql-doris" "mysql-trino" "mysql-presto" "mysql-oceanbase"
                       "mysql-oracle" "mysql-sqlserver")
    local same_pairs=("mysql" "postgresql" "clickhouse" "tidb" "starrocks" "doris"
                      "trino" "presto" "oceanbase" "oracle" "sqlserver")

    case "$target" in
        all)
            for pair in "${cross_pairs[@]}"; do load_cross_db_pair "$pair"; done
            for name in "${same_pairs[@]}"; do load_same_db_pair "$name"; done
            ;;
        cross-db)
            for pair in "${cross_pairs[@]}"; do load_cross_db_pair "$pair"; done
            ;;
        same-db)
            for name in "${same_pairs[@]}"; do load_same_db_pair "$name"; done
            ;;
        *)
            if [ -d "${SCRIPT_DIR}/cross-db/${target}" ]; then
                load_cross_db_pair "$target"
            elif [ -d "${SCRIPT_DIR}/same-db/${target}" ]; then
                load_same_db_pair "$target"
            else
                log_error "Unknown test target: $target"
                echo "Available: all | cross-db | same-db | <pair>"
                exit 1
            fi
            ;;
    esac

    echo ""
    echo "============================================"
    echo " Test Results Summary"
    echo "============================================"
    echo -e " ${GREEN}Passed:  $PASSED${NC}"
    echo -e " ${RED}Failed:  $FAILED${NC}"
    echo -e " ${YELLOW}Skipped: $SKIPPED${NC}"
    echo "============================================"
    echo ""

    for result in "${RESULTS[@]}"; do
        if [[ "$result" == PASS* ]]; then
            echo -e "  ${GREEN}✓${NC} ${result#PASS: }"
        else
            echo -e "  ${RED}✗${NC} ${result#FAIL: }"
        fi
    done

    echo ""

    if [ $FAILED -gt 0 ]; then
        exit 1
    fi
}

main "$@"
