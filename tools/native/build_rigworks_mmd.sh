#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_directory="${RWMD_BUILD_DIRECTORY:-$task_root/build/native/rigworks_mmd/$(uname -s)-$(uname -m)}"
cmake_options=()
if [[ -n "${RWMD_CMAKE_OPTIONS:-}" ]]; then
    read -r -a cmake_options <<< "$RWMD_CMAKE_OPTIONS"
fi
cmake -S "$task_root/native/rigworks_mmd" -B "$build_directory" \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON "${cmake_options[@]}"
cmake --build "$build_directory" --config Release --parallel
ctest --test-dir "$build_directory" -C Release --output-on-failure
cmake --install "$build_directory" --config Release --component RigWorksMmd \
    --prefix "$task_root/native/rigworks_mmd/dist"
