package BlockIp;

import arc.util.CommandHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGuardTest {
    @Test
    void aRefusedCallerDoesNotRunTheCommandAndOthersStillDo() {
        CommandHandler handler = new CommandHandler("/");
        List<String> ran = new ArrayList<>();
        List<Object> refused = new ArrayList<>();
        handler.<String>register("vote", "<target>", "vote", (args, caller) -> ran.add(caller + ":" + args[0]));

        assertTrue(CommandGuard.guard(handler, caller -> "bot".equals(caller), refused::add));
        // Twice must not wrap twice: one refusal per refused call
        assertTrue(CommandGuard.guard(handler, caller -> "bot".equals(caller), refused::add));

        handler.handleMessage("/vote alice", "bot");
        handler.handleMessage("/vote bob", "player");
        assertEquals(List.of("player:bob"), ran);
        assertEquals(List.of("bot"), refused);
    }

    @Test
    void commandsRegisteredLaterAreCaughtByTheNextGuard() {
        CommandHandler handler = new CommandHandler("/");
        List<Object> refused = new ArrayList<>();
        CommandGuard.guard(handler, caller -> true, refused::add);
        List<String> ran = new ArrayList<>();
        handler.<String>register("rtv", "rtv", (args, caller) -> ran.add("rtv"));

        CommandGuard.guard(handler, caller -> true, refused::add);
        handler.handleMessage("/rtv", "bot");
        assertEquals(List.of(), ran);
        assertEquals(1, refused.size());
        assertEquals("rtv", handler.getCommandList().first().text);
    }
}
