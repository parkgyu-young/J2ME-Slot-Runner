#!/bin/sh
# Builds tools/testmidlet/out/NetRmsTest.jar + .jad against the emulator's own MIDP/CLDC classes.
# Requires: JDK, a previous "./gradlew :app:compileDebugJavaWithJavac", ANDROID_HOME (or local.properties).
set -e
cd "$(dirname "$0")"
ROOT=../..
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' $ROOT/local.properties)}
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
APP_CLASSES=$(find $ROOT/app/build/intermediates/javac/debug -type d -name classes | head -1)
[ -d "$APP_CLASSES" ] || { echo "Run ./gradlew :app:compileDebugJavaWithJavac first"; exit 1; }
rm -rf out && mkdir -p out/classes
javac --release 8 -nowarn -cp "$APP_CLASSES:$ANDROID_JAR" -d out/classes src/slottest/NetRmsTest.java
cat > out/MANIFEST.MF <<MF
Manifest-Version: 1.0
MIDlet-1: NetRmsTest, , slottest.NetRmsTest
MIDlet-Name: NetRmsTest
MIDlet-Vendor: J2ME Slot Runner
MIDlet-Version: 1.0.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
Test-URL: http://example.com/
Test-Socket: example.com:80
MF
(cd out && jar cfm NetRmsTest.jar MANIFEST.MF -C classes .)
SIZE=$(wc -c < out/NetRmsTest.jar | tr -d ' ')
cat > out/NetRmsTest.jad <<JAD
MIDlet-1: NetRmsTest, , slottest.NetRmsTest
MIDlet-Jar-Size: $SIZE
MIDlet-Jar-URL: NetRmsTest.jar
MIDlet-Name: NetRmsTest
MIDlet-Vendor: J2ME Slot Runner
MIDlet-Version: 1.0.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
Test-URL: http://example.com/
Test-Socket: example.com:80
JAD
echo "Built $(pwd)/out/NetRmsTest.jar ($SIZE bytes) and NetRmsTest.jad"
