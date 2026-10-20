package dev.smallfish.hooklab.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class MobileControlContractTest {
    @Test public void onlyConfiguredProbesCanBeChanged() {
        assertTrue(MobileControlContract.isRule(MobileControlContract.GLOBAL));
        assertTrue(MobileControlContract.isRule(MobileControlContract.METHOD_BRIGHTNESS_INT));
        assertTrue(MobileControlContract.isRule(MobileControlContract.METHOD_BRIGHTNESS_FLOAT));
        assertTrue(MobileControlContract.isRule(MobileControlContract.METHOD_VALIDATE));
        assertFalse(MobileControlContract.isRule("android.os.SystemProperties#get"));
        assertFalse(MobileControlContract.isRule(null));
    }

    @Test public void validatesReflectedMethodNamesWithoutSensitiveContent() {
        assertTrue(MobileControlContract.isSafeCatalogEntry(
            "dev.smallfish.hooklab.demo.ImageProcessor#adjustBrightness(float,int):float"));
        assertTrue(MobileControlContract.isSafeCatalogEntry(
            "dev.smallfish.hooklab.demo.ImageProcessor#adjustBrightness(int,int):int"));
        assertFalse(MobileControlContract.isSafeCatalogEntry(
            "com.someone.private.App#readPassword():String"));
        assertFalse(MobileControlContract.isSafeCatalogEntry(
            "dev.smallfish.hooklab.demo.ImageProcessor#inject(hello;rm -rf):void"));
    }

    @Test public void thirdMethodAllowedByEventSchema() {
        assertTrue(EventContract.isAllowedMethod(
            MobileControlContract.METHOD_BRIGHTNESS_INT));
        assertFalse(EventContract.isAllowedMethod("OtherClass.privateMethod()"));
    }
}
