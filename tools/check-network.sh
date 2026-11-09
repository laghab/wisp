#!/usr/bin/env bash
# Task 10.1: the network may only be used inside the :net module, and the app may only ask for the permissions Wisp is documented to need.
# Fails (exit 1) on any violation. Run from anywhere: tools/check-network.sh
cd "$(dirname "$0")/.." || exit 1
fail=0
# 1. Network APIs outside :net (app sources, main and debug).
pat='HttpURLConnection|HttpsURLConnection|java\.net\.(Socket|URL|DatagramSocket|ServerSocket)\b|URL\(|okhttp|retrofit|WebView|DownloadManager|ConnectivityManager|Volley|ktor|\.openConnection\('
hits=$(grep -rnE "$pat" app/src --include=*.kt --include=*.java | grep -vE '^\S+:[0-9]+:\s*(//|\*|/\*)' | grep -vE 'Uri\.parse|android\.net\.Uri')
if [ -n "$hits" ]; then echo "FAIL network API used outside :net:"; echo "$hits"; fail=1; else echo "PASS no network API outside :net"; fi
# 2. Only the documented permissions are declared in the app manifests.
allowed='RECORD_AUDIO|INTERNET|FOREGROUND_SERVICE|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS|REQUEST_INSTALL_PACKAGES|VIBRATE'
perms=$(grep -ohE 'uses-permission[^>]*android:name="[^"]+"' app/src/*/AndroidManifest.xml | sed -E 's/.*name="android\.permission\.([^"]+)".*/\1/' | sort -u)
bad=$(echo "$perms" | grep -vxE "$allowed" | grep -v '^$')
if [ -n "$bad" ]; then echo "FAIL undeclared permissions in the manifest: $bad"; fail=1; else echo "PASS permissions are within the documented set: $(echo $perms)"; fi
# 3. No analytics / crash-reporting / ads libraries in the build.
if grep -qiE 'firebase|crashlytics|analytics|sentry|mixpanel|amplitude|admob|play-services|appcenter|bugsnag' gradle/libs.versions.toml app/build.gradle.kts net/build.gradle.kts; then echo "FAIL a telemetry or Play Services dependency is declared"; fail=1; else echo "PASS no telemetry, ads or Play Services dependencies"; fi
# 4. Only :net may declare a networking library; :net itself uses the platform's HttpURLConnection.
if grep -qE 'okhttp|retrofit|ktor|volley' net/build.gradle.kts; then echo "FAIL :net pulls in a networking library"; fail=1; else echo "PASS :net uses only the platform HTTP client"; fi
# 5. Cleartext only in debug builds.
if grep -q 'usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml; then echo "FAIL release manifest allows cleartext traffic"; fail=1; else echo "PASS release manifest does not allow cleartext traffic"; fi
exit $fail
