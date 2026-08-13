#!/usr/bin/env bash
#
# Extracts one version's section from CHANGELOG.md.
#
# The release workflow uses this to build the body of the GitHub release, so the notes people read
# on the release page and the notes in the repository are the same text. Generating them from the
# commit log instead was the alternative, and it produces a list of commit subjects — accurate, and
# not something anybody wants to read to find out whether a release affects them.
#
# Usage: tools/release-notes.sh 1.0.0 [CHANGELOG.md]
#
# Exits non-zero when the version has no section, which is deliberate: a release with empty notes
# should fail the build rather than publish.

set -euo pipefail

version="${1:?usage: release-notes.sh <version> [changelog]}"
changelog="${2:-CHANGELOG.md}"

if [[ ! -f "$changelog" ]]; then
    echo "release-notes: no changelog at '$changelog'" >&2
    exit 1
fi

# Everything between this version's heading and the next version heading at the same level.
notes="$(
    awk -v want="## $version" '
        $0 == want { collecting = 1; next }
        collecting && /^## / { exit }
        collecting { print }
    ' "$changelog"
)"

# Trim the blank lines the section boundaries leave behind.
notes="$(printf '%s\n' "$notes" | sed -e '/./,$!d' -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"

if [[ -z "${notes//[[:space:]]/}" ]]; then
    echo "release-notes: CHANGELOG.md has no '## $version' section with any content" >&2
    exit 1
fi

printf '%s\n' "$notes"
