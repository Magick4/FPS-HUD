#!/system/bin/sh
# KernelSU-Next / Magisk "Action" button: flip the HUD on or off.
MODDIR=${0%/*}
export PATH=/system/bin:/system/xbin:$PATH

sh "$MODDIR/system/bin/fpshud" toggle
sh "$MODDIR/system/bin/fpshud" status
