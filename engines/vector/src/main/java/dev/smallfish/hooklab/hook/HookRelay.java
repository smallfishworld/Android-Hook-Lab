package dev.smallfish.hooklab.hook;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import dev.smallfish.hooklab.protocol.EventContract;
import dev.smallfish.hooklab.protocol.MobileControlContract;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-only device-native collector.
 *
 * Target Hook callbacks never make cross-UID Binder/ContentResolver calls. The
 * background writer polls capture policy every ~800 ms and reports a bounded,
 * sanitized reflection catalog. Turning capture off is safe without USB/ADB.
 */
final class HookRelay {
    private static final String TAG = "HookLabMobile";
    private static final int CAPACITY = 256;
    private static final ArrayBlockingQueue<ContentValues> QUEUE =
            new ArrayBlockingQueue<>(CAPACITY);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong DROPPED = new AtomicLong(0);
    private static final Uri EVENT_URI =
            Uri.parse("content://" + EventContract.AUTHORITY + "/events");
    private static final Uri CONTROL_URI =
            Uri.parse("content://" + MobileControlContract.AUTHORITY);

    private static volatile Context context;
    private static volatile boolean globalCapture = false; // fail closed until policy IPC succeeds
    private static volatile boolean floatBrightnessCapture = true;
    private static volatile boolean intBrightnessCapture = true;
    private static volatile boolean validateCapture = true;

    private HookRelay() {}

    static void initialize(Context applicationContext) {
        if (applicationContext == null) return;
        context = applicationContext.getApplicationContext();
        if (STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(HookRelay::drain, "hooklab-mobile-relay");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static boolean isEnabled(String method) {
        if (!globalCapture) return false;
        if (EventContract.METHOD_BRIGHTNESS.equals(method)) return floatBrightnessCapture;
        if (MobileControlContract.METHOD_BRIGHTNESS_INT.equals(method)) {
            return intBrightnessCapture;
        }
        return EventContract.METHOD_VALIDATE.equals(method) && validateCapture;
    }

    static void capture(String method, float arg0, int arg1,
                        Float result, Throwable error, long durationNs) {
        if (!isEnabled(method) || context == null) return;
        if (!MobileControlContract.isSupportedMethod(method)
                || !EventContract.isFinite(arg0)
                || (result != null && !EventContract.isFinite(result))) {
            DROPPED.incrementAndGet();
            return;
        }
        ContentValues item = new ContentValues(11);
        item.put("method", method);
        item.put("arg0", arg0);
        item.put("arg1", arg1);
        if (result != null) item.put("result", result);
        item.put("outcome", error == null ? "ok" : "throw");
        item.put("exception", error == null ? "" :
                (error instanceof IllegalArgumentException
                        ? "IllegalArgumentException" : "OtherException"));
        item.put("duration_ns", Math.max(0L, durationNs));
        item.put("when_ns", SystemClock.elapsedRealtimeNanos());
        item.put("pid", Process.myPid());
        item.put("tid", Process.myTid());
        if (!QUEUE.offer(item)) DROPPED.incrementAndGet();
    }

    private static void refreshPolicy(Context ctx) {
        try {
            Bundle flags = ctx.getContentResolver().call(CONTROL_URI,
                    MobileControlContract.READ_POLICY, null, null);
            if (flags == null) return;
            globalCapture = flags.getBoolean(MobileControlContract.KEY_CAPTURE_ENABLED, true);
            floatBrightnessCapture = flags.getBoolean(
                    MobileControlContract.METHOD_BRIGHTNESS_FLOAT, true);
            intBrightnessCapture = flags.getBoolean(
                    MobileControlContract.METHOD_BRIGHTNESS_INT, true);
            validateCapture = flags.getBoolean(MobileControlContract.METHOD_VALIDATE, true);
        } catch (RuntimeException failure) {
            // Fail closed when manager IPC becomes unavailable. No business method changes.
            globalCapture = false;
            Log.w(TAG, "Capture policy unavailable; recording suspended", failure);
        }
    }

    private static String simpleType(Class<?> type) {
        if (type.isArray()) return simpleType(type.getComponentType()) + "[]";
        return type.getSimpleName();
    }

    private static void sendCatalog(Context ctx) {
        try {
            Class<?> processor = ctx.getClassLoader()
                    .loadClass("dev.smallfish.hooklab.demo.ImageProcessor");
            ArrayList<String> members = new ArrayList<>();
            for (Method method : processor.getDeclaredMethods()) {
                StringBuilder item = new StringBuilder(processor.getName())
                        .append('#').append(method.getName()).append('(');
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (i > 0) item.append(',');
                    item.append(simpleType(types[i]));
                }
                item.append("):").append(simpleType(method.getReturnType()));
                if (MobileControlContract.isSafeCatalogEntry(item.toString())) {
                    members.add(item.toString());
                }
                if (members.size() >= 32) break;
            }
            Bundle request = new Bundle();
            request.putStringArrayList(MobileControlContract.KEY_CATALOG, members);
            ctx.getContentResolver().call(CONTROL_URI,
                    MobileControlContract.REPORT_CATALOG, null, request);
        } catch (Exception failure) {
            Log.w(TAG, "Demo method catalog unavailable", failure);
        }
    }

    private static void drain() {
        Context app = context;
        if (app != null) sendCatalog(app);
        long lastPolicyPollMs = Long.MIN_VALUE;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Context ctx = context;
                long now = SystemClock.elapsedRealtime();
                if (ctx != null && (lastPolicyPollMs == Long.MIN_VALUE ||
                        now - lastPolicyPollMs >= 800)) {
                    refreshPolicy(ctx);
                    lastPolicyPollMs = now;
                }
                ContentValues event = QUEUE.poll(160, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                if (!isEnabled(event.getAsString("method"))) continue; // no buffered output after rule disable
                if (ctx != null) {
                    try {
                        ctx.getContentResolver().insert(EVENT_URI, event);
                    } catch (RuntimeException failure) {
                        long skipped = DROPPED.incrementAndGet();
                        if (skipped == 1 || skipped % 100 == 0) {
                            Log.w(TAG, "Event sink unavailable (check APK signatures)");
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
