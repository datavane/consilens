#!/bin/bash
# Maven Central 发布脚本（需要外部凭据）。
#
# 前置条件：
#   1. GPG 私钥可用（gpg --list-secret-keys）；
#   2. ~/.m2/settings.xml 配置 <server><id>central</id>...；
#   3. 可选：MAVEN_GPG_PASSPHRASE 环境变量。
#
# 用法：
#   scripts/release/deploy-central.sh            # staging（autoPublish=false）
#   scripts/release/deploy-central.sh --publish  # 上传后自动发布
set -euo pipefail

AUTO_PUBLISH="false"
if [[ "${1:-}" == "--publish" ]]; then
  AUTO_PUBLISH="true"
fi

command -v gpg >/dev/null 2>&1 || { echo "gpg not found" >&2; exit 1; }
gpg --list-secret-keys >/dev/null 2>&1 || { echo "no GPG secret key" >&2; exit 1; }

SETTINGS="$HOME/.m2/settings.xml"
if [[ ! -f "$SETTINGS" ]] || ! grep -q '<id>central</id>' "$SETTINGS"; then
  echo "settings.xml missing <server><id>central</id> credentials" >&2
  exit 1
fi

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> deploy to Sonatype Central (autoPublish=$AUTO_PUBLISH)"
./mvnw -B -Prelease,javadoc,publish \
  -DskipTests \
  -DautoPublish="$AUTO_PUBLISH" \
  deploy

echo "==> next steps"
echo "  1. 在 central.sonatype.com 核对 staging 仓库的 jar/sources/javadoc/pom/SBOM"
echo "  2. 确认无误后 publish；若 autoPublish=true 已自动发布"
