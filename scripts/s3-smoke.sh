#!/usr/bin/env bash
#
# S3 兼容存储冒烟：用真实对象存储（MinIO 或任何 S3 兼容服务）验证 file 连接器的
# 单对象、前缀、glob、JSONL、Parquet、凭据链、临时目录清理与错误路径。
#
# 用法：
#   S3_ENDPOINT=http://127.0.0.1:9000 S3_ACCESS_KEY=minioadmin S3_SECRET_KEY=minioadmin \
#     scripts/s3-smoke.sh
#
# 依赖：java 11+、已构建的 CLI shaded jar、`mc`（MinIO Client）用于准备对象。
# 环境变量：
#   S3_ENDPOINT       默认 http://127.0.0.1:9000
#   S3_BUCKET         默认 consilens-s3-smoke
#   S3_REGION         默认 us-east-1
#   S3_PREFIX         默认 s3-smoke；对象都会写在该前缀下
#   S3_ACCESS_KEY / S3_SECRET_KEY  默认 minioadmin / minioadmin
#   CLI_JAR           默认 consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar
#   MC_BIN            默认 mc
#   S3_SMOKE_KEEP=1   保留工作目录与上传对象，便于排查
#
# 退出码：0 全部通过，1 有检查失败，2 前置条件缺失。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_JAR="${CLI_JAR:-$ROOT/consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar}"
MC_BIN="${MC_BIN:-mc}"
S3_ENDPOINT="${S3_ENDPOINT:-http://127.0.0.1:9000}"
S3_BUCKET="${S3_BUCKET:-consilens-s3-smoke}"
S3_REGION="${S3_REGION:-us-east-1}"
S3_PREFIX="${S3_PREFIX:-s3-smoke}"
S3_ACCESS_KEY="${S3_ACCESS_KEY:-minioadmin}"
S3_SECRET_KEY="${S3_SECRET_KEY:-minioadmin}"
ROWS="${S3_SMOKE_ROWS:-1000}"

WORK_DIR="${S3_SMOKE_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/consilens-s3smoke-XXXXXX")}"
# CLI 运行时使用独立 TMPDIR，便于精确断言下载临时目录被清理
CLI_TMP="$WORK_DIR/tmp"
LOG_DIR="$WORK_DIR/logs"
DATA_DIR="$WORK_DIR/data"
PASS=0
FAIL=0
FAILED_CHECKS=()

log() { printf '%s\n' "$*"; }
section() { printf '\n=== %s\n' "$*"; }

fail() {
  FAIL=$((FAIL + 1))
  FAILED_CHECKS+=("$1")
  printf 'FAIL  %s\n' "$1"
}

pass() {
  PASS=$((PASS + 1))
  printf 'ok    %s\n' "$1"
}

die() {
  printf 'ERROR %s\n' "$*" >&2
  exit 2
}

have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- prerequisites
have java || die "java not found"
[[ -f "$CLI_JAR" ]] || die "CLI jar not found: $CLI_JAR (run ./mvnw -pl consilens-cli -am -DskipTests package)"
have "$MC_BIN" || die "MinIO client not found: $MC_BIN (set MC_BIN=/path/to/mc)"
mkdir -p "$LOG_DIR" "$DATA_DIR" "$CLI_TMP"

log "endpoint : $S3_ENDPOINT"
log "bucket   : $S3_BUCKET"
log "prefix   : $S3_PREFIX"
log "work dir : $WORK_DIR"

