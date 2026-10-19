package dev.smallfish.hooklab.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class EventContractTest {
    @Test public void onlyWhitelistedMethodsAreAllowed() {
        assertTrue(EventContract.isAllowedMethod(EventContract.METHOD_BRIGHTNESS));
        assertFalse(EventContract.isAllowedMethod("com.example.SecretReader.read"));
        assertFalse(EventContract.isAllowedMethod(null));
    }
    @Test public void nonFiniteValuesRejected() {
        assertFalse(EventContract.isFinite(Float.NaN));
        assertFalse(EventContract.isFinite(Float.POSITIVE_INFINITY));
        assertTrue(EventContract.isFinite(0.75f));
    }
    @Test public void exceptionIsTypeOnly() {
        assertFalse(EventContract.isAllowedException("password=12345"));
        assertTrue(EventContract.isAllowedException("IllegalArgumentException"));
    }
}
