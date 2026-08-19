#!/system/bin/sh
# Late-start service: wait for boot, then bring the HUD up and keep it alive.
MODDIR=${0%/*}
export PATH=/system/bin:/system/xbin:$PATH

nohup sh "$MODDIR/system/bin/fpshud" boot >/dev/null 2>&1 &