# ------------------------------------------------------------------ data set-up
make_data() {
  local csv_dir="$DATA_DIR/local"
  mkdir -p "$csv_dir" "$DATA_DIR/target" "$DATA_DIR/jsonl" "$DATA_DIR/parquet" "$DATA_DIR/nested"

  awk -v rows="$ROWS" 'BEGIN {
    print "id,name,amount"
    for (i = 1; i <= rows; i++) printf "%d,payload_%d,%.2f\n", i, i, i * 0.5
  }' > "$DATA_DIR/source.csv"

  # 目标侧：每 20 行改一行，差异数 = floor(rows / 20)
  awk -v rows="$ROWS" 'BEGIN {
    print "id,name,amount"
    for (i = 1; i <= rows; i++) {
      name = sprintf("payload_%d", i)
      if (i % 20 == 0) name = name "_changed"
      printf "%d,%s,%.2f\n", i, name, i * 0.5
    }
  }' > "$DATA_DIR/target.csv"

  # 前缀场景：源侧切成两个对象，内容与 source.csv 相同
  awk -v rows="$ROWS" 'BEGIN {
    print "id,name,amount"
    half = int(rows / 2)
    for (i = 1; i <= half; i++) printf "%d,payload_%d,%.2f\n", i, i, i * 0.5
  }' > "$csv_dir/part-000.csv"
  awk -v rows="$ROWS" 'BEGIN {
    print "id,name,amount"
    half = int(rows / 2)
    for (i = half + 1; i <= rows; i++) printf "%d,payload_%d,%.2f\n", i, i, i * 0.5
  }' > "$csv_dir/part-001.csv"
  # 非数据文件：前缀/glob 必须忽略
  echo "not a data file" > "$csv_dir/notes.txt"

  # 目录标记对象（以 / 结尾）必须忽略
  : > "$DATA_DIR/dir-marker"

  # 深层目录：验证 ** 跨目录匹配（行区间不与 part-000/001 重叠）
  awk -v rows="$ROWS" 'BEGIN {
    print "id,name,amount"
    for (i = rows + 1; i <= rows + 100; i++) printf "%d,payload_%d,%.2f\n", i, i, i * 0.5
  }' > "$DATA_DIR/nested/part-002.csv"

  awk -v rows="$ROWS" 'BEGIN {
    for (i = 1; i <= rows; i++) printf "{\"id\":%d,\"name\":\"payload_%d\",\"amount\":%.2f}\n", i, i, i * 0.5
  }' > "$DATA_DIR/jsonl/data.jsonl"

  local gen="$WORK_DIR/S3FixtureTool.java"
  cat > "$gen" <<'JAVA'
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Smoke fixture helper: Parquet fixture via DuckDB and S3 directory markers via the AWS SDK. */
public final class S3FixtureTool {

    public static void main(String[] args) throws Exception {
        if ("parquet".equals(args[0])) {
            parquet(args[1], args[2]);
        } else if ("marker".equals(args[0])) {
            marker(args[1], args[2], args[3], args[4], args[5], args[6]);
        } else {
            throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }

    private static void parquet(String csv, String out) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT * FROM read_csv_auto('" + csv
                    + "')) TO '" + out + "' (FORMAT PARQUET)");
        }
    }

    /** Hadoop/Spark style directory marker: a zero byte object whose key ends with "/". */
    private static void marker(String endpoint, String bucket, String key,
                               String accessKey, String secretKey, String region) {
        try (S3Client client = S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build()) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.empty());
        }
    }
}
JAVA
  java -cp "$CLI_JAR" "$gen" parquet "$DATA_DIR/source.csv" "$DATA_DIR/parquet/data.parquet" \
      > "$LOG_DIR/gen-parquet.log" 2>&1 \
    || die "failed to generate parquet fixture, see $LOG_DIR/gen-parquet.log"
  [[ -s "$DATA_DIR/parquet/data.parquet" ]] || die "parquet fixture is empty"
  printf '%s\n' "$gen" > "$DATA_DIR/.fixture-tool"
}

# ------------------------------------------------------------------- S3 fixtures
upload() {
  local alias="consilens-smoke"
  "$MC_BIN" alias set "$alias" "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" \
      > "$LOG_DIR/mc-alias.log" 2>&1 || die "mc alias set failed, see $LOG_DIR/mc-alias.log"
  "$MC_BIN" mb --ignore-existing "$alias/$S3_BUCKET" > "$LOG_DIR/mc-mb.log" 2>&1 \
      || die "mc mb failed, see $LOG_DIR/mc-mb.log"

  local base="$alias/$S3_BUCKET/$S3_PREFIX"
  "$MC_BIN" cp "$DATA_DIR/local/part-000.csv" "$base/csv/part-000.csv" > "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload part-000 failed"
  "$MC_BIN" cp "$DATA_DIR/local/part-001.csv" "$base/csv/part-001.csv" >> "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload part-001 failed"
  "$MC_BIN" cp "$DATA_DIR/local/notes.txt" "$base/csv/notes.txt" >> "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload notes.txt failed"
  "$MC_BIN" cp "$DATA_DIR/nested/part-002.csv" "$base/nested/deep/part-002.csv" >> "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload nested part failed"
  "$MC_BIN" cp "$DATA_DIR/jsonl/data.jsonl" "$base/jsonl/data.jsonl" >> "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload jsonl failed"
  "$MC_BIN" cp "$DATA_DIR/parquet/data.parquet" "$base/parquet/data.parquet" >> "$LOG_DIR/mc-cp.log" 2>&1 \
      || die "upload parquet failed"
  # 以 / 结尾的空对象，模拟 Hadoop/Spark 写出的目录标记
  java -cp "$CLI_JAR" "$(cat "$DATA_DIR/.fixture-tool")" marker "$S3_ENDPOINT" "$S3_BUCKET" \
      "$S3_PREFIX/csv/" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" "$S3_REGION" \
      > "$LOG_DIR/marker.log" 2>&1 \
      || die "failed to write directory marker, see $LOG_DIR/marker.log"
}

