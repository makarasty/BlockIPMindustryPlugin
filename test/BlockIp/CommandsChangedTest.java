package BlockIp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import arc.util.CommandHandler;
import org.junit.jupiter.api.Test;

/**
 * A command line guards the commands again only when a register or a removal may have left one
 * bare.
 */
class CommandsChangedTest {
    @Test
    void anyRegisterOrRemovalIsSeenAndNothingElseIs() {
        BlockPlugin plugin = new BlockPlugin();
        ConfigManager.ConfigData config = new ConfigManager.ConfigData();
        CommandHandler handler = new CommandHandler("/");
        handler.register("rtv", "", (args) -> {});
        handler.register("votemap", "<id>", (args) -> {});
        assertTrue(plugin.commandsChanged(handler.getCommandList(), config), "never guarded yet");

        plugin.sawCommands(handler.getCommandList(), config);
        assertFalse(plugin.commandsChanged(handler.getCommandList(), config));

        // Essentials' reload registers the same command again, bare: same size, new last element
        handler.register("rtv", "", (args) -> {});
        assertTrue(plugin.commandsChanged(handler.getCommandList(), config));
        plugin.sawCommands(handler.getCommandList(), config);

        handler.removeCommand("votemap");
        assertTrue(plugin.commandsChanged(handler.getCommandList(), config));
        plugin.sawCommands(handler.getCommandList(), config);

        // A config reload hands a new object, whose vote list may name other commands
        assertTrue(
                plugin.commandsChanged(handler.getCommandList(), new ConfigManager.ConfigData()));
    }
}
