#!/usr/bin/env bash
set -euo pipefail

package_type="${1:-app-image}"
case "$(uname -s)" in
  Linux)
    case "$package_type" in
      app-image|deb|rpm) ;;
      *) echo "Unsupported Linux package type: $package_type" >&2; exit 2 ;;
    esac
    ;;
  Darwin)
    case "$package_type" in
      app-image|pkg|dmg) ;;
      *) echo "Unsupported macOS package type: $package_type" >&2; exit 2 ;;
    esac
    ;;
  *)
    echo "Use scripts/package-app.cmd on Windows." >&2
    exit 2
    ;;
esac

rm -rf target/jpackage
./mvnw --batch-mode --no-transfer-progress -Pdesktop-package -DskipTests clean package

app_jar="$(find target -maxdepth 1 -type f -name 'tetris-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
if [ -z "$app_jar" ]; then
  echo "Tetris application JAR was not produced." >&2
  exit 1
fi

cp "$app_jar" target/jpackage/modules/

app_version="$(tr -d '\r\n' < src/main/resources/VERSION)"
app_version="${app_version#v}"
mkdir -p target/jpackage/dist

jpackage \
  --type "$package_type" \
  --name Tetris \
  --dest target/jpackage/dist \
  --module-path target/jpackage/modules \
  --module xyz.xuminghai.tetris/xyz.xuminghai.tetris.TetrisApplication \
  --jlink-options "--no-man-pages --no-header-files" \
  --app-version "$app_version"

echo "Created $package_type under target/jpackage/dist"
