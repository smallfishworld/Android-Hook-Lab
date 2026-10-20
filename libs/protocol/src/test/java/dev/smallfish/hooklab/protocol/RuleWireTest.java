package dev.smallfish.hooklab.protocol;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuleWireTest {
    private static final String PKG = "com.example.owner";
    @Test public void validExactOverload() {
        String entry = "com.example.owner.Worker#process(int,java.lang.String)";
        assertTrue(RuleWire.valid(PKG, entry));
        assertEquals(entry, RuleWire.encode(PKG, Arrays.asList(entry)));
        assertEquals(1, RuleWire.decode(PKG, entry).size());
    }
    @Test public void noCrossPackage() {
        assertFalse(RuleWire.valid(PKG, "com.bank.secrets.Auth#read()"));
    }
    @Test public void noWildcardOrCommand() {
        assertFalse(RuleWire.valid(PKG, "com.example.owner.*#read()"));
        assertFalse(RuleWire.valid(PKG, "com.example.owner.Worker#run(;ls)"));
        assertFalse(RuleWire.valid(PKG, "com.example.owner.Worker#*()"));
    }
    @Test(expected=IllegalArgumentException.class)
    public void duplicateRejected() {
        RuleWire.decode(PKG, "com.example.owner.Worker#go()\ncom.example.owner.Worker#go()");
    }
    @Test(expected=IllegalArgumentException.class)
    public void overflowRejected() {
        String[] a = new String[13];
        Arrays.fill(a, "com.example.owner.Worker#go()");
        RuleWire.decode(PKG, String.join("\n", a));
    }
}
