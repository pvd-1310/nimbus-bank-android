#!/usr/bin/env bash
set -u

backend_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd) || exit 1
project_dir=$(dirname -- "$backend_dir")
failed=0

run_check() {
    local name=$1
    shift
    printf '\n== %s ==\n' "$name"
    if "$@"; then
        printf '[PASS] %s\n' "$name"
    else
        local status=$?
        printf '[FAIL] %s (exit %s)\n' "$name" "$status" >&2
        failed=1
    fi
}

test_firebase() (
    local_temp=$(mktemp -d "${TMPDIR:-/tmp}/banking-use-cases.XXXXXX") || exit 1
    trap 'rm -rf -- "$local_temp"' EXIT
    cp -- "$project_dir/firebase.backend-test.json" "$project_dir/firestore.rules" \
        "$project_dir/firestore.indexes.json" "$local_temp/" || exit 1
    cd -- "$local_temp" || exit 1
    export BANKING_TEST_BACKEND_DIR="$backend_dir"
    CI=1 firebase emulators:exec --config firebase.backend-test.json \
        --project demo-mobile-banking --only auth,firestore \
        'npm --prefix "$BANKING_TEST_BACKEND_DIR" run test:emulator'
)

test_android() (
    cd -- "$project_dir" || exit 1
    ./gradlew "$@" --offline
)

run_check "Backend build" npm --prefix "$backend_dir" run build
run_check "Backend tests" env RUN_EMULATOR_TESTS=0 npm --prefix "$backend_dir" test
run_check "Firebase Auth and Firestore emulator tests" test_firebase
run_check "Android unit tests and debug APK" test_android :app:testDebugUnitTest :app:assembleDebug
run_check "Android instrumentation on connected device" test_android :app:connectedDebugAndroidTest

exit "$failed"
