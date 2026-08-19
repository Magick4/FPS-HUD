#!/system/bin/sh
# Runs when the module is removed.
MODDIR=${0%/*}
export PATH=/system/bin:/system/xbin:$PATH

sh "$MODDIR/system/bin/fpshud" stop >/dev/null 2>&1
rm -f /data/adb/fox_live_fps/pid /data/adb/fox_live_fps/fpshud.log

# The user's settings are intentionally left in /data/adb/fox_live_fps so a
# reinstall picks them straight back up. Delete that folder to purge them.