# -------------------------------------------------------------------- CLI config
# write_config <file> <source-resource-block> <target-resource-block>
write_config() {
  local file="$1" source="$2" target="$3"
  cat > "$file" <<YAML
source:
  type: file
  name: smoke-source
  resource:
$source
target:
  type: file
  name: smoke-target
  resource:
$target
comparison:
  keys:
    source: [id]
    target: [id]
  fields:
    source: [name, amount]
    target: [name, amount]
result:
  sinks:
    - format: console
      type: result
YAML
}

# Nested "options:" form is the one shown in docs/13; keep it exercised here so a
# regression in option flattening fails the smoke.
s3_source_block() {
  local path="$1" format="$2"
  cat <<YAML
    type: file
    path: $path
    options:
      format: $format
      s3Region: $S3_REGION
      s3Endpoint: $S3_ENDPOINT
      s3PathStyle: true
      s3AccessKey: $S3_ACCESS_KEY
      s3SecretKey: $S3_SECRET_KEY
YAML
}

s3_source_block_env_credentials() {
  local path="$1" format="$2"
  cat <<YAML
    type: file
    path: $path
    format: $format
    s3Region: $S3_REGION
    s3Endpoint: $S3_ENDPOINT
    s3PathStyle: true
YAML
}

local_block() {
  local path="$1" format="$2"
  cat <<YAML
    type: file
    name: $path
    format: $format
YAML
}

# run_diff <name> <config> <expected-exit> <expected-differences|->
run_diff() {
  local name="$1" config="$2" expected_exit="$3" expected_diffs="${4:--}"
  local stdout="$LOG_DIR/$name.json" stderr="$LOG_DIR/$name.log"
  TMPDIR="$CLI_TMP" java -jar "$CLI_JAR" diff -c "$config" --fail-on-diff > "$stdout" 2> "$stderr"
  local exit_code=$?

  if [[ "$exit_code" != "$expected_exit" ]]; then
    fail "$name: exit=$exit_code (expected $expected_exit), see $stderr"
    return
  fi
  if [[ "$expected_diffs" != "-" ]]; then
    local actual
    actual="$(grep -o '"totalDifferences" : [0-9]*' "$stdout" | head -1 | grep -o '[0-9]*$')"
    if [[ -z "$actual" ]]; then
      fail "$name: no totalDifferences in output, see $stdout"
      return
    fi
    if [[ "$actual" != "$expected_diffs" ]]; then
      fail "$name: totalDifferences=$actual (expected $expected_diffs)"
      return
    fi
  fi
  pass "$name"
}

s3_temp_dirs() {
  find "$CLI_TMP" -maxdepth 1 -name 'consilens-s3-*' -type d 2>/dev/null | wc -l | tr -d ' '
}

