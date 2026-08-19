package fox.fps;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live FPS overlay for rooted Android (KernelSU / KernelSU-Next / Magisk).
 *
 * Runs as a plain root process through app_process, grabs the framework system
 * context out of ActivityThread and pushes a system overlay window straight
 * into WindowManager - no APK, no "draw over other apps" permission.
 *
 * Everything (colours, position, refresh interval, FPS source) is read from
 * /data/adb/fox_live_fps/config.prop and hot-reloaded whenever that file
 * changes, so the WebUI can tweak the HUD without restarting it.
 */
public final class FpsOverlay {

    /* ------------------------------------------------------------------ */
    /* paths                                                               */
    /* ------------------------------------------------------------------ */

    private static final File DATA_DIR = new File("/data/adb/fox_live_fps");
    private static final File CONFIG = new File(DATA_DIR, "config.prop");
    private static final File LOG = new File(DATA_DIR, "fpshud.log");
    private static final long LOG_MAX_BYTES = 64L * 1024L;

    /* ------------------------------------------------------------------ */
    /* runtime state                                                       */
    /* ------------------------------------------------------------------ */

    private static Config cfg = new Config();
    private static long cfgStamp = -1L;

    private static Context viewContext;
    private static WindowManager windowManager;
    private static TextView hud;
    private static GradientDrawable hudBackground;
    private static WindowManager.LayoutParams layout;
    private static Handler handler;

    private static FpsSource source;
    private static VsyncSource vsync;
    private static int attachedWindowType = -1;
    private static float refreshRate = 60f;
    private static long refreshStamp = 0L;
    private static Display display;
    private static int consecutiveFailures = 0;

    public static void main(String[] args) {
        try {
            exemptHiddenApi();
            Looper.prepareMainLooper();

            Context system = systemContext();
            DisplayManager dm = (DisplayManager) system.getSystemService(Context.DISPLAY_SERVICE);
            display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Context displayContext = system.createDisplayContext(display);
            viewContext = new ContextThemeWrapper(displayContext, android.R.style.Theme_DeviceDefault);
            windowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

            handler = new Handler(Looper.myLooper());
            vsync = new VsyncSource();
            vsync.start();

            loadConfig();
            pickSource();
            attach();

            log("overlay started (sdk=" + Build.VERSION.SDK_INT + ", source=" + sourceName() + ")");
            handler.post(TICK);
            Looper.loop();
        } catch (Throwable t) {
            log("fatal: " + stack(t));
            System.exit(1);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the loop                                                            */
    /* ------------------------------------------------------------------ */

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            int delay = 500;
            try {
                if (reloadConfigIfChanged()) {
                    pickSource();
                    applyConfig();
                }
                delay = cfg.interval;

                if (!cfg.enabled) {
                    if (hud != null) {
                        detach();
                    }
                } else {
                    if (hud == null) {
                        attach();
                    }
                    render();
                }
            } catch (Throwable t) {
                consecutiveFailures++;
                log("tick failed (" + consecutiveFailures + "): " + stack(t));
                if (consecutiveFailures >= 5) {
                    log("too many failures, exiting so the watchdog can restart us");
                    System.exit(2);
                }
            }
            handler.postDelayed(this, delay);
        }
    };

    private static void render() {
        float fps = source == null ? -1f : source.read();
        if (fps < 0f && source != vsync) {
            // primary source went silent - fall back to the vsync counter
            float alt = vsync.read();
            if (alt >= 0f) {
                fps = alt;
            }
        }

        long now = System.currentTimeMillis();
        if (now - refreshStamp > 2000L) {
            refreshStamp = now;
            try {
                float r = display.getRefreshRate();
                if (r > 1f) {
                    refreshRate = r;
                }
            } catch (Throwable ignored) {
            }
        }

        StringBuilder sb = new StringBuilder();
        if (cfg.label.length() > 0) {
            sb.append(cfg.label).append(' ');
        }
        if (fps < 0f) {
            sb.append("--");
        } else {
            sb.append(format(fps, cfg.decimals));
        }
        if (cfg.showHz) {
            sb.append("  ").append(format(refreshRate, 0)).append("Hz");
        }
        hud.setText(sb.toString());

        if (cfg.dynamicColor && fps >= 0f) {
            float target = refreshRate > 1f ? refreshRate : 60f;
            float ratio = fps / target;
            if (ratio >= 0.92f) {
                hud.setTextColor(cfg.colorGood);
            } else if (ratio >= 0.72f) {
                hud.setTextColor(cfg.colorOk);
            } else {
                hud.setTextColor(cfg.colorBad);
            }
        } else {
            hud.setTextColor(cfg.textColor);
        }
        consecutiveFailures = 0;
    }

