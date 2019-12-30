package me.egg82.ssc.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.commands.annotation.*;
import co.aikar.taskchain.TaskChainFactory;
import me.egg82.ssc.commands.internal.ImportCommand;
import me.egg82.ssc.commands.internal.ReloadCommand;
import me.egg82.ssc.commands.internal.SetLevelCommand;
import me.egg82.ssc.services.StorageMessagingHandler;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CommandAlias("simplestaffchat|ssc")
public class SimpleStaffChatCommand extends BaseCommand {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Plugin plugin;
    private final TaskChainFactory taskFactory;
    private final CommandManager commandManager;

    public SimpleStaffChatCommand(Plugin plugin, TaskChainFactory taskFactory, CommandManager commandManager) {
        this.plugin = plugin;
        this.taskFactory = taskFactory;
        this.commandManager = commandManager;
    }

    @Subcommand("reload")
    @CommandPermission("ssc.admin")
    @Description("{@@description.reload}")
    public void onReload(CommandIssuer issuer) {
        StorageMessagingHandler handler;
        try {
            handler = ServiceLocator.get(StorageMessagingHandler.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }
        new ReloadCommand(plugin, taskFactory.newChain(), commandManager, handler, issuer).run();
    }

    @Subcommand("import")
    @CommandPermission("ssc.admin")
    @Description("{@@description.import}")
    @Syntax("<master> <slave> [batchSize]")
    @CommandCompletion("@storage @storage @nothing")
    public void onImport(CommandIssuer issuer, String master, String slave, @Default("50") String batchSize) {
        new ImportCommand(issuer, master, slave, batchSize, taskFactory.newChain()).run();
    }

    @Subcommand("level|addlevel|setlevel")
    @CommandPermission("ssc.admin")
    @Description("{@@description.level}")
    @Syntax("<level> <name>")
    @CommandCompletion("@level @nothing")
    public void onLevel(CommandIssuer issuer, String level, String name) {
        new SetLevelCommand(issuer, level, name, taskFactory.newChain()).run();
    }

    @CatchUnknown @Default
    @CommandCompletion("@subcommand")
    public void onDefault(CommandSender sender, String[] args) {
        Bukkit.getServer().dispatchCommand(sender, "simplestaffchat help");
    }

    @HelpCommand
    @Syntax("[command]")
    public void onHelp(CommandSender sender, CommandHelp help) { help.showHelp(); }
}
