#!/usr/bin/env bash
# Task 10.3: checks the prebuilt native libraries against the pinned checksums in docs/native-libs.sha256.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/jniLibs"
sha256sum --check --quiet "../../../../docs/native-libs.sha256" && echo "PASS native libraries match the pinned checksums"
