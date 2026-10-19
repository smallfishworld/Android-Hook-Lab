package dev.smallfish.hooklab.hook;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;
import dev.smallfish.hooklab.protocol.EventContract;

/**
 * Modern libxposed entry. M0 ONLY hooks deterministic methods in the included demo APK.
 * No third-party package browsing, credential collection or method rewriting.
 */
public final class DemoModule extends XposedModule {
    private static final String TAG = "HookLabM0";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!EventContract.TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        try {
            // Application.attach is called before the demo Activity runs, providing a valid Context.
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                Object value = chain.getArg(0);
                if (value instanceof Context) {
                    HookRelay.initialize((Context) value);
                }
                return result;
            });

            Class<?> processor = param.getClassLoader()
                .loadClass("dev.smallfish.hooklab.demo.ImageProcessor");
            Method brightness = processor.getDeclaredMethod("adjustBrightness", float.class, int.class);
            Method validate = processor.getDeclaredMethod("validateLevel", int.class);

            hook(brightness).intercept(chain -> {
                long start = SystemClock.elapsedRealtimeNanos();
                Object result = null;
                Throwable failure = null;
                try {
                    result = chain.proceed();
                    return result;
                } catch (Throwable t) {
                    failure = t;
                    throw t;
                } finally {
                    // Instrumentation must never change the app's method outcome.
                    try {
                        float arg0 = ((Number) chain.getArg(0)).floatValue();
                        int arg1 = ((Number) chain.getArg(1)).intValue();
                        Float numericResult = result instanceof Number
                                ? ((Number) result).floatValue() : null;
                        HookRelay.capture(EventContract.METHOD_BRIGHTNESS, arg0, arg1,
                                numericResult, failure, SystemClock.elapsedRealtimeNanos() - start);
                    } catch (Throwable ignored) {
                        // Do not leak instrumentation errors to the target.
                    }
                }
            });
            hook(validate).intercept(chain -> {
                long start = SystemClock.elapsedRealtimeNanos();
                Object result = null;
                Throwable failure = null;
                try {
                    result = chain.proceed();
                    return result;
                } catch (Throwable t) {
                    failure = t;
                    throw t;
                } finally {
                    try {
                        float level = ((Number) chain.getArg(0)).floatValue();
                        Float numericResult = result instanceof Number
                                ? ((Number) result).floatValue() : null;
                        HookRelay.capture(EventContract.METHOD_VALIDATE, level, 0,
                                numericResult, failure, SystemClock.elapsedRealtimeNanos() - start);
                    } catch (Throwable ignored) {
                        // Never modify a business exception or return value.
                    }
                }
            });
            log(Log.INFO, TAG, "Demo-only Java hooks installed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "Demo Hook initialization failed", e);
        }
    }
}
