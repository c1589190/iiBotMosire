#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

cd "$REPO_ROOT"
./mvnw -B -ntp -pl iibot-app -am -Dmaven.test.skip=true package
mkdir -p "$SIM_ROOT/dist"
if [ -f "$DIST_JAR" ]; then
    backup="$DIST_JAR.backup-$(date +%Y%m%d-%H%M%S)"
    cp -p "$DIST_JAR" "$backup"
    chmod 600 "$backup"
    echo "SIM_JAR_BACKUP=$backup"
fi
cp "$REPO_JAR" "$DIST_JAR"
chmod 600 "$DIST_JAR"
(
    cd "$SIM_ROOT/dist"
    sha256sum "$(basename "$DIST_JAR")" > "$(basename "$DIST_JAR").sha256"
    chmod 644 "$(basename "$DIST_JAR").sha256"
)
echo "SIM_JAR=$DIST_JAR"
cat "$DIST_JAR.sha256"
