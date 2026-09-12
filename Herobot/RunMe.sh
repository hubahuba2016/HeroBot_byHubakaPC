#!/bin/bash

set -e
cd "$(dirname "$0")"

echo "Building project..."
./gradlew --stop >/dev/null 2>&1 || true
./gradlew clean build

echo "Running application..."
./gradlew run --console=plain
