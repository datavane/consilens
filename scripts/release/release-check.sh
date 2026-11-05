#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
# Keep gate artifacts outside target/ so `mvn clean` (root-tests gate) cannot
# delete the logs and smoke output of the running gate.
OUT="$ROOT/.release-checks/$RUN_ID"
LOG_DIR="$OUT/logs"
mkdir -p "$LOG_DIR"

WITH_MCP=true
SKIP_DOCKER=false
DOCKER_VERIFY_ONLY=false
EXTERNAL_DATABASE_VERIFY_ONLY=false
OCEANBASE_VERIFY_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --skip-mcp)
      WITH_MCP=false
      ;;
    --skip-docker)
      SKIP_DOCKER=true
      ;;
    --docker-verify-only)
      DOCKER_VERIFY_ONLY=true
      ;;
    --external-database-verify-only)
      EXTERNAL_DATABASE_VERIFY_ONLY=true
      ;;
    --oceanbase-verify-only)
      OCEANBASE_VERIFY_ONLY=true
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

run_gate() {
  local name="$1"
  shift
  echo "==> $name"
  "$@" 2>&1 | tee "$LOG_DIR/$name.log"
}

assert_report_exists() {
  local report_dir="$1"
  local test_class="$2"
  local report="$report_dir/TEST-${test_class}.xml"

  if [[ ! -f "$report" ]]; then
    echo "Failsafe report is missing for expected integration test: $test_class" >&2
    return 1
  fi
}

assert_connector_report_exists() {
  local report_dir="$1"
  local connector="$2"
  local class_pattern="$3"

  if ! find "$report_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print \
      | grep -Eq "/TEST-com\.consilens\.core\..*${class_pattern}\.xml$"; then
    echo "No executed integration report proves Connector coverage: $connector" >&2
    return 1
  fi
}

validate_default_test_manifest() {
  local report_dir="$1"
  local source_root="$ROOT/consilens-core/src/test/java"
  local source
  local test_class
  local expected=0

  while IFS= read -r source; do
    test_class="${source#"$source_root"/}"
    test_class="${test_class%.java}"
    test_class="${test_class//\//.}"
    assert_report_exists "$report_dir" "$test_class"
    expected=$((expected + 1))
  done < <(find "$source_root" -type f -name '*ITest.java' \
      ! -name 'CrossDatabaseDockerITest.java' \
      ! -name 'CrossDatabaseMysqlOceanBaseITest.java' -print | sort)

  if [[ "$expected" -eq 0 ]]; then
    echo "No default *ITest.java sources were discovered." >&2
    return 1
  fi

  assert_connector_report_exists "$report_dir" mysql \
      '(ChecksumDifferMySQLITest|JoinDifferMySQLITest|CrossDatabaseDiffITest|DatabaseAdapterITest|CrossDatabaseMysql.*ITest)'
  assert_connector_report_exists "$report_dir" postgresql \
      '(ChecksumDifferPostgresITest|CrossDatabaseDiffITest|DatabaseAdapterITest)'
  assert_connector_report_exists "$report_dir" oracle \
      '(ChecksumDifferOracleITest|DatabaseAdapterOracleITest|CrossDatabaseMysqlOracleITest)'
  assert_connector_report_exists "$report_dir" tidb 'CrossDatabaseMysqlTiDBITest'
  assert_connector_report_exists "$report_dir" clickhouse 'CrossDatabaseMysqlClickHouseITest'
  assert_connector_report_exists "$report_dir" sqlserver 'CrossDatabaseMysqlSqlServerITest'
  assert_connector_report_exists "$report_dir" starrocks 'CrossDatabaseMysqlStarRocksITest'
  assert_connector_report_exists "$report_dir" doris 'CrossDatabaseMysqlDorisITest'
  assert_connector_report_exists "$report_dir" trino 'CrossDatabaseMysqlTrinoITest'
  assert_connector_report_exists "$report_dir" presto 'CrossDatabaseMysqlPrestoITest'

  echo "Default integration manifest verified: classes=$expected, connectors=10."
}

validate_oceanbase_testcontainer_manifest() {
  local report_dir="$1"

  assert_report_exists "$report_dir" 'com.consilens.core.integration.CrossDatabaseMysqlOceanBaseITest'
  assert_connector_report_exists "$report_dir" oceanbase 'CrossDatabaseMysqlOceanBaseITest'
  echo "OceanBase Testcontainers manifest verified: classes=1, connectors=oceanbase."
}

