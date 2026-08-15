#!/usr/bin/env bash
#
# Collects every platform's build output into one directory of consistently named release assets.
#
# jpackage names its output after its own conventions, and they are not one convention:
#
#   quill_1.0.0_amd64.deb        Debian's architecture spelling
#   quill-1.0.0-1.x86_64.rpm     RPM's spelling, plus a release number
#   Quill-1.0.0.dmg              no architecture at all
#
# Three spellings of the same machine, and the one that matters most -- the DMG -- does not say
# which architecture it is for. Put those in a release list beside each other and an Apple Silicon
# user has no way to tell which file is theirs. So every asset is renamed to one scheme:
#
#   Quill-<version>-<platform>.<extension>
#
# The portable archives are already named that way by the Gradle task that builds them, and are
# copied through unchanged rather than renamed twice.
#
# Usage: tools/assemble-release.sh <version> <downloaded-dir> <output-dir>
#
# <downloaded-dir> holds one directory per platform, named `packages-<platform>`, plus an optional
# `packages-windows-setup`.

set -euo pipefail

version="${1:?usage: assemble-release.sh <version> <downloaded-dir> <output-dir>}"
downloaded="${2:?usage: assemble-release.sh <version> <downloaded-dir> <output-dir>}"
output="${3:?usage: assemble-release.sh <version> <downloaded-dir> <output-dir>}"

# The platforms a release covers. Windows on ARM is deliberately absent: it runs the x64 build under
# emulation, and shipping a second Windows package for it would be a download nobody needs to
# choose between.
platforms=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64)

mkdir -p "$output"

extension_of() {
    case "$1" in
        # Two-part extensions have to be special-cased or `.tar.gz` becomes `.gz`.
        *.tar.gz) printf 'tar.gz' ;;
        *.tar.xz) printf 'tar.xz' ;;
        *)        printf '%s' "${1##*.}" ;;
    esac
}

collected=0

for platform in "${platforms[@]}"; do
    directory="$downloaded/packages-$platform"
    if [[ ! -d "$directory" ]]; then
        echo "assemble-release: no artifacts for $platform" >&2
        continue
    fi

    for file in "$directory"/*; do
        [[ -f "$file" ]] || continue
        base="$(basename "$file")"

        case "$base" in
            # Already named for its platform by the build; copying it through keeps one name for
            # one file rather than renaming something that is already right.
            Quill-*-"$platform".*)
                cp "$file" "$output/$base"
                ;;
            *)
                cp "$file" "$output/Quill-$version-$platform.$(extension_of "$base")"
                ;;
        esac
        collected=$((collected + 1))
    done
done

# The Windows installer keeps its own name. It is the file somebody double-clicks, and "QuillSetup"
# says what it will do in a way "Quill-1.0.0-windows-x64.exe" does not.
setup="$downloaded/packages-windows-setup"
if [[ -d "$setup" ]]; then
    while IFS= read -r -d '' file; do
        case "$(basename "$file")" in
            QuillSetup.exe) cp "$file" "$output/QuillSetup-$version-windows-x64.exe" ;;
            *) continue ;;
        esac
        collected=$((collected + 1))
    done < <(find "$setup" -type f -print0)
fi

if [[ "$collected" -eq 0 ]]; then
    echo "assemble-release: found nothing to release under '$downloaded'" >&2
    exit 1
fi

# Checksums, so somebody downloading 75MB of binary from the internet can tell whether it is the
# file this pipeline built. Generated last so the file lists everything beside it.
(cd "$output" && sha256sum ./* > SHA256SUMS)

echo "assemble-release: $collected asset(s) in $output"
ls -la "$output"
