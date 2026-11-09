package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * The method Essentials' Electorate finds by name: its signature is a contract with another plugin.
 */
class ElectorateHookTest {
    @Test
    void essentialsFindsTheHookAsItLooksItUp() throws Exception {
        Method hook =
                Class.forName("BlockIp.BlockPlugin")
                        .getMethod("voteRefusal", String.class, boolean.class, boolean.class);
        assertTrue(
                Modifier.isStatic(hook.getModifiers()) && Modifier.isPublic(hook.getModifiers()));
        assertEquals(String.class, hook.getReturnType());
    }

    @Test
    void beforeInitEveryoneMayVote() {
        assertNull(
                BlockPlugin.voteRefusal("uuid", true, true),
                "not loaded yet: Essentials' own rules decide");
    }
}
