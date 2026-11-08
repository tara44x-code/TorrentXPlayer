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

# A foreground service keeps the stream server alive while VLC plays in front.
kotlin_dir="$app/src/main/java/com/tara44x/torrentxplayer"
test -f "$kotlin_dir/MainActivity.kt"
cp "$root/android/kotlin/MainActivity.kt" "$root/android/kotlin/StreamService.kt" "$kotlin_dir/"
manifest="$app/src/main/AndroidManifest.xml"
sed -i 's|<uses-permission android:name="android.permission.INTERNET" />|&\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />|' "$manifest"
sed -i 's|</application>|    <service android:name=".StreamService" android:exported="false" android:foregroundServiceType="mediaPlayback" android:stopWithTask="true" />\n    </application>|' "$manifest"
grep -q 'FOREGROUND_SERVICE_MEDIA_PLAYBACK' "$manifest"
grep -q 'android:name=".StreamService"' "$manifest"

echo "Android project prepared:"
grep -n 'usesCleartextTraffic\|rustls-platform-verifier' "$gradle"
cat "$manifest"
