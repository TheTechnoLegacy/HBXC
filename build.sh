#!/usr/bin/env bash
# Builds and signs HBXC.apk from the sources in this folder (no Gradle needed).
#
# Required environment variables:
#   ANDROID_JAR  path to an android.jar (any platform API 19+; compile-time only)
#   APKTOOL_JAR  path to apktool (2.9.x) jar  - builds the resources/manifest
#   SIGNER_JAR   path to uber-apk-signer jar  - zipaligns and signs the APK
# Optional:
#   JAVAC  Java compiler command (default: javac, JDK 8+)
#   D8     D8 dex compiler command (default: d8, from Android build-tools)
#   KEYSTORE / KS_ALIAS / KS_PASS  sign with your own key instead of the debug key
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${APKTOOL_JAR:?set APKTOOL_JAR}" "${SIGNER_JAR:?set SIGNER_JAR}"
JAVAC=${JAVAC:-javac}; D8=${D8:-d8}
rm -rf out build && mkdir -p out/classes out/dex
$JAVAC -source 8 -target 8 -nowarn -cp "$ANDROID_JAR" -d out/classes $(find src -name '*.java')
$D8 --release --min-api 15 --lib "$ANDROID_JAR" --output out/dex $(find out/classes -name '*.class')
java -jar "$APKTOOL_JAR" b . -o out/unsigned.apk
python3 - <<'PY'
import zipfile
z = zipfile.ZipFile('out/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('out/dex/classes.dex', 'classes.dex')
z.close()
PY
if [ -n "${KEYSTORE:-}" ]; then
  java -jar "$SIGNER_JAR" -a out/unsigned.apk -o out/signed --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS"
  cp out/signed/unsigned-aligned-signed.apk HBXC.apk
else
  java -jar "$SIGNER_JAR" -a out/unsigned.apk -o out/signed --allowResign
  cp out/signed/unsigned-aligned-debugSigned.apk HBXC.apk
fi
echo "Built HBXC.apk"
