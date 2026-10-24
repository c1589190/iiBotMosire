#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

cd "$REPO_ROOT"
./mvnw -B -ntp -pl iibot-app -am -Dmaven.test.skip=true package
mkdir -p "$SIM_ROOT/dist"
cp "$REPO_JAR" "$DIST_JAR"
chmod 600 "$DIST_JAR"
sha256sum "$DIST_JAR" > "$DIST_JAR.sha256"
echo "SIM_JAR=$DIST_JAR"
cat "$DIST_JAR.sha256"
