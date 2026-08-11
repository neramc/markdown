#!/usr/bin/env bash
#
# Builds QuillSetup.exe and QuillUninstall.exe.
#
# Four stages: publish the uninstaller, stage it into a copy of the application image, pack that
# into a hash-indexed payload archive, then publish the installer with the archive embedded. The
# uninstaller goes inside the payload so an installation carries its own remover and the registered
# UninstallString points at a file that still exists after setup exits.
#
# The application image itself must be produced on Windows — jpackage cannot cross-build one — so
# this script takes a prebuilt image via --app-image, which is how the CI Windows job hands its
# output to the .NET job. Everything after that stage cross-compiles fine from Linux or macOS.
#
# Usage:
#   tools/build-installer.sh --app-image <dir> [--version <v>] [--output <dir>] [--runtime win-x64]
#   tools/build-installer.sh --no-payload      # build both executables with nothing embedded
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOLUTION_DIR="$REPO_ROOT/installer-windows"
DOTNET="${DOTNET:-dotnet}"

APP_IMAGE=""
VERSION=""
OUTPUT="$SOLUTION_DIR/artifacts"
RUNTIME="win-x64"
NO_PAYLOAD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-image)  APP_IMAGE="$2"; shift 2 ;;
    --version)    VERSION="$2";   shift 2 ;;
    --output)     OUTPUT="$2";    shift 2 ;;
    --runtime)    RUNTIME="$2";   shift 2 ;;
    --no-payload) NO_PAYLOAD=1;   shift ;;
    -h|--help)    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$VERSION" ]]; then
  # One source of truth for the version: the same property the Gradle build stamps the app with, so
  # the installer cannot advertise a version the payload does not have.
  VERSION="$(sed -n 's/^quill\.version=//p' "$REPO_ROOT/gradle.properties" | head -1)"
  VERSION="${VERSION:-0.1.0}"
fi

PAYLOAD_DIR="$SOLUTION_DIR/Quill.Installer/Payload"
PAYLOAD_ARCHIVE="$PAYLOAD_DIR/quill-payload.zip"
STAGING="$SOLUTION_DIR/artifacts/staging"

echo "==> Quill installer build"
echo "    version : $VERSION"
echo "    runtime : $RUNTIME"
echo "    output  : $OUTPUT"

publish() {
  local project="$1"
  local destination="$2"

  "$DOTNET" publish "$SOLUTION_DIR/$project" \
    -c Release \
    -f net10.0-windows \
    -r "$RUNTIME" \
    --self-contained true \
    -p:PublishSingleFile=true \
    -p:Version="$VERSION" \
    -o "$destination"
}

rm -rf "$PAYLOAD_DIR" "$STAGING"
mkdir -p "$PAYLOAD_DIR" "$OUTPUT"

# ---------------------------------------------------------------- 1. uninstaller

echo "==> Publishing QuillUninstall.exe"
publish "Quill.Uninstaller/Quill.Uninstaller.csproj" "$OUTPUT/uninstaller"

# ---------------------------------------------------------------- 2 & 3. stage and pack

if [[ "$NO_PAYLOAD" -eq 1 ]]; then
  echo "==> Skipping payload; the wizard will report that it carries no application image."
else
  if [[ ! -d "$APP_IMAGE" ]]; then
    echo "error: pass --app-image <dir> (a Windows app image) or --no-payload" >&2
    exit 2
  fi

  echo "==> Staging $APP_IMAGE"
  mkdir -p "$STAGING"
  cp -a "$APP_IMAGE/." "$STAGING/"
  cp "$OUTPUT/uninstaller/QuillUninstall.exe" "$STAGING/QuillUninstall.exe"

  echo "==> Packing payload"
  # Packing runs through the same PayloadBuilder the extractor was written against, so the archive
  # and its index can never disagree about format.
  "$DOTNET" run --project "$SOLUTION_DIR/Quill.Setup.Pack/Quill.Setup.Pack.csproj" -c Release -- \
    "$STAGING" "$PAYLOAD_ARCHIVE" "$VERSION"
fi

# ---------------------------------------------------------------- 4. installer

echo "==> Publishing QuillSetup.exe"
publish "Quill.Installer/Quill.Installer.csproj" "$OUTPUT/installer"

echo "==> Done"
ls -lh "$OUTPUT/installer/QuillSetup.exe" "$OUTPUT/uninstaller/QuillUninstall.exe"
