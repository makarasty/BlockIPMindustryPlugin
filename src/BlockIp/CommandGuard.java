package BlockIp;

import arc.util.CommandHandler;
import arc.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * Refuses commands from some callers. Arc runs a command straight from the chat packet, with no
 * hook between the chat event and the command and without the chat filter, so the only way in is
 * each command's runner, which Arc keeps in a package-private final field. Wrapping it in place
 * keeps the command's name, order and help.
 *
 * <p>Each command is wrapped once, and the wrapper holds a list of gates: guarding a command again
 * adds a gate to it instead of a wrapper around the wrapper. A plugin that registers a command
 * again (Essentials does on reload) replaces the wrapped one with a bare one, which the next call
 * wraps.
 */
final class CommandGuard {
    private static final Field RUNNER;

    static {
        Field field = null;
        try {
            field = CommandHandler.Command.class.getDeclaredField("runner");
            field.setAccessible(true);
        } catch (Exception e) {
            Log.err("BlockIp: cannot reach command runners, so commands cannot be guarded", e);
        }
        RUNNER = field;
    }

    /**
     * Asked before the command runs; returning false refuses it, and the gate tells the caller why.
     */
    @FunctionalInterface
    interface Gate {
        boolean allows(String command, String[] args, Object caller);
    }

    private CommandGuard() {}

    /**
     * Puts {@code gate} on every command of {@code handler} whose name matches {@code names}, once:
     * calling it again only reaches commands registered since. Returns false when the commands
     * could not be reached.
     */
    @SuppressWarnings("unchecked")
    static boolean guard(CommandHandler handler, Predicate<String> names, Gate gate) {
        if (RUNNER == null) return false;
        try {
            for (CommandHandler.Command command : handler.getCommandList()) {
                if (!names.test(command.text)) continue;
                Object runner = RUNNER.get(command);
                if (runner instanceof Guarded guarded) {
                    if (!guarded.gates.contains(gate)) guarded.gates.add(gate);
                } else {
                    Guarded guarded =
                            new Guarded(
                                    command.text, (CommandHandler.CommandRunner<Object>) runner);
                    guarded.gates.add(gate);
                    RUNNER.set(command, guarded);
                }
            }
            return true;
        } catch (Exception e) {
            Log.err("BlockIp: could not guard commands", e);
            return false;
        }
    }

    private static final class Guarded implements CommandHandler.CommandRunner<Object> {
        final String name;
        final CommandHandler.CommandRunner<Object> inner;
        final ArrayList<Gate> gates = new ArrayList<>(2);

        Guarded(String name, CommandHandler.CommandRunner<Object> inner) {
            this.name = name;
            this.inner = inner;
        }

        @Override
        public void accept(String[] args, Object caller) {
            for (int i = 0; i < gates.size(); i++) {
                if (!gates.get(i).allows(name, args, caller)) return;
            }
            inner.accept(args, caller);
        }
    }
}
