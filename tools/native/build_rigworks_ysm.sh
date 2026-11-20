#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_directory="${RWYM_BUILD_DIRECTORY:-$task_root/build/native/rigworks_ysm/$(uname -s)-$(uname -m)}"
build_testing=ON
if [[ "${RWYM_SKIP_TESTS:-0}" == 1 ]]; then build_testing=OFF; fi
cmake -S "$task_root/native/rigworks_ysm" -B "$build_directory" \
    -DCMAKE_BUILD_TYPE=Release "-DBUILD_TESTING=$build_testing" "$@"
cmake --build "$build_directory" --parallel
if [[ "${RWYM_SKIP_TESTS:-0}" != 1 ]]; then
    ctest --test-dir "$build_directory" -C Release --output-on-failure
fi
cmake --install "$build_directory" --config Release --component RigWorksYsm \
    --prefix "$task_root/native/rigworks_ysm/dist"
