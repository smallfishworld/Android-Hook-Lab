package dev.smallfish.hooklab.protocol;

/**
 * Trusted same-signature phone-side control channel.
 * All supported targets are explicit: a developer can extend this contract only
 * after adding an instrumented and tested adapter for the selected method.
 */
public final class MobileControlContract {
    private MobileControlContract() {}

    public static final String AUTHORITY = "dev.smallfish.hooklab.control";
    public static final String READ_POLICY = "read_policy";
    public static final String UPDATE_POLICY = "update_policy";
    public static final String REPORT_CATALOG = "report_catalog";
    public static final String READ_CATALOG = "read_catalog";
    public static final String REPORT_STATUS = "report_status";
    public static final String KEY_METHOD_ID = "method_id";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CAPTURE_ENABLED = "capture_enabled";
    public static final String KEY_REPORTED_AT_MS = "reported_at_ms";
    public static final String KEY_CATALOG = "catalog";

    public static final String METHOD_BRIGHTNESS_FLOAT = EventContract.METHOD_BRIGHTNESS;
    public static final String METHOD_BRIGHTNESS_INT = "ImageProcessor.adjustBrightness(int,int)";
    public static final String METHOD_VALIDATE = EventContract.METHOD_VALIDATE;
    public static final String GLOBAL = "__global_capture__";

    public static final String[] SUPPORTED = {
        METHOD_BRIGHTNESS_FLOAT, METHOD_BRIGHTNESS_INT, METHOD_VALIDATE
    };

    public static boolean isSupportedMethod(String method) {
        if (method == null) return false;
        for (String candidate : SUPPORTED) {
            if (candidate.equals(method)) return true;
        }
        return false;
    }

    public static boolean isRule(String key) {
        return GLOBAL.equals(key) || isSupportedMethod(key);
    }

    public static boolean isSafeCatalogEntry(String entry) {
        if (entry == null || entry.length() < 10 || entry.length() > 240) return false;
        return entry.matches(
            "dev\\.smallfish\\.hooklab\\.demo\\.ImageProcessor#[A-Za-z_$][A-Za-z0-9_$]*\\([A-Za-z0-9_,.\\[\\] ]*\\):[A-Za-z0-9_.\\[\\]]+");
    }
}
