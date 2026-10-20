package dev.smallfish.hooklab.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import dalvik.system.DexFile;
import dev.smallfish.hooklab.protocol.RuleWire;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Scoped Java Hook adapter. Only operates inside user-approved Vector scopes,
 * with an explicit package-specific selected_target and exact method rules.
 */
public final class HookLabModule extends XposedModule {
    private static final String TAG = "HookLab";
    private static final Uri TRACE = Uri.parse("content://" + RuleWire.EVENT_AUTHORITY);
    private static final int QUEUE_LIMIT = 256;

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || !RuleWire.packageName(param.getPackageName())
                || param.getPackageName().equals("dev.smallfish.hooklab")
                || (param.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;
        final String pkg = param.getPackageName();
        final ClassLoader loader = param.getClassLoader();
        final String[] paths = getDexPaths(param.getApplicationInfo());
        final ContextHolder holder = new ContextHolder();

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                Object value = chain.getArg(0);
                if (value instanceof Context) holder.initialize((Context) value,
                        pkg, paths, loader, this);
                return result;
            });
        } catch (Throwable failure) {
            log(Log.WARN, TAG, "Cannot initialize scoped app session", failure);
        }
    }

    private static String[] getDexPaths(ApplicationInfo info) {
        ArrayList<String> paths = new ArrayList<>();
        if (info.sourceDir != null) paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) if (paths.size() < 12) paths.add(path);
        }
        return paths.toArray(new String[0]);
    }

    private static final class ContextHolder {
        private boolean started;
        synchronized void initialize(Context ctx, String pkg, String[] paths,
                ClassLoader loader, HookLabModule module) {
            if (started) return;
            started = true;
            Thread worker = new Thread(new Controller(ctx.getApplicationContext(),
                    pkg, paths, loader, module), "hooklab-control");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static final class Controller implements Runnable {
        private final Context ctx;
        private final String pkg;
        private final String[] apkPaths;
        private final ClassLoader loader;
        private final HookLabModule module;
        private final ArrayBlockingQueue<Bundle> queue =
                new ArrayBlockingQueue<>(QUEUE_LIMIT);
        private final Map<String, HookHandle> hooks = new HashMap<>();
        private String lastRules = null;
        private String lastBrowse = null;
        private SharedPreferences policy;
        private int dropped;
        private long lastPoll;

        Controller(Context ctx, String pkg, String[] apkPaths,
                   ClassLoader loader, HookLabModule module) {
            this.ctx=ctx;
            this.pkg=pkg;
            this.apkPaths=apkPaths;
            this.loader=loader;
            this.module=module;
        }

        @Override public void run() {
            sendCatalog();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long now=SystemClock.elapsedRealtime();
                    if (now-lastPoll >= 900 || lastPoll == 0) {
                        lastPoll=now;
                        poll();
                    }
                    Bundle event=queue.poll(150, TimeUnit.MILLISECONDS);
                    if (event != null && !hooks.isEmpty()) {
                        ctx.getContentResolver().call(TRACE, "event", null, event);
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } catch (Throwable failure) {
                    module.log(Log.WARN,TAG,"Event/control channel unavailable; pausing",failure);
                    unhookAll();
                    lastRules=null;
                    try { Thread.sleep(1000); } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            unhookAll();
        }

        private void poll() {
            if (policy == null) policy=module.getRemotePreferences(RuleWire.PREF_GROUP);
            if (!pkg.equals(policy.getString(RuleWire.SELECTED_TARGET, ""))) {
                unhookAll();
                lastRules=null;
                return;
            }
            Bundle heartbeat=new Bundle();
            heartbeat.putString("package",pkg);
            ctx.getContentResolver().call(TRACE,"heartbeat",null,heartbeat);
            String wire=policy.getString(RuleWire.RULES_PREFIX + pkg, "");
            if (!wire.equals(lastRules)) {
                List<String> requested=RuleWire.decode(pkg,wire);
                Set<String> wanted=new HashSet<>(requested);
                ArrayList<String> removed=new ArrayList<>();
                for (String id : hooks.keySet()) {
                    if (!wanted.contains(id)) removed.add(id);
                }
                for (String id:removed) {
                    HookHandle handle=hooks.remove(id);
                    if (handle != null) handle.unhook();
                }
                for (String item:requested) {
                    if (hooks.containsKey(item)) continue;
                    try {
                        HookHandle handle=register(item);
                        if (handle != null) hooks.put(item,handle);
                    } catch (Throwable failure) {
                        module.log(Log.WARN,TAG,"Method not hookable: "+item,failure);
                    }
                }
                lastRules=wire;
            }
            String requestedClass=policy.getString(RuleWire.BROWSE_PREFIX + pkg, "");
            if (!requestedClass.equals(lastBrowse)) {
                lastBrowse=requestedClass;
                if (RuleWire.classForTarget(pkg,requestedClass)) sendMethods(requestedClass);
            }
        }

        private HookHandle register(String entry) throws Exception {
            int hash=entry.indexOf('#');
            int open=entry.indexOf('(',hash);
            String cls=entry.substring(0,hash);
            String method=entry.substring(hash+1,open);
            String args=entry.substring(open+1,entry.length()-1);
            Class<?> type=Class.forName(cls,false,loader);
            String[] argTypes=args.isEmpty()?new String[0]:args.split(",");
            Class<?>[] parameterTypes=new Class<?>[argTypes.length];
            for(int i=0;i<argTypes.length;i++) parameterTypes[i]=typeFor(argTypes[i]);
            Method target=type.getDeclaredMethod(method,parameterTypes);
            return module.hook(target).intercept(chain -> {
                long start=SystemClock.elapsedRealtimeNanos();
                Object result=null;
                Throwable failure=null;
                try {
                    result=chain.proceed();
                    return result;
                } catch(Throwable thrown) {
                    failure=thrown;
                    throw thrown;
                } finally {
                    try {
                        Bundle event=new Bundle();
                        event.putString("package",pkg);
                        event.putString("method",entry);
                        event.putString("args",summarizeArgs(chain.getArgs()));
                        event.putString("result",failure != null
                                ? safeType(failure.getClass().getName())
                                : summarize(result));
                        event.putString("outcome",failure==null?"ok":"throw");
                        event.putLong("duration",Math.max(0,
                                SystemClock.elapsedRealtimeNanos()-start));
                        event.putLong("at",start);
                        event.putInt("tid",Process.myTid());
                        if (!queue.offer(event)) dropped++;
                    } catch(Throwable ignored) {
                        // Instrumentation must never replace app method result or exception.
                    }
                }
            });
        }

        private Class<?> typeFor(String name) throws ClassNotFoundException {
            switch(name) {
                case "void":return void.class;
                case "boolean":return boolean.class;
                case "byte":return byte.class;
                case "short":return short.class;
                case "int":return int.class;
                case "long":return long.class;
                case "float":return float.class;
                case "double":return double.class;
                case "char":return char.class;
                default:
                    if (name.endsWith("[]")) return Array.newInstance(
                        typeFor(name.substring(0,name.length()-2)),0).getClass();
                    return Class.forName(name,false,loader);
            }
        }
        private static String safeType(String name) {
            return name==null?"unknown":name.replaceAll("[^a-zA-Z0-9._$]","_")
                    .substring(0,Math.min(name.length(),100));
        }
        private static String summarize(Object value) {
            if (value==null) return "null";
            if (value instanceof Number || value instanceof Boolean ||
                    value instanceof Character) {
                return "primitive="+value;
            }
            if (value instanceof String) return "string_len="+((String)value).length();
            return "type="+safeType(value.getClass().getName());
        }
        private static String summarizeArgs(List<Object> args) {
            StringBuilder b=new StringBuilder();
            for(int i=0;i<Math.min(8,args.size());i++) {
                if (b.length()>0) b.append("; ");
                b.append('a').append(i).append('=').append(summarize(args.get(i)));
            }
            if(args.size()>8) b.append("; truncated");
            return b.substring(0,Math.min(200,b.length()));
        }
        private void sendCatalog() {
            ArrayList<String> names=new ArrayList<>();
            for(String path:apkPaths) {
                try {
                    DexFile dex=new DexFile(path);
                    try {
                        Enumeration<String> entries=dex.entries();
                        while(entries.hasMoreElements() && names.size()<RuleWire.MAX_CATALOG) {
                            String name=entries.nextElement();
                            if (RuleWire.classForTarget(pkg,name)) names.add(name);
                        }
                    } finally {dex.close();}
                } catch(Throwable ignored) { /* Dex metadata may be protected/unavailable. */ }
                if(names.size()>=RuleWire.MAX_CATALOG)break;
            }
            try {
                Bundle data=new Bundle();
                data.putString("package",pkg);
                data.putStringArrayList("items",names);
                ctx.getContentResolver().call(TRACE,"catalog",null,data);
            } catch(Throwable ex) {module.log(Log.WARN,TAG,"Class catalog reporting failed",ex);}
        }
        private void sendMethods(String className) {
            try {
                Class<?> cls=Class.forName(className,false,loader);
                ArrayList<String> names=new ArrayList<>();
                for(Method method:cls.getDeclaredMethods()) {
                    if(method.isSynthetic() || names.size()>=RuleWire.MAX_CATALOG)continue;
                    StringBuilder signature=new StringBuilder(className).append('#')
                            .append(method.getName()).append('(');
                    Class<?>[] parameters=method.getParameterTypes();
                    for(int i=0;i<parameters.length;i++) {
                        if(i>0)signature.append(',');
                        signature.append(parameters[i].getTypeName());
                    }
                    signature.append(')');
                    String rule=signature.toString();
                    if (RuleWire.valid(pkg,rule)) names.add(rule);
                }
                Bundle data=new Bundle();
                data.putString("package",pkg);
                data.putString("class",className);
                data.putStringArrayList("items",names);
                ctx.getContentResolver().call(TRACE,"methods",null,data);
            } catch(Throwable e) {
                module.log(Log.WARN,TAG,"Method metadata unavailable: "+className,e);
            }
        }
        private void unhookAll() {
            for(HookHandle hook:hooks.values()) {
                try {hook.unhook();} catch(Throwable ignored){}
            }
            hooks.clear();
            queue.clear();
        }
    }
}