validate_external_test_manifest() {
  local report_dir="$1"

  assert_report_exists "$report_dir" 'com.consilens.core.integration.CrossDatabaseDockerITest'
  assert_report_exists "$report_dir" 'com.consilens.core.compare.OceanBaseSameDbComparisonTest'
  assert_report_exists "$report_dir" 'com.consilens.core.compare.OceanBaseCrossDbComparisonTest'
  assert_connector_report_exists "$report_dir" oceanbase \
      '(OceanBaseSameDbComparisonTest|OceanBaseCrossDbComparisonTest)'
  echo "External integration manifest verified: classes=3, connectors=oceanbase."
}

validate_failsafe_reports() {
  local report_dir="$ROOT/consilens-core/target/failsafe-reports"
  local suite="$1"
  local reports=()
  local report
  local tests
  local skipped
  local errors
  local failures

  if [[ ! -f "$report_dir/failsafe-summary.xml" ]]; then
    echo "Failsafe summary is missing: $report_dir/failsafe-summary.xml" >&2
    return 1
  fi

  while IFS= read -r report; do
    reports+=("$report")
  done < <(find "$report_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print | sort)
  if [[ "${#reports[@]}" -eq 0 ]]; then
    echo "Failsafe did not produce any TEST-*.xml integration reports." >&2
    return 1
  fi

  if [[ "$suite" == "default" ]]; then
    for external_test in CrossDatabaseDockerITest OceanBaseSameDbComparisonTest OceanBaseCrossDbComparisonTest; do
      if find "$report_dir" -maxdepth 1 -type f -name "*${external_test}*.xml" -print -quit | grep -q .; then
        echo "Default Docker verify unexpectedly ran fixed-service test: $external_test" >&2
        return 1
      fi
    done
    validate_default_test_manifest "$report_dir"
  fi
  case "$suite" in
    default)
      ;;
    external)
      validate_external_test_manifest "$report_dir"
      ;;
    oceanbase)
      validate_oceanbase_testcontainer_manifest "$report_dir"
      ;;
    *)
      echo "Unknown integration report suite: $suite" >&2
      return 2
      ;;
  esac

  read -r tests skipped errors failures < <(
    awk '
      match($0, /tests="[0-9]+"/) { value = substr($0, RSTART + 7, RLENGTH - 8); tests += value }
      match($0, /skipped="[0-9]+"/) { value = substr($0, RSTART + 9, RLENGTH - 10); skipped += value }
      match($0, /errors="[0-9]+"/) { value = substr($0, RSTART + 8, RLENGTH - 9); errors += value }
      match($0, /failures="[0-9]+"/) { value = substr($0, RSTART + 10, RLENGTH - 11); failures += value }
      END { print tests + 0, skipped + 0, errors + 0, failures + 0 }
    ' "${reports[@]}"
  )

  if [[ "$tests" -eq 0 ]]; then
    echo "Failsafe reports contain zero executed integration tests." >&2
    return 1
  fi
  if [[ "$skipped" -ne 0 ]] || grep -Eq '<skipped([[:space:]/>])' "${reports[@]}"; then
    echo "Integration reports contain skipped tests (skipped=$skipped)." >&2
    return 1
  fi
  if [[ "$errors" -ne 0 || "$failures" -ne 0 ]]; then
    echo "Integration reports contain failures (errors=$errors, failures=$failures)." >&2
    return 1
  fi

  echo "Integration report verification passed: suite=$suite, tests=$tests, skipped=$skipped."
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI is required for the integration verify gate." >&2
    return 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is unavailable; refusing to skip integration verification." >&2
    return 1
  fi
}

docker_verify() {
  local report_dir="$ROOT/consilens-core/target/failsafe-reports"

  require_docker
  rm -rf "$report_dir"
  # 固定 JVM 默认时区：MySQL 驱动按 LOCAL 解释 TIMESTAMP，宿主时区非 UTC 时
  # 会与 Presto/ClickHouse 等 UTC 会话产生 8 小时假差异。
  TZ=UTC ./mvnw -B -pl consilens-core -am verify
  validate_failsafe_reports default
}

