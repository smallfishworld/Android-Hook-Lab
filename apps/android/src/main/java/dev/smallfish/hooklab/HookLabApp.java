package dev.smallfish.hooklab;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Collections;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import dev.smallfish.hooklab.protocol.RuleWire;

/** Framework-mediated policy storage and user-approved scope: no su shell needed. */
public final class HookLabApp extends Application {
    private static volatile XposedService service;
    private static final String TAG = "HookLab";
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService connected) { service = connected; }
            @Override public void onServiceDied(XposedService dead) {
                if (service == dead) service = null;
            }
        });
    }
    public static boolean connected() { return service != null; }
    public static XposedService requireService() {
        XposedService value = service;
        if (value == null) throw new IllegalStateException(
            "Vector service unavailable. Enable this module in Vector and reopen Hook Lab.");
        return value;
    }
    public static SharedPreferences remoteRules() {
        return requireService().getRemotePreferences(RuleWire.PREF_GROUP);
    }
}
