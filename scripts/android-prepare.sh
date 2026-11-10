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

# libVLC (LGPL-2.1+) for the built-in player: the newest 3.x release on Maven Central.
libvlc_version=$(curl -fsSL https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml \
  | grep -o '<version>3\.[0-9]*\.[0-9]*</version>' | sed 's/<[^>]*>//g' | sort -V | tail -n 1 || true)
libvlc_version=${libvlc_version:-3.6.0}
echo "libVLC $libvlc_version"
cat >> "$gradle" <<GRADLE

// Torrent X Player: libVLC powers the built-in player (PlayerActivity).
dependencies {
    implementation("org.videolan.android:libvlc-all:$libvlc_version")
}
GRADLE

cat >> "$app/proguard-rules.pro" <<'RULES'

# Torrent X Player: called from Rust through JNI only.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
# libVLC calls into its Java classes from native code.
-keep class org.videolan.libvlc.** { *; }
# Called from Rust through JNI only.
-keep class com.tara44x.torrentxplayer.LinkInbox { *; }
RULES

# A foreground service keeps the stream server alive while VLC plays in front.
kotlin_dir="$app/src/main/java/com/tara44x/torrentxplayer"
test -f "$kotlin_dir/MainActivity.kt"
cp "$root"/android/kotlin/*.kt "$kotlin_dir/"
manifest="$app/src/main/AndroidManifest.xml"
sed -i 's|<uses-permission android:name="android.permission.INTERNET" />|&\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />|' "$manifest"
sed -i 's|</application>|    <service android:name=".StreamService" android:exported="false" android:foregroundServiceType="mediaPlayback" android:stopWithTask="true" />\n    </application>|' "$manifest"
sed -i 's|</application>|    <activity android:name=".PlayerActivity" android:exported="false" android:configChanges="orientation\|screenSize\|smallestScreenSize\|screenLayout\|keyboardHidden\|uiMode" android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" android:supportsPictureInPicture="true" android:resizeableActivity="true" />\n    </application>|' "$manifest"
grep -q 'android:name=".PlayerActivity"' "$manifest"
# Magnet links and .torrent files from the browser, file managers, and the share menu.
python3 - "$manifest" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
filters = """
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="magnet" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="application/x-bittorrent" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/x-bittorrent" />
            </intent-filter>
        </activity>"""
assert "</activity>" in text
text = text.replace("</activity>", filters.lstrip("\n"), 1)
open(path, "w").write(text)
PY
grep -q 'android:scheme="magnet"' "$manifest"
grep -q 'FOREGROUND_SERVICE_MEDIA_PLAYBACK' "$manifest"
grep -q 'android:name=".StreamService"' "$manifest"

# Gradle itself: from its GitHub mirror, which stays up when services.gradle.org does not.
wrapper="$root/src-tauri/gen/android/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$wrapper" ]; then
  sed -i -E 's#^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-([0-9.]+)-(bin|all)\.zip#distributionUrl=https\\://github.com/gradle/gradle-distributions/releases/download/v\1/gradle-\1-\2.zip#' "$wrapper"
  grep '^distributionUrl' "$wrapper"
fi

echo "Android project prepared:"
grep -n 'usesCleartextTraffic\|rustls-platform-verifier' "$gradle"
cat "$manifest"
