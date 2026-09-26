package BlockIp;

import arc.util.CommandHandler;
import arc.util.Log;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Refuses commands from some callers. Arc runs a command straight from the chat packet, with no hook
 * between the chat event and the command and without the chat filter, so the only way in is each
 * command's runner, which Arc keeps in a package-private final field. Wrapping it in place keeps the
 * command's name, order and help.
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

    private CommandGuard() {
    }

    /**
     * Wraps every command of {@code handler} not wrapped yet, so calling it again only catches the ones
     * registered since. A command whose caller matches {@code refuse} is not run; {@code onRefused} gets
     * the caller instead. Returns false when the commands could not be reached.
     */
    @SuppressWarnings("unchecked")
    static boolean guard(CommandHandler handler, Predicate<Object> refuse, Consumer<Object> onRefused) {
        if (RUNNER == null) return false;
        try {
            for (CommandHandler.Command command : handler.getCommandList()) {
                Object runner = RUNNER.get(command);
                if (!(runner instanceof Guarded)) {
                    RUNNER.set(command, new Guarded((CommandHandler.CommandRunner<Object>) runner, refuse, onRefused));
                }
            }
            return true;
        } catch (Exception e) {
            Log.err("BlockIp: could not guard commands", e);
            return false;
        }
    }

    private static final class Guarded implements CommandHandler.CommandRunner<Object> {
        final CommandHandler.CommandRunner<Object> inner;
        final Predicate<Object> refuse;
        final Consumer<Object> onRefused;

        Guarded(CommandHandler.CommandRunner<Object> inner, Predicate<Object> refuse, Consumer<Object> onRefused) {
            this.inner = inner;
            this.refuse = refuse;
            this.onRefused = onRefused;
        }

        @Override
        public void accept(String[] args, Object caller) {
            if (refuse.test(caller)) {
                onRefused.accept(caller);
                return;
            }
            inner.accept(args, caller);
        }
    }
}
