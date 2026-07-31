#!/usr/bin/env bash
set -euo pipefail

# Builds a self-contained macOS app that starts Propel Web on a local port and
# opens the default browser. The output app does not require a separate Java install.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAVEN_BIN="${MAVEN_BIN:-mvn}"
JPACKAGE_BIN="${JPACKAGE_BIN:-jpackage}"
APP_VERSION="${APP_VERSION:-1.1.0}"
JAR_NAME="Auto_project-1.0-SNAPSHOT.jar"
STAGE_DIR="$PROJECT_ROOT/target/jpackage-web-input"
RELEASE_DIR="$PROJECT_ROOT/release/macos"
APP_PATH="$RELEASE_DIR/Propel Web.app"
ARCHIVE_PATH="$RELEASE_DIR/Propel-Web-macOS-arm64.zip"
SUPPLY_MATRIX="$PROJECT_ROOT/feishu/supply_matrix.xlsx"
PACKAGE_DIR="$(mktemp -d /private/tmp/propel-web-jpackage.XXXXXX)"

cleanup() {
  rm -rf "$PACKAGE_DIR"
}
trap cleanup EXIT

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script must be run on macOS." >&2
  exit 1
fi
if [[ ! -x "$JPACKAGE_BIN" ]] && ! command -v "$JPACKAGE_BIN" >/dev/null 2>&1; then
  echo "jpackage was not found. Install JDK 21 or newer." >&2
  exit 1
fi
if [[ ! -f "$SUPPLY_MATRIX" ]]; then
  echo "PICS supply matrix was not found: $SUPPLY_MATRIX" >&2
  exit 1
fi

MAVEN_ARGS=(-B package)
USE_EXISTING_JAR=false
for argument in "$@"; do
  case "$argument" in
    --skip-tests)
      MAVEN_ARGS+=(-DskipTests)
      ;;
    --use-existing-jar)
      USE_EXISTING_JAR=true
      ;;
    *)
      echo "Unknown option: $argument" >&2
      echo "Usage: $0 [--skip-tests] [--use-existing-jar]" >&2
      exit 2
      ;;
  esac
done

cd "$PROJECT_ROOT"
if [[ "$USE_EXISTING_JAR" == "false" ]]; then
  if ! command -v "$MAVEN_BIN" >/dev/null 2>&1; then
    echo "Maven was not found. Install Maven, or pass --use-existing-jar after building the JAR." >&2
    exit 1
  fi
  "$MAVEN_BIN" "${MAVEN_ARGS[@]}"
fi

JAR_PATH="$PROJECT_ROOT/target/$JAR_NAME"
if [[ ! -f "$JAR_PATH" ]]; then
  echo "Application JAR was not created: $JAR_PATH" >&2
  exit 1
fi

rm -rf "$STAGE_DIR" "$APP_PATH"
mkdir -p "$STAGE_DIR/feishu" "$RELEASE_DIR"
cp "$JAR_PATH" "$STAGE_DIR/$JAR_NAME"
cp "$SUPPLY_MATRIX" "$STAGE_DIR/feishu/supply_matrix.xlsx"
# Finder metadata inherited from a Desktop folder makes jpackage's ad-hoc
# codesign fail. Only clear attributes from disposable package copies.
xattr -cr "$STAGE_DIR" "$RELEASE_DIR" 2>/dev/null || true

"$JPACKAGE_BIN" \
  --type app-image \
  --input "$STAGE_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class com.autoproject.web.WebLauncherMain \
  --name "Propel Web" \
  --dest "$PACKAGE_DIR" \
  --app-version "$APP_VERSION" \
  --description "Open the Propel local web application" \
  --vendor "VIOOH" \
  --mac-package-identifier "com.viooh.propel.web" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Xmx4g" \
  --java-options '-Dpropel.supplyMatrixPath=$APPDIR/feishu/supply_matrix.xlsx'

BUILT_APP="$PACKAGE_DIR/Propel Web.app"

# Finder can immediately attach com.apple.FinderInfo to an .app inside a visible
# Desktop folder. Finish signing and create the distributable ZIP while the app
# is still in the private temporary directory, before Finder can mutate it.
xattr -cr "$BUILT_APP"
xattr -d com.apple.FinderInfo "$BUILT_APP" 2>/dev/null || true
codesign --force --deep --sign - "$BUILT_APP"
codesign --verify --deep --strict "$BUILT_APP"
rm -f "$ARCHIVE_PATH"
ditto -c -k --sequesterRsrc --keepParent "$BUILT_APP" "$ARCHIVE_PATH"

mv "$BUILT_APP" "$APP_PATH"

if [[ ! -x "$APP_PATH/Contents/MacOS/Propel Web" ]]; then
  echo "macOS launcher was not created: $APP_PATH" >&2
  exit 1
fi
if [[ ! -f "$APP_PATH/Contents/app/feishu/supply_matrix.xlsx" ]]; then
  echo "PICS supply matrix was not packaged into the app." >&2
  exit 1
fi

echo
echo "Propel Web is ready:"
echo "  $APP_PATH"
echo "  $ARCHIVE_PATH"
echo "Double-click the app to start the local server and open the browser."