# ------------------------------------------------------------------------ checks
main() {
  make_data
  upload
  local base="s3://$S3_BUCKET/$S3_PREFIX"
  local before_tmp
  before_tmp="$(s3_temp_dirs)"

  section "1. 单对象（CSV）"
  write_config "$WORK_DIR/single.yaml" \
    "$(s3_source_block "$base/csv/part-000.csv" csv)" \
    "$(local_block "$DATA_DIR/local/part-000.csv" csv)"
  run_diff "single-object" "$WORK_DIR/single.yaml" 0 0

  section "2. 前缀（忽略目录标记与非数据文件）"
  write_config "$WORK_DIR/prefix-identical.yaml" \
    "$(s3_source_block "$base/csv/" csv)" \
    "$(local_block "$DATA_DIR/source.csv" csv)"
  run_diff "prefix-identical" "$WORK_DIR/prefix-identical.yaml" 0 0

  section "3. 前缀（有差异，--fail-on-diff 退出 2）"
  local expected_diffs=$((ROWS / 20))
  write_config "$WORK_DIR/prefix-diff.yaml" \
    "$(s3_source_block "$base/csv/" csv)" \
    "$(local_block "$DATA_DIR/target.csv" csv)"
  run_diff "prefix-with-differences" "$WORK_DIR/prefix-diff.yaml" 2 "$expected_diffs"

  section "4. glob（*.csv 与 **/*.csv）"
  write_config "$WORK_DIR/glob-single-level.yaml" \
    "$(s3_source_block "$base/csv/*.csv" csv)" \
    "$(local_block "$DATA_DIR/source.csv" csv)"
  run_diff "glob-csv" "$WORK_DIR/glob-single-level.yaml" 0 0

  local recursive="$DATA_DIR/recursive"
  mkdir -p "$recursive"
  cp "$DATA_DIR/local/part-000.csv" "$recursive/part-000.csv"
  cp "$DATA_DIR/local/part-001.csv" "$recursive/part-001.csv"
  cp "$DATA_DIR/nested/part-002.csv" "$recursive/part-002.csv"
  write_config "$WORK_DIR/glob-recursive.yaml" \
    "$(s3_source_block "$base/**/*.csv" csv)" \
    "$(local_block "$recursive" csv)"
  # csv/part-000、csv/part-001、nested/deep/part-002 三个对象，与本地同名文件逐一对应
  run_diff "glob-recursive" "$WORK_DIR/glob-recursive.yaml" 0 0

  section "5. JSONL 对象"
  write_config "$WORK_DIR/jsonl.yaml" \
    "$(s3_source_block "$base/jsonl/data.jsonl" jsonl)" \
    "$(local_block "$DATA_DIR/jsonl/data.jsonl" jsonl)"
  run_diff "jsonl-object" "$WORK_DIR/jsonl.yaml" 0 0

  section "6. Parquet 对象 vs 本地 CSV（跨格式）"
  write_config "$WORK_DIR/parquet.yaml" \
    "$(s3_source_block "$base/parquet/data.parquet" parquet)" \
    "$(local_block "$DATA_DIR/source.csv" csv)"
  run_diff "parquet-object-vs-csv" "$WORK_DIR/parquet.yaml" 0 0

  section "7. AWS 默认凭据链（环境变量）"
  write_config "$WORK_DIR/env-credentials.yaml" \
    "$(s3_source_block_env_credentials "$base/csv/part-000.csv" csv)" \
    "$(local_block "$DATA_DIR/local/part-000.csv" csv)"
  AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
      AWS_REGION="$S3_REGION" AWS_EC2_METADATA_DISABLED=true \
      run_diff "env-credential-chain" "$WORK_DIR/env-credentials.yaml" 0 0

  section "8. 错误路径（前缀无对象）"
  write_config "$WORK_DIR/missing.yaml" \
    "$(s3_source_block "$base/does-not-exist/" csv)" \
    "$(local_block "$DATA_DIR/source.csv" csv)"
  local stdout="$LOG_DIR/missing-prefix.json" stderr="$LOG_DIR/missing-prefix.log"
  TMPDIR="$CLI_TMP" java -jar "$CLI_JAR" diff -c "$WORK_DIR/missing.yaml" > "$stdout" 2> "$stderr"
  local exit_code=$?
  if [[ "$exit_code" == "0" ]]; then
    fail "missing-prefix: expected a failure, got exit 0"
  elif ! grep -qi "No S3 objects matched" "$stderr"; then
    fail "missing-prefix: expected 'No S3 objects matched', see $stderr"
  else
    pass "missing-prefix"
  fi

  section "9. 临时目录清理"
  local after_tmp
  after_tmp="$(s3_temp_dirs)"
  if [[ "$after_tmp" -gt "$before_tmp" ]]; then
    fail "temp-cleanup: $after_tmp consilens-s3-* directories left behind"
    find "$CLI_TMP" -maxdepth 1 -name 'consilens-s3-*' -type d 2>/dev/null | sed 's/^/      /'
  else
    pass "temp-cleanup"
  fi

  section "汇总"
  printf 'passed: %d, failed: %d\n' "$PASS" "$FAIL"
  if [[ "$FAIL" -gt 0 ]]; then
    printf 'failed checks:\n'
    printf '  - %s\n' "${FAILED_CHECKS[@]}"
    printf 'logs: %s\n' "$LOG_DIR"
    return 1
  fi
  if [[ "${S3_SMOKE_KEEP:-0}" != "1" ]]; then
    "$MC_BIN" rm --recursive --force "consilens-smoke/$S3_BUCKET/$S3_PREFIX" \
        > "$LOG_DIR/mc-rm.log" 2>&1 || true
    rm -rf "$WORK_DIR"
  else
    printf 'kept: %s (objects under s3://%s/%s/)\n' "$WORK_DIR" "$S3_BUCKET" "$S3_PREFIX"
  fi
  printf 's3 smoke passed\n'
  return 0
}

main "$@"
