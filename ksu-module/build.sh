#!/bin/bash
# Ensure you have the Android SDK installed for javac and d8

# 1. Compile the Java source against the Android framework
javac -cp $ANDROID_HOME/platforms/android-33/android.jar fox/fps/FpsOverlay.java

# 2. Convert to DEX bytecode
$ANDROID_HOME/build-tools/33.0.0/d8 fox/fps/FpsOverlay.class --output .

# 3. Rename and package the KSU module
mv classes.dex fps_overlay.dex
zip -r fox_live_fps.zip module.prop service.sh fps_overlay.dex

echo "Done. Flash fox_live_fps.zip in KernelSU, bitch."