    /* ------------------------------------------------------------------ */
    /* window handling                                                     */
    /* ------------------------------------------------------------------ */

    private static void attach() {
        if (!cfg.enabled || hud != null) {
            return;
        }

        hudBackground = new GradientDrawable();
        hud = new TextView(viewContext);
        hud.setBackground(hudBackground);
        hud.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        hud.setIncludeFontPadding(false);
        hud.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        hud.setText("--");

        layout = new WindowManager.LayoutParams();
        layout.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layout.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layout.format = PixelFormat.TRANSLUCENT;
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        layout.setTitle("FpsHud");
        if (Build.VERSION.SDK_INT >= 30) {
            layout.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            layout.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applyConfig();

        // Preferred window type first, then progressively more permissive ones.
        int[] types = windowTypeChain(cfg.windowType);
        Throwable last = null;
        for (int i = 0; i < types.length; i++) {
            try {
                layout.type = types[i];
                windowManager.addView(hud, layout);
                attachedWindowType = types[i];
                log("window attached with type " + types[i]);
                return;
            } catch (Throwable t) {
                last = t;
                log("window type " + types[i] + " rejected: " + t);
                try {
                    windowManager.removeViewImmediate(hud);
                } catch (Throwable ignored) {
                }
            }
        }
        hud = null;
        throw new IllegalStateException("no usable window type", last instanceof Exception
                ? (Exception) last : new RuntimeException(String.valueOf(last)));
    }

    private static void detach() {
        if (hud == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(hud);
        } catch (Throwable ignored) {
        }
        hud = null;
        attachedWindowType = -1;
    }

    /** TYPE_SECURE_SYSTEM_OVERLAY / TYPE_SYSTEM_OVERLAY / TYPE_APPLICATION_OVERLAY. */
    private static int[] windowTypeChain(String preferred) {
        if ("overlay".equals(preferred)) {
            return new int[]{2038, 2015, 2006};
        }
        if ("system".equals(preferred)) {
            return new int[]{2006, 2015, 2038};
        }
        return new int[]{2015, 2006, 2038};
    }

    private static void applyConfig() {
        if (hud == null || layout == null) {
            return;
        }

        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.textSize);
        hud.setTextColor(cfg.textColor);
        int padH = dp(cfg.paddingH);
        int padV = dp(cfg.paddingV);
        hud.setPadding(padH, padV, padH, padV);
        hudBackground.setColor(cfg.bgColor);
        hudBackground.setCornerRadius(dp(cfg.bgRadius));
        hud.setAlpha(cfg.opacity);

        layout.gravity = gravityOf(cfg.position);
        layout.x = dp(cfg.x);
        layout.y = dp(cfg.y);

        if (attachedWindowType != -1 && attachedWindowType != windowTypeChain(cfg.windowType)[0]) {
            // window type can only change by recreating the window
            detach();
            attach();
            return;
        }

        try {
            if (hud.getWindowToken() != null || hud.isAttachedToWindow()) {
                windowManager.updateViewLayout(hud, layout);
            }
        } catch (Throwable t) {
            log("updateViewLayout failed: " + t);
        }
    }

    private static int gravityOf(String position) {
        int v = Gravity.TOP;
        int h = Gravity.START;
        if (position.startsWith("bottom")) {
            v = Gravity.BOTTOM;
        } else if (position.startsWith("middle")) {
            v = Gravity.CENTER_VERTICAL;
        }
        if (position.endsWith("right")) {
            h = Gravity.END;
        } else if (position.endsWith("center")) {
            h = Gravity.CENTER_HORIZONTAL;
        }
        return v | h;
    }

    private static int dp(float value) {
        float density = viewContext.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /* ------------------------------------------------------------------ */
    /* framework plumbing                                                  */
    /* ------------------------------------------------------------------ */

    /** ActivityThread.systemMain().getSystemContext() without compiling against hidden APIs. */
    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = null;
        try {
            Method systemMain = activityThread.getMethod("systemMain");
            thread = systemMain.invoke(null);
        } catch (Throwable t) {
            log("systemMain() unavailable (" + t + "), trying currentActivityThread()");
        }
        if (thread == null) {
            thread = activityThread.getMethod("currentActivityThread").invoke(null);
        }
        if (thread == null) {
            throw new IllegalStateException("could not obtain an ActivityThread");
        }
        Context ctx = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        if (ctx == null) {
            throw new IllegalStateException("system context was null");
        }
        return ctx;
    }

