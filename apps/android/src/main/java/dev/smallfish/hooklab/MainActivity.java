package dev.smallfish.hooklab;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.smallfish.hooklab.protocol.EventContract;

/** Minimal functional M0 UI. Displays only verified events from the signed demo app. */
public final class MainActivity extends Activity {
    private static final Uri EVENTS = Uri.parse("content://" + EventContract.AUTHORITY + "/events");
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            mainThread.postDelayed(this, 1000);
        }
    };
    private TextView info;
    private TextView events;
    private boolean visible;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 38, 30, 24);

        TextView title = new TextView(this);
        title.setText("Android Hook Lab · M0");
        title.setTextSize(24);
        root.addView(title);

        info = new TextView(this);
        info.setPadding(0, 10, 0, 16);
        info.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT +
                     ") · " + Build.SUPPORTED_ABIS[0] +
                     "\nHook status: unverified until a real Demo event arrives." +
                     "\nActivate this module in Vector; scope only to Hook Lab Demo. Restart Demo.");
        root.addView(info);

        Button launch = new Button(this);
        launch.setText("Open Hook Lab Demo");
        launch.setOnClickListener(view -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(EventContract.TARGET_PACKAGE);
            if (intent == null) {
                info.append("\nInstall demo-app-debug.apk first.");
                return;
            }
            startActivity(intent);
        });
        root.addView(launch);

        Button clear = new Button(this);
        clear.setText("Clear stored demo events");
        clear.setOnClickListener(view -> databaseExecutor.execute(() -> {
            getContentResolver().delete(EVENTS, null, null);
            runOnUiThread(this::refresh);
        }));
        root.addView(clear);

        TextView subtitle = new TextView(this);
        subtitle.setText("Recent intercepted calls · newest first");
        subtitle.setPadding(0, 15, 0, 8);
        subtitle.setTextSize(17);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        events = new TextView(this);
        events.setTextSize(13);
        events.setText("No events yet.\n\nOnly real Vector events from the demo are shown.");
        scroll.addView(events);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        databaseExecutor.execute(() -> {
            StringBuilder text = new StringBuilder();
            int count = 0;
            try (Cursor cursor = getContentResolver().query(EVENTS, null, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("Provider returned no cursor");
                int methodCol = cursor.getColumnIndexOrThrow("method");
                int arg0Col = cursor.getColumnIndexOrThrow("arg0");
                int arg1Col = cursor.getColumnIndexOrThrow("arg1");
                int resultCol = cursor.getColumnIndexOrThrow("result");
                int outcomeCol = cursor.getColumnIndexOrThrow("outcome");
                int exceptionCol = cursor.getColumnIndexOrThrow("exception");
                int durationCol = cursor.getColumnIndexOrThrow("duration_ns");
                int pidCol = cursor.getColumnIndexOrThrow("pid");
                int tidCol = cursor.getColumnIndexOrThrow("tid");
                while (cursor.moveToNext()) {
                    count++;
                    text.append("#").append(count).append(" · ")
                        .append(cursor.getString(methodCol)).append("\n")
                        .append("pid ").append(cursor.getInt(pidCol))
                        .append(" / tid ").append(cursor.getInt(tidCol))
                        .append(" · ").append(String.format(Locale.ROOT, "%.3f ms",
                           cursor.getLong(durationCol) / 1_000_000.0)).append("\n")
                        .append("args: ").append(cursor.getFloat(arg0Col))
                        .append(", ").append(cursor.getInt(arg1Col)).append("  → ");
                    if ("throw".equals(cursor.getString(outcomeCol))) {
                        text.append("throws ").append(cursor.getString(exceptionCol));
                    } else {
                        text.append(cursor.isNull(resultCol) ? "(void)" : cursor.getFloat(resultCol));
                    }
                    text.append("\n\n");
                }
            } catch (Exception e) {
                text.setLength(0);
                text.append("Read failed: ").append(e.getClass().getSimpleName());
            }
            if (count == 0 && text.length() == 0) {
                text.append("No events yet.\n\n1. Install both debug APKs built together.\n")
                    .append("2. Enable Hook Lab in Vector; scope only Demo.\n")
                    .append("3. Force-stop and restart Demo.\n4. Press the demo buttons.");
            }
            String display = text.toString();
            int visibleCount = count;
            runOnUiThread(() -> {
                if (visible) {
                    events.setText(display);
                    if (visibleCount > 0) {
                        info.setText("Captured " + visibleCount + " recent real Demo events. " +
                                     "Provider permission: signature + caller validation.");
                    }
                }
                loading.set(false);
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        poll.run();
    }

    @Override protected void onPause() {
        visible = false;
        mainThread.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        mainThread.removeCallbacks(poll);
        databaseExecutor.shutdownNow();
        super.onDestroy();
    }
}
