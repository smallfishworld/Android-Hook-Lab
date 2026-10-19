package dev.smallfish.hooklab.storage;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import java.util.Arrays;
import dev.smallfish.hooklab.protocol.EventContract;

/**
 * Only same-signed Demo APK may insert; only owner UID may read/delete.
 * No generic query selection/projection from other apps and no SQL built from external strings.
 */
public final class EventProvider extends ContentProvider {
    private static final int EVENTS = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(EventContract.AUTHORITY, "events", EVENTS); }
    private EventDatabase database;
    private int insertCounter;

    @Override public boolean onCreate() {
        database = new EventDatabase(requireContext());
        return true;
    }

    private Context requireContext() {
        Context ctx = getContext();
        if (ctx == null) throw new IllegalStateException("No provider context");
        return ctx;
    }

    private static void verifyUri(Uri uri) {
        if (MATCHER.match(uri) != EVENTS) throw new IllegalArgumentException("Unsupported URI");
    }

    private void verifyWriter() {
        int callingUid = Binder.getCallingUid();
        String[] packages = requireContext().getPackageManager().getPackagesForUid(callingUid);
        if (packages == null || !Arrays.asList(packages).contains(EventContract.TARGET_PACKAGE)) {
            throw new SecurityException("Only signed Hook Lab Demo can submit events");
        }
        String callingPackage = getCallingPackage();
        if (callingPackage != null && !EventContract.TARGET_PACKAGE.equals(callingPackage)) {
            throw new SecurityException("Package identity mismatch");
        }
    }

    private void verifyOwner() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Event history is private to Hook Lab");
        }
    }

    @Override public Uri insert(Uri uri, ContentValues incoming) {
        verifyUri(uri);
        verifyWriter();
        if (incoming == null) throw new IllegalArgumentException("Missing event");
        String method = incoming.getAsString("method");
        String outcome = incoming.getAsString("outcome");
        String exception = incoming.getAsString("exception");
        Float arg0 = incoming.getAsFloat("arg0");
        Integer arg1 = incoming.getAsInteger("arg1");
        Float value = incoming.getAsFloat("result");
        Long duration = incoming.getAsLong("duration_ns");
        Long when = incoming.getAsLong("when_ns");
        Integer pid = incoming.getAsInteger("pid");
        Integer tid = incoming.getAsInteger("tid");
        if (!EventContract.isAllowedMethod(method) || !EventContract.isAllowedOutcome(outcome)
                || !EventContract.isAllowedException(exception)
                || arg0 == null || !EventContract.isFinite(arg0)
                || (value != null && !EventContract.isFinite(value))
                || arg1 == null || Math.abs((long) arg1) > 1000
                || duration == null || duration < 0
                || when == null || when < 0 || pid == null || pid <= 0 || tid == null || tid <= 0) {
            throw new IllegalArgumentException("Invalid or unsafe event");
        }
        ContentValues safe = new ContentValues(10);
        safe.put("method", method);
        safe.put("outcome", outcome);
        safe.put("exception", exception == null ? "" : exception);
        safe.put("arg0", arg0);
        safe.put("arg1", arg1);
        if (value != null) safe.put("result", value);
        safe.put("duration_ns", duration);
        safe.put("when_ns", when);
        safe.put("pid", pid);
        safe.put("tid", tid);
        SQLiteDatabase db = database.getWritableDatabase();
        long id = db.insertOrThrow("events", null, safe);
        if (++insertCounter % 64 == 0) {
            db.execSQL("DELETE FROM events WHERE _id NOT IN " +
                "(SELECT _id FROM events ORDER BY _id DESC LIMIT 1000)");
        }
        requireContext().getContentResolver().notifyChange(uri, null);
        return ContentUris.withAppendedId(uri, id);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        verifyUri(uri);
        verifyOwner();
        // Ignore external projection/where/sort to avoid arbitrary SQL or sensitive data exposure.
        Cursor cursor = database.getReadableDatabase().query("events",
            new String[]{"_id", "method", "arg0", "arg1", "result",
                         "outcome", "exception", "duration_ns", "pid", "tid"},
            null, null, null, null, "_id DESC", "80");
        cursor.setNotificationUri(requireContext().getContentResolver(), uri);
        return cursor;
    }

    @Override public int delete(Uri uri, String where, String[] args) {
        verifyUri(uri);
        verifyOwner();
        int count = database.getWritableDatabase().delete("events", null, null);
        requireContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException("Events are append-only");
    }

    @Override public String getType(Uri uri) {
        verifyUri(uri);
        return "vnd.android.cursor.dir/vnd." + EventContract.AUTHORITY + ".event";
    }

    private static final class EventDatabase extends SQLiteOpenHelper {
        EventDatabase(Context ctx) { super(ctx, "events-m0.db", null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE events (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "method TEXT NOT NULL, arg0 REAL NOT NULL, arg1 INTEGER NOT NULL," +
                "result REAL, outcome TEXT NOT NULL, exception TEXT NOT NULL," +
                "duration_ns INTEGER NOT NULL, when_ns INTEGER NOT NULL," +
                "pid INTEGER NOT NULL, tid INTEGER NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("No schema migration defined");
        }
    }
}