external_database_verify() {
  local report_dir="$ROOT/consilens-core/target/failsafe-reports"

  require_docker
  local required_env
  for required_env in CONSILENS_IT_MYSQL_PASSWORD CONSILENS_IT_POSTGRESQL_PASSWORD \
      CONSILENS_IT_CLICKHOUSE_PASSWORD CONSILENS_IT_OCEANBASE_PASSWORD; do
    if [[ -z "${!required_env:-}" ]]; then
      echo "Set $required_env for the external database verify gate" >&2
      return 1
    fi
  done
  rm -rf "$report_dir"
  ./mvnw -B -pl consilens-core -am -Pexternal-databases \
      -Dit.test=CrossDatabaseDockerITest,OceanBaseSameDbComparisonTest,OceanBaseCrossDbComparisonTest verify
  validate_failsafe_reports external
}

oceanbase_verify() {
  local report_dir="$ROOT/consilens-core/target/failsafe-reports"

  require_docker
  rm -rf "$report_dir"
  ./mvnw -B -pl consilens-core -am -Poceanbase-testcontainer \
      -Dit.test=CrossDatabaseMysqlOceanBaseITest verify
  validate_failsafe_reports oceanbase
}

find_java17() {
  if [[ -n "${JAVA17_HOME:-}" && -x "$JAVA17_HOME/bin/java" ]]; then
    echo "$JAVA17_HOME"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local home
    if home="$(/usr/libexec/java_home -v 17 2>/dev/null)" && [[ -x "$home/bin/java" ]]; then
      echo "$home"
      return 0
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    local major
    major="$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '[._]' '{print $1}')"
    if [[ "$major" == "17" || "$major" -gt 17 ]]; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  return 1
}

smoke_dist_cli() {
  local archive
  archive="$(ls -t consilens-dist/target/consilens-*.tar.gz | head -1)"
  if [[ -z "$archive" || ! -f "$archive" ]]; then
    echo "Release archive not found" >&2
    exit 1
  fi

  local smoke_dir="$OUT/smoke"
  rm -rf "$smoke_dir"
  mkdir -p "$smoke_dir"
  tar -xzf "$archive" -C "$smoke_dir"

  local app
  app="$(find "$smoke_dir" -maxdepth 1 -type d -name 'consilens-*' | head -1)"
  if [[ -z "$app" || ! -x "$app/bin/consilens-cli.sh" ]]; then
    echo "Unpacked CLI launcher not found" >&2
    exit 1
  fi
  if ! find "$app/mcp-skills" -mindepth 2 -maxdepth 2 -name SKILL.md | grep -q .; then
    echo "MCP skills not found in release archive" >&2
    exit 1
  fi
  for required in LICENSE README.md SECURITY.md SUPPORT.md; do
    if [[ ! -f "$app/$required" ]]; then
      echo "$required not found in release archive" >&2
      exit 1
    fi
  done
  if [[ ! -d "$app/docs" || -z "$(find "$app/docs" -maxdepth 1 -name '*.md' -print -quit)" ]]; then
    echo "User documentation not found in release archive" >&2
    exit 1
  fi

  if ! tar -tzf "$archive" | grep -q '/sbom/bom.json'; then
    echo "SBOM not found in release archive" >&2
    exit 1
  fi

  local cli_jar cli_entries
  cli_jar="$(find "$app/libs" -maxdepth 1 -name 'consilens-cli-*.jar' | head -1)"
  if [[ -z "$cli_jar" ]]; then
    echo "CLI jar not found in release archive" >&2
    exit 1
  fi
  cli_entries="$OUT/cli-jar-entries.txt"
  jar tf "$cli_jar" > "$cli_entries"
  for required_class in org/apache/orc/OrcFile.class org/duckdb/DuckDBDriver.class \
      com/consilens/connector/iceberg/IcebergConnectorProvider.class \
      org/apache/iceberg/TableMetadata.class org/apache/iceberg/avro/Avro.class \
      org/apache/hadoop/conf/Configuration.class; do
    if ! grep -q "$required_class" "$cli_entries"; then
      echo "Required reader class missing from CLI jar: $required_class" >&2
      exit 1
    fi
  done

  "$app/bin/consilens-cli.sh" --version

  (
    cd "$app"
    ./bin/consilens-cli.sh diff -c examples/files/csv-to-csv.yaml >/dev/null
    ./bin/consilens-cli.sh diff -c examples/files/csv-to-csv.yaml --engine duckdb >/dev/null
    ./bin/consilens-cli.sh metrics -c examples/files/csv-to-csv.yaml \
      --engine duckdb --metric count >/dev/null
    ./bin/consilens-cli.sh connector certify -c examples/files/csv-to-csv.yaml >/dev/null
  )

  local verify_dir="$OUT/dist-verify"
  rm -rf "$verify_dir"
  set +e
  (cd "$app" && ./bin/consilens-cli.sh verify -c examples/files/csv-to-csv.yaml \
      --fail-on any --format json --output-dir "$verify_dir" >/dev/null)
  local verify_exit=$?
  set -e
  if [[ "$verify_exit" -ne 1 ]]; then
    echo "dist verify expected exit 1, got $verify_exit" >&2
    exit 1
  fi
  test -f "$verify_dir/verify-result.json"
}

