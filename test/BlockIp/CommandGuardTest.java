package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import arc.util.CommandHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandGuardTest {
    @Test
    void aRefusedCallerDoesNotRunTheCommandAndOthersStillDo() {
        CommandHandler handler = new CommandHandler("/");
        List<String> ran = new ArrayList<>();
        List<Object> refused = new ArrayList<>();
        handler.<String>register(
                "vote", "<target>", "vote", (args, caller) -> ran.add(caller + ":" + args[0]));
        CommandGuard.Gate gate =
                (command, args, caller) -> {
                    if (!"bot".equals(caller)) return true;
                    refused.add(caller);
                    return false;
                };

        assertTrue(CommandGuard.guard(handler, name -> true, gate));
        // Twice must not wrap twice: one refusal per refused call
        assertTrue(CommandGuard.guard(handler, name -> true, gate));

        handler.handleMessage("/vote alice", "bot");
        handler.handleMessage("/vote bob", "player");
        assertEquals(List.of("player:bob"), ran);
        assertEquals(List.of("bot"), refused);
    }

    @Test
    void commandsRegisteredLaterAreCaughtByTheNextGuard() {
        CommandHandler handler = new CommandHandler("/");
        List<Object> refused = new ArrayList<>();
        CommandGuard.Gate gate = (command, args, caller) -> !refused.add(caller);
        CommandGuard.guard(handler, name -> true, gate);
        List<String> ran = new ArrayList<>();
        handler.<String>register("rtv", "rtv", (args, caller) -> ran.add("rtv"));

        CommandGuard.guard(handler, name -> true, gate);
        handler.handleMessage("/rtv", "bot");
        assertEquals(List.of(), ran);
        assertEquals(1, refused.size());
        assertEquals("rtv", handler.getCommandList().first().text);
    }

    @Test
    void gatesComposePerCommandAndSurviveARegistration() {
        CommandHandler handler = new CommandHandler("/");
        List<String> ran = new ArrayList<>();
        handler.<String>register(
                "votekick", "<p>", "", (args, caller) -> ran.add("votekick:" + caller));
        handler.<String>register("help", "", (args, caller) -> ran.add("help:" + caller));
        List<String> asked = new ArrayList<>();
        CommandGuard.Gate everything =
                (command, args, caller) -> asked.add("all:" + command) && !"pending".equals(caller);
        CommandGuard.Gate votes =
                (command, args, caller) -> asked.add("vote:" + command) && !"newbie".equals(caller);

        CommandGuard.guard(handler, name -> true, everything);
        CommandGuard.guard(handler, "votekick"::equals, votes);
        CommandGuard.guard(handler, "votekick"::equals, votes);

        handler.handleMessage("/votekick x", "newbie");
        handler.handleMessage("/help", "newbie");
        handler.handleMessage("/votekick x", "pending");
        handler.handleMessage("/votekick x", "regular");
        assertEquals(List.of("help:newbie", "votekick:regular"), ran);
        // In the order they were put on, the second not asked once the first refused
        assertEquals(
                List.of(
                        "all:votekick",
                        "vote:votekick",
                        "all:help",
                        "all:votekick",
                        "all:votekick",
                        "vote:votekick"),
                asked);

        // A plugin registering the command again (Essentials on reload) drops the gates; the next
        // guard puts them back
        handler.<String>register(
                "votekick", "<p>", "", (args, caller) -> ran.add("again:" + caller));
        handler.handleMessage("/votekick x", "newbie");
        CommandGuard.guard(handler, "votekick"::equals, votes);
        handler.handleMessage("/votekick x", "newbie");
        assertEquals(List.of("help:newbie", "votekick:regular", "again:newbie"), ran);
    }
}
