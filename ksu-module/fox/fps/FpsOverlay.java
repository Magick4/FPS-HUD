package fox.fps;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class FpsOverlay {
    public static void main(String[] args) {
        Looper.prepare();
        try {
            // Grab the system context directly from ActivityThread
            ActivityThread thread = ActivityThread.systemMain();
            Context context = thread.getSystemContext();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

            TextView tv = new TextView(context);
            tv.setText("FPS: --");
            tv.setTextColor(Color.GREEN);
            tv.setTextSize(18f);
            tv.setBackgroundColor(Color.argb(150, 0, 0, 0));
            tv.setPadding(15, 10, 15, 10);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    2015, // TYPE_SECURE_SYSTEM_OVERLAY - bypasses draw-over-apps permission
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = 50;
            params.y = 50;

            wm.addView(tv, params);

            Handler handler = new Handler();
            Runnable updateFps = new Runnable() {
                @Override
                public void run() {
                    tv.setText("FPS: " + getFps());
                    handler.postDelayed(this, 500);
                }
            };
            handler.post(updateFps);

        } catch (Exception e) {
            e.printStackTrace();
        }
        Looper.loop();
    }

    private static String getFps() {
        try {
            // Standard Qualcomm DRM sysfs node. 
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cat /sys/class/drm/sde-crtc-*/measured_fps | head -n 1"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim().split(" ")[0];
            }
        } catch (Exception e) {}
        return "N/A";
    }
}