smoke_server_artifact() {
  local jar
  jar="$(ls -t consilens-server/target/consilens-server-*.jar | head -1)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "Server jar not found" >&2
    exit 1
  fi

  unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Main-Class: org.springframework.boot.loader.JarLauncher'
  unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Start-Class: com.consilens.server.ConsilensServerApplication'
  jar tf "$jar" | grep -q '^BOOT-INF/classes/com/consilens/server/ConsilensServerApplication.class$'
  jar tf "$jar" | grep -q '^BOOT-INF/classes/static/index.html$'

  local dist_count jar_count
  dist_count="$(find "$ROOT/consilens-ui/dist" -type f | wc -l | tr -d ' ')"
  jar_count="$(jar tf "$jar" | grep -c '^BOOT-INF/classes/static/.*[^/]$' || true)"
  if [[ "$dist_count" -ne "$jar_count" ]]; then
    echo "Server static assets do not match UI dist: dist=$dist_count jar=$jar_count" >&2
    exit 1
  fi
}

smoke_mcp() {
  local java_home="$1"
  local jar
  jar="$(ls -t consilens-mcp/target/consilens-mcp-*.jar | grep -v -- '-original\\.jar$' | head -1)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "MCP jar not found" >&2
    exit 1
  fi

  local version
  version="$("$java_home/bin/java" -jar "$jar" --version)"
  if [[ "$version" != consilens-mcp\ * ]]; then
    echo "Unexpected MCP version output: $version" >&2
    exit 1
  fi
  echo "$version"
}

if [[ "$DOCKER_VERIFY_ONLY" == "true" ]]; then
  run_gate docker-verify docker_verify
  echo "Docker integration verify passed. Logs: $LOG_DIR"
  exit 0
fi

if [[ "$EXTERNAL_DATABASE_VERIFY_ONLY" == "true" ]]; then
  run_gate external-database-verify external_database_verify
  echo "External database integration verify passed. Logs: $LOG_DIR"
  exit 0
fi

if [[ "$OCEANBASE_VERIFY_ONLY" == "true" ]]; then
  run_gate oceanbase-verify oceanbase_verify
  echo "OceanBase Testcontainers verify passed. Logs: $LOG_DIR"
  exit 0
fi

run_gate pom-versions python3 scripts/release/check-pom-versions.py
run_gate root-tests ./mvnw -B clean test
if [[ "$SKIP_DOCKER" == "true" ]]; then
  echo "==> docker-verify (skipped by --skip-docker)"
else
  run_gate docker-verify docker_verify
fi
run_gate ui-package ./mvnw -B -Prelease -pl consilens-ui package
run_gate server-package ./mvnw -B -pl consilens-server -am package -DskipTests
run_gate server-smoke smoke_server_artifact
run_gate release-package ./mvnw -B -Prelease,javadoc -pl consilens-dist -am install -DskipTests
run_gate sbom-policy python3 scripts/release/check-sbom.py consilens-dist/target/bom.json
run_gate dist-cli-smoke smoke_dist_cli

if [[ "$WITH_MCP" == "true" ]]; then
  JAVA17="$(find_java17 || true)"
  if [[ -z "$JAVA17" ]]; then
    echo "Java 17 not found. Set JAVA17_HOME or run with --skip-mcp." >&2
    exit 1
  fi
  run_gate mcp-package env JAVA_HOME="$JAVA17" ./mvnw -B -Pconsilens-mcp -pl consilens-mcp package
  run_gate mcp-smoke smoke_mcp "$JAVA17"
fi

echo "Release checks passed. Logs: $LOG_DIR"
