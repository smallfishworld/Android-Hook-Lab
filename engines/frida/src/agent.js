/*
 * Android Hook Lab / M2 Frida Java agent.
 * Explicit method hooks only; metadata-first and no implicit object stringify.
 * Frida 17 requires importing and bundling frida-java-bridge.
 */
import Java from 'frida-java-bridge';

const installed = new Map();
let nextHookId = 1;
let nextCallId = 1;
let session = 'unconfigured';
let prefix = '';
let captureStack = false;
let perSecondLimit = 200;
let secondWindow = 0;
let eventCount = 0;
let dropped = 0;
const parentCalls = new Map();

function requirePrefix(className) {
  if (!prefix || !className.startsWith(prefix + '.')) {
    throw new Error('Class is outside the explicit authorized package prefix');
  }
}

function inJava(callback) {
  return new Promise((resolve, reject) => {
    if (!Java.available) {
      reject(new Error('Java VM unavailable'));
      return;
    }
    try {
      Java.perform(() => {
        try { resolve(callback()); }
        catch (e) { reject(e); }
      });
    } catch (e) { reject(e); }
  });
}

function safeValue(value, typeHint) {
  if (value === null || value === undefined) return { type: 'null' };
  // Never stringify arbitrary Java objects or inspect their fields.
  const kind = typeof value;
  if (kind === 'boolean') return { type: 'boolean', value };
  if (kind === 'number' && Number.isFinite(value)) {
    return { type: 'number', value };
  }
  if (kind === 'string') return { type: 'string', length: value.length };
  return { type: String(typeHint || 'object').slice(0, 120), redacted: true };
}

function takeStack() {
  if (!captureStack) return [];
  try {
    return Java.backtrace({ limit: 16 }).frames
      .filter(f => String(f.className || '').startsWith(prefix + '.'))
      .slice(0, 12)
      .map(f => ({
        class_name: String(f.className).slice(0, 180),
        method: String(f.methodName).slice(0, 120)
      }));
  } catch (_) { return []; }
}

function emit(event) {
  const currentSecond = Math.floor(Date.now() / 1000);
  if (secondWindow !== currentSecond) {
    secondWindow = currentSecond;
    eventCount = 0;
  }
  if (eventCount >= perSecondLimit) {
    dropped++;
    return;
  }
  eventCount++;
  event.dropped_total = dropped;
  send({ kind: 'hook_event', event });
}

function detachAll() {
  for (const item of installed.values()) {
    try { item.overload.implementation = null; } catch (_) {}
  }
  installed.clear();
  parentCalls.clear();
}

rpc.exports = {
  configure(options) {
    if (!options || typeof options.package_prefix !== 'string' ||
        !/^[a-zA-Z_][\w]*(\.[a-zA-Z_][\w]*)+$/.test(options.package_prefix)) {
      throw new Error('Expected explicit valid package_prefix');
    }
    if (installed.size) throw new Error('Unhook before reconfiguring');
    prefix = options.package_prefix;
    session = String(options.session_id || 'session').slice(0, 64);
    captureStack = options.capture_stack === true;
    perSecondLimit = Math.min(1000, Math.max(1, Number(options.max_events_per_sec) || 200));
    dropped = 0;
    return { ok: true, package_prefix: prefix, stack_enabled: captureStack, max_events_per_sec: perSecondLimit };
  },
  discover(term, limit) {
    return inJava(() => {
      if (!prefix) throw new Error('Not configured');
      const search = String(term || '').toLowerCase().slice(0, 80);
      const max = Math.min(200, Math.max(1, Number(limit) || 50));
      return Java.enumerateLoadedClassesSync()
        .filter(name => name.startsWith(prefix + '.') &&
           name.toLowerCase().includes(search))
        .sort().slice(0, max);
    });
  },
  methods(className) {
    return inJava(() => {
      requirePrefix(className);
      const cls = Java.use(className);
      const out = [];
      const candidates = cls.class.getDeclaredMethods();
      for (let i = 0; i < Math.min(candidates.length, 200); i++) {
        const method = candidates[i];
        const name = String(method.getName());
        if (!name || name.startsWith('access$')) continue;
        const family = cls[name];
        if (family && family.overloads) {
          for (const over of family.overloads) {
            const types = over.argumentTypes.map(t => t.className);
            const item = { name, arguments: types, returns: over.returnType.className };
            if (!out.some(x => x.name === name && JSON.stringify(x.arguments) === JSON.stringify(types))) {
              out.push(item);
            }
          }
        }
      }
      return out.sort((a,b) => a.name.localeCompare(b.name)).slice(0, 200);
    });
  },
  install(className, methodName, argumentTypes) {
    return inJava(() => {
      requirePrefix(className);
      if (!/^[\w$]+$/.test(methodName)) throw new Error('Invalid method');
      if (!Array.isArray(argumentTypes) || argumentTypes.length > 16) {
        throw new Error('Explicit argument type list required');
      }
      if (installed.size >= 16) throw new Error('Maximum 16 hooks per session');
      const key = className + '#' + methodName + '(' + argumentTypes.join(',') + ')';
      if ([...installed.values()].some(x => x.key === key)) throw new Error('Already installed');
      const cls = Java.use(className);
      if (!cls[methodName]) throw new Error('Method not present');
      const over = cls[methodName].overload.apply(cls[methodName], argumentTypes);
      const hookId = 'F' + nextHookId++;
      const returnType = over.returnType.className;
      over.implementation = function () {
        const args = Array.prototype.slice.call(arguments);
        const threadId = Process.getCurrentThreadId();
        const ancestors = parentCalls.get(threadId) || [];
        const callId = 'c' + nextCallId++;
        const parentCallId = ancestors.length ? ancestors[ancestors.length - 1] : null;
        const startNs = Number(Java.use('android.os.SystemClock').elapsedRealtimeNanos());
        const stack = takeStack();
        ancestors.push(callId);
        parentCalls.set(threadId, ancestors);
        let outcome = 'ok', result, exceptionType = '';
        try {
          result = over.call.apply(over, [this].concat(args));
          return result;
        } catch (e) {
          outcome = 'throw';
          // Do not capture exception messages or stack containing secrets.
          exceptionType = (e && e.$className) ? String(e.$className).slice(0, 120) : 'Exception';
          throw e;
        } finally {
          const duration = Math.max(0, Number(Java.use('android.os.SystemClock').elapsedRealtimeNanos()) - startNs);
          ancestors.pop();
          if (!ancestors.length) parentCalls.delete(threadId);
          try {
            emit({
              schema_version: 1, engine: 'FRIDA', session_id: session,
              call_id: callId, parent_call_id: parentCallId, hook_id: hookId,
              method: key, package_name: prefix, pid: Process.id, tid: threadId,
              monotonic_time_ns: startNs, duration_ns: duration,
              outcome, exception_type: exceptionType,
              args: args.map((v, index) => safeValue(v, argumentTypes[index])),
              result: outcome === 'ok' ? safeValue(result, returnType) : null,
              stack
            });
          } catch (_) { dropped++; }
        }
      };
      installed.set(hookId, { key, overload: over });
      return { id: hookId, method: key };
    });
  },
  remove(id) {
    return inJava(() => {
      const item = installed.get(id);
      if (!item) return false;
      item.overload.implementation = null;
      installed.delete(id);
      return true;
    });
  },
  status() {
    return { engine: 'FRIDA', session_id: session,
      hooks: [...installed.entries()].map(([id, x]) => ({ id, method: x.key })),
      dropped, max_events_per_sec: perSecondLimit, capture_stack: captureStack };
  },
  stop() { return inJava(() => { detachAll(); return true; }); }
};
