#!/system/bin/sh
##########################################################################
# Live FPS HUD - installer
##########################################################################

SKIPUNZIP=0
DATADIR=/data/adb/fox_live_fps

ui_print " "
ui_print "  ┌────────────────────────────────────┐"
ui_print "  │        L I V E   F P S   H U D     │"
ui_print "  └────────────────────────────────────┘"
ui_print " "

# ---- environment ------------------------------------------------------
if [ "$KSU" = "true" ]; then
	ui_print "- Root      : KernelSU $KSU_VER (kernel $KSU_KERNEL_VER_CODE)"
elif [ "$APATCH" = "true" ]; then
	ui_print "- Root      : APatch $APATCH_VER_CODE"
elif [ -n "$MAGISK_VER_CODE" ]; then
	ui_print "- Root      : Magisk $MAGISK_VER_CODE"
else
	ui_print "! Unknown root manager - continuing anyway"
fi

ui_print "- Device    : $(getprop ro.product.model) [$(getprop ro.board.platform)]"
ui_print "- ROM       : $(getprop ro.build.display.id)"
ui_print "- Android   : $(getprop ro.build.version.release) (API $API)"

if [ "$API" -lt 26 ]; then
	abort "! Android 8.0 (API 26) or newer is required"
fi

if [ ! -f /system/bin/app_process ] && [ ! -f /system/bin/app_process64 ]; then
	abort "! app_process not found - this ROM cannot run the HUD"
fi

# ---- payload ----------------------------------------------------------
if [ ! -f "$MODPATH/fps_overlay.dex" ]; then
	abort "! fps_overlay.dex missing from the zip - grab a release build"
fi

# ---- settings ---------------------------------------------------------
mkdir -p "$DATADIR"
if [ -f "$DATADIR/config.prop" ]; then
	ui_print "- Keeping your existing settings"
else
	cp -f "$MODPATH/config.default.prop" "$DATADIR/config.prop"
	ui_print "- Installed default settings"
fi
chmod 0755 "$DATADIR"
chmod 0644 "$DATADIR/config.prop"

# ---- fps source report ------------------------------------------------
ui_print " "
NODE=""
for d in /sys/class/drm/sde-crtc-* /sys/class/drm/crtc-*; do
	[ -e "$d/measured_fps" ] && NODE="$d/measured_fps" && break
done
if [ -n "$NODE" ]; then
	ui_print "- FPS source: $NODE"
	ui_print "              reads $(head -n1 "$NODE" 2>/dev/null)"
else
	ui_print "- FPS source: no kernel node found on this device,"
	ui_print "              the HUD will count vsyncs instead."
fi

# ---- permissions ------------------------------------------------------
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print " "
ui_print "- Installed. Reboot, or start it right now with:"
ui_print "    su -c fpshud start"
ui_print "- Configure it from the module's WebUI button."
ui_print " "
