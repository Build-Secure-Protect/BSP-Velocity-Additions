#!/bin/sh
# Copies the freshly built plugin jar into the local test proxy (server/plugins) so server/start.sh loads it.
set -e
cd "$(dirname "$0")/.."
jar=$(ls build/libs/bsp_velocity_additions-*.jar | grep -v slim | head -1)
[ -n "$jar" ] || { echo "no jar in build/libs; run ./gradlew build first"; exit 1; }
mkdir -p server/plugins
rm -f server/plugins/bsp_velocity_additions-*.jar
cp "$jar" server/plugins/
echo "copied $jar -> server/plugins/"
