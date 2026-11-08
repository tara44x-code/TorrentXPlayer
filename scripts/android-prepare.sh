#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Adjusts the Android project that `tauri android init` generates in src-tauri/gen/android:
#  - lets the player load media from its own stream server on http://127.0.0.1;
#  - adds the Java side of rustls-platform-verifier, which checks HTTPS certificates with
#    Android's own verifier (vendored in android/, from the project's Maven archive);
#  - keeps that Java code from being stripped by the release build's optimizer.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
app="$root/src-tauri/gen/android/app"
gradle="$app/build.gradle.kts"
test -f "$gradle"

sed -i 's/manifestPlaceholders\["usesCleartextTraffic"\] = "false"/manifestPlaceholders["usesCleartextTraffic"] = "true"/' "$gradle"
if grep -q 'usesCleartextTraffic"\] = "false"' "$gradle"; then
  echo "could not allow the local stream server" >&2
  exit 1
fi

mkdir -p "$app/libs"
cp "$root/android/rustls-platform-verifier-0.2.0.aar" "$app/libs/"
cat >> "$gradle" <<'GRADLE'

// Torrent X Player: the Java side of rustls-platform-verifier (HTTPS certificate checks).
dependencies {
    implementation(files("libs/rustls-platform-verifier-0.2.0.aar"))
}
GRADLE

cat >> "$app/proguard-rules.pro" <<'RULES'

# Torrent X Player: called from Rust through JNI only.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
RULES

echo "Android project prepared:"
grep -n 'usesCleartextTraffic\|rustls-platform-verifier' "$gradle"
