package dev.smallfish.hooklab.hook;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import dev.smallfish.hooklab.protocol.EventContract;

/**
 * Non-blocking event handoff. No ContentResolver IPC is performed on a hooked method's thread.
 * The bounded queue drops events under pressure rather than blocking the app.
 */
final class HookRelay {
    private static final String TAG = "HookLabM0";
    private static final int CAPACITY = 256;
    private static final ArrayBlockingQueue<ContentValues> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong DROPPED = new AtomicLong(0);
    private static final Uri URI = Uri.parse("content://" + EventContract.AUTHORITY + "/events");
    private static volatile Context context;

    private HookRelay() {}

    static void initialize(Context applicationContext) {
        context = applicationContext.getApplicationContext();
        if (STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(HookRelay::drain, "hooklab-event-writer");
            worker.setDaemon(true);
            worker.start();
        }
    }

    static void capture(String method, float arg0, int arg1, Float result, Throwable error,
                        long durationNs) {
        if (context == null || !EventContract.isAllowedMethod(method)) {
            DROPPED.incrementAndGet();
            return;
        }
        if (!EventContract.isFinite(arg0) || (result != null && !EventContract.isFinite(result))) {
            DROPPED.incrementAndGet();
            return;
        }
        ContentValues value = new ContentValues(11);
        value.put("method", method);
        value.put("arg0", arg0);
        value.put("arg1", arg1);
        if (result != null) value.put("result", result);
        value.put("outcome", error == null ? "ok" : "throw");
        value.put("exception", error == null ? "" :
                (error instanceof IllegalArgumentException ? "IllegalArgumentException" : "OtherException"));
        value.put("duration_ns", Math.max(0L, durationNs));
        value.put("when_ns", SystemClock.elapsedRealtimeNanos());
        value.put("pid", Process.myPid());
        value.put("tid", Process.myTid());
        if (!QUEUE.offer(value)) DROPPED.incrementAndGet();
    }

    private static void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ContentValues item = QUEUE.poll(1, TimeUnit.SECONDS);
                Context ctx = context;
                if (item != null && ctx != null) {
                    try {
                        ctx.getContentResolver().insert(URI, item);
                    } catch (SecurityException | IllegalArgumentException e) {
                        long skipped = DROPPED.incrementAndGet();
                        if (skipped == 1 || skipped % 100 == 0) {
                            Log.w(TAG, "Event receiver unavailable; check signature permissions and installation");
                        }
                    } catch (RuntimeException e) {
                        DROPPED.incrementAndGet();
                        Log.w(TAG, "Event delivery failed", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
