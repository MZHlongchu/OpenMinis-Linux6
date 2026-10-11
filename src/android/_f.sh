#!/bin/bash
cd "$(dirname "$0")" || exit 1
export ANDROID_HOME="D:\\Android\\Sdk"
echo "===== architectureCheck ====="
./gradlew :architectureCheck --no-daemon --console=plain 2>&1 | grep -E "Architecture violations|BUILD" | head -4
echo "===== :harness:test ====="
./gradlew :harness:test --no-daemon --console=plain -Dkotlin.compiler.execution.strategy=in-process > /tmp/h3.log 2>&1
echo "harness exit=$?"
grep -E "tests? completed|BUILD (SUCCESSFUL|FAILED)" /tmp/h3.log | head -3
echo "===== :app:testSlimReleaseUnitTest (max heap) ====="
./gradlew :app:testSlimReleaseUnitTest --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx2600m -Dfile.encoding=UTF-8" -Dkotlin.daemon.jvmargs="-Xmx2600m" > /tmp/t5.log 2>&1
echo "apptests exit=$?"
grep -E "tests? completed|FAILED|OutOfMemory|BUILD (SUCCESSFUL|FAILED)" /tmp/t5.log | head -20
exit 0