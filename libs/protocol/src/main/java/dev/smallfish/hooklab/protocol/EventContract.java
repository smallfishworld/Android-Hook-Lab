package dev.smallfish.hooklab.protocol;

/** Cross-APK event contract. Names and accepted methods are intentionally hard-coded for M0. */
public final class EventContract {
    private EventContract() {}

    public static final String AUTHORITY = "dev.smallfish.hooklab.events";
    public static final String TARGET_PACKAGE = "dev.smallfish.hooklab.demo";
    public static final String METHOD_BRIGHTNESS = "ImageProcessor.adjustBrightness(float,int)";
    public static final String METHOD_VALIDATE = "ImageProcessor.validateLevel(int)";

    public static boolean isAllowedMethod(String method) {
        return METHOD_BRIGHTNESS.equals(method) || METHOD_VALIDATE.equals(method)
            || MobileControlContract.METHOD_BRIGHTNESS_INT.equals(method);
    }

    public static boolean isAllowedOutcome(String outcome) {
        return "ok".equals(outcome) || "throw".equals(outcome);
    }

    public static boolean isFinite(float number) {
        return !Float.isNaN(number) && !Float.isInfinite(number);
    }

    public static boolean isAllowedException(String name) {
        return name == null || name.isEmpty()
            || "IllegalArgumentException".equals(name) || "OtherException".equals(name);
    }
}
