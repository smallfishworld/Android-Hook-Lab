package dev.smallfish.hooklab.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import dev.smallfish.hooklab.protocol.MobileControlContract;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/**
 * M2 mobile-first Vector module for project-owned Demo App.
 * Methods are intercepted once in-process; Android Hook Lab UI changes *capture*
 * without USB/ADB. No hidden access to third-party application data.
 */
public final class DemoModule extends XposedModule {
    private static final String TAG = "HookLabMobile";

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!"dev.smallfish.hooklab.demo".equals(param.getPackageName())
                || !param.isFirstPackage()) return;
        try {
            // Covers both early and slightly later libxposed module initialization.
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof Context) {
                    HookRelay.initialize((Context) chain.getArg(0));
                }
                return result;
            });
            Method createApp = Instrumentation.class
                    .getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(createApp).intercept(chain -> {
                Object outcome = chain.proceed();
                if (chain.getArg(0) instanceof Application) {
                    HookRelay.initialize((Application) chain.getArg(0));
                }
                return outcome;
            });

            Class<?> processor = param.getClassLoader()
                    .loadClass("dev.smallfish.hooklab.demo.ImageProcessor");
            Method brightnessFloat =
                    processor.getDeclaredMethod("adjustBrightness", float.class, int.class);
            Method brightnessInt =
                    processor.getDeclaredMethod("adjustBrightness", int.class, int.class);
            Method validate = processor.getDeclaredMethod("validateLevel", int.class);

            hook(brightnessFloat).intercept(chain -> {
                long start = SystemClock.elapsedRealtimeNanos();
                Object result = null;
                Throwable failure = null;
                try {
                    result = chain.proceed();
                    return result;
                } catch (Throwable error) {
                    failure = error;
                    throw error;
                } finally {
                    try {
                        HookRelay.capture(MobileControlContract.METHOD_BRIGHTNESS_FLOAT,
                                ((Number) chain.getArg(0)).floatValue(),
                                ((Number) chain.getArg(1)).intValue(),
                                result instanceof Number
                                        ? ((Number) result).floatValue() : null,
                                failure, SystemClock.elapsedRealtimeNanos() - start);
                    } catch (Throwable ignored) {}
                }
            });

            hook(brightnessInt).intercept(chain -> {
                long start = SystemClock.elapsedRealtimeNanos();
                Object result = null;
                Throwable failure = null;
                try {
                    result = chain.proceed();
                    return result;
                } catch (Throwable error) {
                    failure = error;
                    throw error;
                } finally {
                    try {
                        HookRelay.capture(MobileControlContract.METHOD_BRIGHTNESS_INT,
                                ((Number) chain.getArg(0)).floatValue(),
                                ((Number) chain.getArg(1)).intValue(),
                                result instanceof Number
                                        ? ((Number) result).floatValue() : null,
                                failure, SystemClock.elapsedRealtimeNanos() - start);
                    } catch (Throwable ignored) {}
                }
            });

            hook(validate).intercept(chain -> {
                long start = SystemClock.elapsedRealtimeNanos();
                Object result = null;
                Throwable failure = null;
                try {
                    result = chain.proceed();
                    return result;
                } catch (Throwable error) {
                    failure = error;
                    throw error;
                } finally {
                    try {
                        HookRelay.capture(MobileControlContract.METHOD_VALIDATE,
                                ((Number) chain.getArg(0)).floatValue(), 0,
                                result instanceof Number
                                        ? ((Number) result).floatValue() : null,
                                failure, SystemClock.elapsedRealtimeNanos() - start);
                    } catch (Throwable ignored) {}
                }
            });
            log(Log.INFO, TAG, "Registered three demo-only Java probes");
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, TAG, "Hook initialization failed", error);
        }
    }
}
