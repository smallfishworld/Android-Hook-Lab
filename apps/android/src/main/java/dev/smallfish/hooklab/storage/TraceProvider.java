package dev.smallfish.hooklab.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import dev.smallfish.hooklab.protocol.RuleWire;

/**
 * Local target-to-UI observation channel. Caller must be the one explicit package
 * selected by the user. No secrets/raw objects, no publicly readable event history.
 *
 * A target may forge data attributed to its own UID; this is a diagnostic channel,
 * not a cryptographic attestation of the injected Xposed module.
 */
public final class TraceProvider extends ContentProvider {
    private static final String PREF_NAME = "hooklab.ui.v2";
    private static final int EVENTS = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(RuleWire.EVENT_AUTHORITY, "events", EVENTS); }
    private TraceDb database;
    private SharedPreferences preferences;
    private int inserts;

    @Override public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        database = new TraceDb(ctx);
        preferences = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return true;
    }
    public static String uiPrefs() { return PREF_NAME; }
    private void owner() {
        if (Binder.getCallingUid() != Process.myUid())
            throw new SecurityException("Only Hook Lab can inspect trace history");
    }
    private String selected() {
        return preferences.getString("selected", "");
    }
    private void target(Bundle data) {
        String pkg = selected();
        if (!RuleWire.packageName(pkg) || data == null ||
                !pkg.equals(data.getString("package"))) {
            throw new SecurityException("Target not authorized");
        }
        String[] caller = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
        if (caller == null || !Arrays.asList(caller).contains(pkg))
            throw new SecurityException("Unexpected target UID");
        String declared = getCallingPackage();
        if (declared != null && !declared.equals(pkg))
            throw new SecurityException("Unexpected calling package");
    }
    private static ArrayList<String> validateList(ArrayList<String> list, String pkg, boolean methods) {
        if (list == null || list.size() > RuleWire.MAX_CATALOG) {
            throw new IllegalArgumentException("Catalog too large");
        }
        Set<String> unique = new HashSet<>();
        for (String item : list) {
            if (item == null || item.length() > 290 || item.length() == 0)
                throw new IllegalArgumentException("Bad catalog entry");
            if (methods) {
                if (!RuleWire.valid(pkg, item)) throw new IllegalArgumentException("Bad method entry");
            } else if (!RuleWire.classForTarget(pkg, item)) {
                throw new IllegalArgumentException("Bad class entry");
            }
            unique.add(item);
        }
        ArrayList<String> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("catalog".equals(method) || "methods".equals(method)) {
            target(extras);
            String pkg = selected();
            ArrayList<String> items = extras.getStringArrayList("items");
            if ("methods".equals(method)) {
                String cls = extras.getString("class", "");
                if (!RuleWire.classForTarget(pkg, cls)) throw new IllegalArgumentException("Bad class");
            }
            ArrayList<String> validated = validateList(items, pkg, "methods".equals(method));
            String key = "methods".equals(method) ? "methods" : "classes";
            // Data is read only by our own UID. Bound total is enforced at IPC boundary.
            preferences.edit().putString(key, String.join("\n", validated)).apply();
            if ("methods".equals(method)) preferences.edit().putString("method_class", extras.getString("class")).apply();
            preferences.edit().putLong("heartbeat", SystemClock.elapsedRealtime()).apply();
            Bundle result = new Bundle();
            result.putInt("count", validated.size());
            return result;
        }
        if ("event".equals(method)) {
            target(extras);
            String pkg = selected();
            String signature = extras.getString("method", "");
            String args = extras.getString("args", "");
            String result = extras.getString("result", "");
            String outcome = extras.getString("outcome", "");
            long duration = extras.getLong("duration", -1);
            if (!RuleWire.valid(pkg, signature)
                    || args.length() > 200 || result.length() > 120
                    || !args.matches("[a-zA-Z0-9 .,:_\\[\\]{};=<>|+?-]*")
                    || !result.matches("[a-zA-Z0-9 .,:_\\[\\]{};=<>|+?-]*")
                    || !("ok".equals(outcome) || "throw".equals(outcome))
                    || duration < 0 || duration > 120_000_000_000L) {
                throw new IllegalArgumentException("Unsafe event");
            }
            ContentValues row = new ContentValues(10);
            row.put("package", pkg);
            row.put("method", signature);
            row.put("args", args);
            row.put("result", result);
            row.put("outcome", outcome);
            row.put("duration_ns", duration);
            row.put("pid", Binder.getCallingPid());
            row.put("tid", extras.getInt("tid", 0));
            row.put("at_ns", extras.getLong("at", 0));
            SQLiteDatabase db = database.getWritableDatabase();
            db.insertOrThrow("events", null, row);
            if (++inserts % 64 == 0) {
                db.execSQL("DELETE FROM events WHERE id NOT IN "+
                    "(SELECT id FROM events ORDER BY id DESC LIMIT 1500)");
            }
            return Bundle.EMPTY;
        }
        if ("snapshot".equals(method)) {
            owner();
            Bundle data = new Bundle();
            data.putString("classes", preferences.getString("classes", ""));
            data.putString("methods", preferences.getString("methods", ""));
            data.putString("method_class", preferences.getString("method_class", ""));
            data.putLong("heartbeat", preferences.getLong("heartbeat", -1L));
            return data;
        }
        if ("clear".equals(method)) {
            owner();
            database.getWritableDatabase().delete("events", null, null);
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("Unknown provider method");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) {
        owner();
        if (MATCHER.match(uri) != EVENTS) throw new IllegalArgumentException("Invalid URI");
        return database.getReadableDatabase().query("events",
            new String[]{"id","package","method","args","result","outcome","duration_ns","pid","tid","at_ns"},
            "package=?", new String[]{selected()}, null,null,"id DESC","250");
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use scoped call API");
    }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException("Use scoped call API");
    }
    @Override public int delete(Uri uri, String where, String[] args) {
        throw new UnsupportedOperationException("Use scoped call API");
    }
    @Override public String getType(Uri uri) { return null; }

    private static final class TraceDb extends SQLiteOpenHelper {
        TraceDb(Context c) { super(c, "hooklab-trace-v2.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "package TEXT NOT NULL,method TEXT NOT NULL,args TEXT NOT NULL," +
                "result TEXT NOT NULL,outcome TEXT NOT NULL,duration_ns INTEGER NOT NULL," +
                "pid INTEGER NOT NULL,tid INTEGER NOT NULL,at_ns INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
            throw new IllegalStateException("Unsupported schema version");
        }
    }
}