    /** Some ROMs enforce the hidden-API blacklist even for root processes. */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exempt = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exempt.setAccessible(true);
            exempt.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* FPS sources                                                         */
    /* ------------------------------------------------------------------ */

    private interface FpsSource {
        /** @return current fps, or a negative value when unavailable. */
        float read();

        String describe();
    }

    private static String sourceName() {
        return source == null ? "none" : source.describe();
    }

    private static void pickSource() {
        if ("vsync".equals(cfg.source)) {
            source = vsync;
            return;
        }
        if ("node".equals(cfg.source) && cfg.node.length() > 0) {
            SysfsSource s = new SysfsSource(new File(cfg.node));
            source = s.read() >= 0f ? s : vsync;
            return;
        }
        File node = probeNode();
        if (node != null) {
            source = new SysfsSource(node);
            log("using kernel fps node " + node.getAbsolutePath());
        } else {
            source = vsync;
            log("no kernel fps node found, using vsync counter");
        }
    }

    /** Kernel nodes that expose a measured panel frame rate, best first. */
    static List<File> candidateNodes() {
        List<File> out = new ArrayList<File>();
        // Qualcomm SDE/DPU (Snapdragon): /sys/class/drm/sde-crtc-N/measured_fps
        File drm = new File("/sys/class/drm");
        File[] entries = drm.listFiles();
        if (entries != null) {
            java.util.Arrays.sort(entries);
            for (int i = 0; i < entries.length; i++) {
                String name = entries[i].getName();
                if (name.startsWith("sde-crtc") || name.startsWith("crtc-")) {
                    out.add(new File(entries[i], "measured_fps"));
                }
            }
        }
        String[] fixed = {
                "/sys/class/graphics/fb0/measured_fps",
                "/sys/devices/virtual/graphics/fb0/measured_fps",
                "/sys/class/drm/card0/measured_fps",
                "/proc/mtkfb/fps",
                "/sys/kernel/debug/mtkfb/fps",
                "/sys/class/mtk_disp_mgr/fps",
                "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/measured_fps",
        };
        for (int i = 0; i < fixed.length; i++) {
            out.add(new File(fixed[i]));
        }
        return out;
    }

    private static File probeNode() {
        List<File> candidates = candidateNodes();
        for (int i = 0; i < candidates.size(); i++) {
            File f = candidates.get(i);
            if (f.exists() && f.canRead() && parse(readFile(f)) >= 0f) {
                return f;
            }
        }
        return null;
    }

    private static final class SysfsSource implements FpsSource {
        private final File file;

        SysfsSource(File file) {
            this.file = file;
        }

        @Override
        public float read() {
            return parse(readFile(file));
        }

        @Override
        public String describe() {
            return file.getAbsolutePath();
        }
    }

    /** Counts Choreographer vsync callbacks - works on literally any device. */
    private static final class VsyncSource implements FpsSource, Choreographer.FrameCallback {
        private final ArrayDeque<Long> frames = new ArrayDeque<Long>();

