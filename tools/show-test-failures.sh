#!/usr/bin/env bash
#
# Prints the assertion behind every failing test, from the JUnit XML.
#
# A failing test on a CI runner is otherwise close to unreadable. Gradle prints the assertion where
# the test ran and then buries it: `--stacktrace` adds two hundred frames of
# org.gradle.internal.execution on top, the "what went wrong" summary says only "There were failing
# tests, see the report at ...", and that report is an HTML file on a machine that stops existing
# when the job ends. Three separate attempts to diagnose an arm64-only failure from those logs
# turned up nothing but stack frames.
#
# So this reads the results Gradle wrote and prints them at the very end of the job, where the tail
# of a log always reaches: which test, and what it asserted. Nothing else.
#
# Usage: tools/show-test-failures.sh [root]

set -uo pipefail

root="${1:-.}"
found=0

while IFS= read -r report; do
    grep -q '<failure\|<error' "$report" || continue
    found=1
    echo "::group::$report"
    python3 - "$report" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    tree = ET.parse(sys.argv[1])
except ET.ParseError as error:
    print(f"could not read {sys.argv[1]}: {error}")
    raise SystemExit(0)

for case in tree.getroot().iter("testcase"):
    for bad in list(case.iter("failure")) + list(case.iter("error")):
        print(f"FAILED  {case.get('classname')} :: {case.get('name')}")
        message = (bad.get("message") or "").strip()
        if message:
            print(message[:2000])
        # Two things are worth keeping out of the body. The frames, because the message does not
        # carry the test's own line number and the first few frames do. And every "Caused by",
        # because the message of an ExceptionInInitializerError is empty and the sentence that
        # says what actually went wrong is three levels down the chain -- which is exactly the
        # case this script was written for.
        lines = (bad.text or "").splitlines()
        frames = [line for line in lines if line.strip().startswith("at ")]
        causes = [
            line for index, line in enumerate(lines)
            if line.strip().startswith("Caused by") or (index and lines[index - 1].strip().startswith("Caused by"))
        ]
        for line in causes[:8]:
            print(line.rstrip()[:2000])
        for line in frames[:4]:
            print(line.rstrip())
        print()
PY
    echo "::endgroup::"
done < <(find "$root" -path '*/build/test-results/*' -name '*.xml' 2>/dev/null)

if [[ "$found" -eq 0 ]]; then
    echo "show-test-failures: no test report holds a failure, so the build broke somewhere other than a test"
fi
