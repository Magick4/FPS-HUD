#!/system/bin/sh
MODDIR=${0%/*}

# Wait for boot to finish so WindowManager is ready
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Launch the app_process payload in the background
export CLASSPATH=$MODDIR/fps_overlay.dex
app_process /system/bin fox.fps.FpsOverlay &