        void start() {
            try {
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable t) {
                log("choreographer unavailable: " + t);
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            frames.addLast(Long.valueOf(frameTimeNanos));
            long cutoff = frameTimeNanos - 1000000000L;
            while (!frames.isEmpty() && frames.peekFirst().longValue() < cutoff) {
                frames.removeFirst();
            }
            try {
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public float read() {
            if (frames.size() < 2) {
                return -1f;
            }
            long span = frames.peekLast().longValue() - frames.peekFirst().longValue();
            if (span <= 0L) {
                return -1f;
            }
            return (frames.size() - 1) * 1000000000f / span;
        }

        @Override
        public String describe() {
            return "vsync";
        }
    }

    private static final Pattern NUMBER = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    /** Kernel nodes print anything from "60" to "fps: 119.87 duration:1000 frame_count:120". */
    static float parse(String raw) {
        if (raw == null) {
            return -1f;
        }
        String line = raw.trim();
        if (line.length() == 0) {
            return -1f;
        }
        int nl = line.indexOf('\n');
        if (nl > 0) {
            line = line.substring(0, nl);
        }
        int marker = line.toLowerCase(Locale.US).indexOf("fps");
        if (marker >= 0) {
            line = line.substring(marker + 3);
        }
        Matcher m = NUMBER.matcher(line);
        if (!m.find()) {
            return -1f;
        }
        try {
            float value = Float.parseFloat(m.group(1));
            if (value < 0f || value > 1000f) {
                return -1f;
            }
            return value;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static String readFile(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    /* ------------------------------------------------------------------ */
    /* config                                                              */
    /* ------------------------------------------------------------------ */

    static final class Config {
        boolean enabled = true;
        String source = "auto";
        String node = "";
        int interval = 500;
        String position = "top_left";
        float x = 16f;
        float y = 16f;
        float textSize = 13f;
        int textColor = 0xFF00E676;
        int bgColor = 0x99000000;
        float bgRadius = 8f;
        float paddingH = 8f;
        float paddingV = 3f;
        float opacity = 1f;
        String label = "FPS";
        boolean showHz = false;
        int decimals = 0;
        boolean dynamicColor = false;
        int colorGood = 0xFF00E676;
        int colorOk = 0xFFFFC107;
        int colorBad = 0xFFFF5252;
        String windowType = "secure";
    }

    private static boolean reloadConfigIfChanged() {
        long stamp = CONFIG.exists() ? CONFIG.lastModified() : 0L;
        if (stamp == cfgStamp) {
            return false;
        }
        loadConfig();
        return true;
    }

    private static void loadConfig() {
        cfgStamp = CONFIG.exists() ? CONFIG.lastModified() : 0L;
        Config c = new Config();
        Properties p = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(CONFIG);
            p.load(new InputStreamReader(in, "UTF-8"));
        } catch (Throwable t) {
            log("config not readable (" + t + "), using defaults");
        } finally {
            close(in);
        }

        c.enabled = bool(p, "enabled", c.enabled);
        c.source = str(p, "source", c.source);
        c.node = str(p, "node", c.node);
        c.interval = clamp(integer(p, "interval", c.interval), 100, 5000);
        c.position = str(p, "position", c.position);
        c.x = number(p, "x", c.x);
        c.y = number(p, "y", c.y);
        c.textSize = clampF(number(p, "text_size", c.textSize), 6f, 48f);
        c.textColor = color(p, "text_color", c.textColor);
        c.bgColor = color(p, "bg_color", c.bgColor);
        c.bgRadius = clampF(number(p, "bg_radius", c.bgRadius), 0f, 40f);
        c.paddingH = clampF(number(p, "padding_h", c.paddingH), 0f, 40f);
        c.paddingV = clampF(number(p, "padding_v", c.paddingV), 0f, 40f);
        c.opacity = clampF(number(p, "opacity", c.opacity), 0.1f, 1f);
        c.label = str(p, "label", c.label);
        c.showHz = bool(p, "show_hz", c.showHz);
        c.decimals = clamp(integer(p, "decimals", c.decimals), 0, 2);
        c.dynamicColor = bool(p, "dynamic_color", c.dynamicColor);
        c.colorGood = color(p, "color_good", c.colorGood);
        c.colorOk = color(p, "color_ok", c.colorOk);
        c.colorBad = color(p, "color_bad", c.colorBad);
        c.windowType = str(p, "window_type", c.windowType);
        if ("none".equalsIgnoreCase(c.label)) {
            c.label = "";
        }
        cfg = c;
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return v.length() == 0 && !"label".equals(key) ? def : v;
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase(Locale.US);
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    private static int integer(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static float number(Properties p, String key, float def) {
        try {
            return Float.parseFloat(p.getProperty(key).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int color(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        if (v.length() == 0) {
            return def;
        }
        try {
            if (!v.startsWith("#")) {
                v = "#" + v;
            }
            if (v.length() == 7) {
                return Color.parseColor(v);
            }
            if (v.length() == 9) {
                long value = Long.parseLong(v.substring(1), 16);
                return (int) value;
            }
            return Color.parseColor(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String format(float value, int decimals) {
        if (decimals <= 0) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.US, "%." + decimals + "f", Float.valueOf(value));
    }

    /* ------------------------------------------------------------------ */
    /* misc                                                                */
    /* ------------------------------------------------------------------ */

    static void log(String message) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " " + message;
        System.out.println("FpsHud: " + line);
        Writer w = null;
        try {
            if (!DATA_DIR.exists()) {
                DATA_DIR.mkdirs();
            }
            boolean append = LOG.length() < LOG_MAX_BYTES;
            w = new OutputStreamWriter(new FileOutputStream(LOG, append), "UTF-8");
            w.write(line);
            w.write('\n');
        } catch (Throwable ignored) {
        } finally {
            close(w);
        }
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < trace.length && i < 8; i++) {
            sb.append("\n    at ").append(trace[i]);
        }
        if (t.getCause() != null && t.getCause() != t) {
            sb.append("\n  caused by ").append(t.getCause());
        }
        return sb.toString();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private FpsOverlay() {
    }
}
