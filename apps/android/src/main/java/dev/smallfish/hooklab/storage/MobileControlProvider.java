package dev.smallfish.hooklab.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.smallfish.hooklab.protocol.EventContract;
import dev.smallfish.hooklab.protocol.MobileControlContract;

/**
 * Narrow, authenticated, offline control plane for project-owned Demo process.
 *
 * - Hook Lab UID can read and update capture flags.
 * - Same-signed Demo UID can read flags and report a sanitized method catalog.
 * - No shell, Java method invocation, arbitrary package selection or network access.
 * - This toggles instrumentation capture; installed Vector interceptors remain in place
 *   until the target process terminates.
 */
public final class MobileControlProvider extends ContentProvider {
    private static final String PREFS = "device-hook-policy-v1";
    private static final String LAST_POLL = "last_demo_poll_elapsed_ms";
    private static final String CATALOG = "catalog_v1";

    private SharedPreferences prefs;

    @Override public boolean onCreate() {
        Context app = getContext();
        if (app == null) return false;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return true;
    }

    private void requireOwner() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Only Hook Lab may change its own capture rules");
        }
    }

    private boolean isDemoCaller() {
        String[] packages = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
        return packages != null && Arrays.asList(packages)
                .contains(EventContract.TARGET_PACKAGE);
    }

    private void requireDemo() {
        if (!isDemoCaller()) {
            throw new SecurityException("Only signed Hook Lab Demo may communicate as target");
        }
    }

    private void requireOwnerOrDemo() {
        if (Binder.getCallingUid() != Process.myUid() && !isDemoCaller()) {
            throw new SecurityException("Untrusted on-device control caller");
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (MobileControlContract.READ_POLICY.equals(method)) {
            requireOwnerOrDemo();
            if (isDemoCaller()) {
                prefs.edit().putLong(LAST_POLL, SystemClock.elapsedRealtime()).apply();
            }
            Bundle output = new Bundle();
            output.putBoolean(MobileControlContract.KEY_CAPTURE_ENABLED,
                    prefs.getBoolean(MobileControlContract.GLOBAL, true));
            for (String name : MobileControlContract.SUPPORTED) {
                output.putBoolean(name, prefs.getBoolean(name, true));
            }
            output.putLong(MobileControlContract.KEY_REPORTED_AT_MS,
                    prefs.getLong(LAST_POLL, -1L));
            return output;
        }
        if (MobileControlContract.UPDATE_POLICY.equals(method)) {
            requireOwner();
            if (extras == null) throw new IllegalArgumentException("Missing policy update");
            String target = extras.getString(MobileControlContract.KEY_METHOD_ID);
            if (!MobileControlContract.isRule(target)
                    || !extras.containsKey(MobileControlContract.KEY_ENABLED)) {
                throw new IllegalArgumentException("Unsupported capture rule");
            }
            boolean enabled = extras.getBoolean(MobileControlContract.KEY_ENABLED);
            prefs.edit().putBoolean(target, enabled).apply();
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            return result;
        }
        if (MobileControlContract.REPORT_CATALOG.equals(method)) {
            requireDemo();
            ArrayList<String> requested = extras == null ? null
                    : extras.getStringArrayList(MobileControlContract.KEY_CATALOG);
            if (requested == null || requested.size() > 32) {
                throw new IllegalArgumentException("Expected at most 32 Demo methods");
            }
            Set<String> safe = new HashSet<>();
            for (String entry : requested) {
                if (!MobileControlContract.isSafeCatalogEntry(entry)) {
                    throw new IllegalArgumentException("Unexpected method descriptor");
                }
                safe.add(entry);
            }
            prefs.edit().putStringSet(CATALOG, safe).apply();
            Bundle output = new Bundle();
            output.putInt("count", safe.size());
            return output;
        }
        if (MobileControlContract.READ_CATALOG.equals(method)) {
            requireOwner();
            List<String> sorted = new ArrayList<>(
                    prefs.getStringSet(CATALOG, Collections.emptySet()));
            Collections.sort(sorted);
            Bundle result = new Bundle();
            result.putStringArrayList(MobileControlContract.KEY_CATALOG,
                    new ArrayList<>(sorted));
            return result;
        }
        throw new IllegalArgumentException("Unknown control method");
    }

    // Only the explicit call() API is supported by this provider.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        throw new UnsupportedOperationException("Use authenticated control calls");
    }
    @Override public Uri insert(Uri u, ContentValues v) {
        throw new UnsupportedOperationException("Use authenticated control calls");
    }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException("Use authenticated control calls");
    }
    @Override public int delete(Uri u, String s, String[] a) {
        throw new UnsupportedOperationException("Use authenticated control calls");
    }
    @Override public String getType(Uri u) {
        return null;
    }
}
