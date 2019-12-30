package me.egg82.ssc.commands.internal;

import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.taskchain.TaskChain;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.services.StorageMessagingHandler;
import me.egg82.ssc.utils.ConfigurationFileUtil;
import org.bukkit.plugin.Plugin;

public class ReloadCommand implements Runnable {
    private final Plugin plugin;
    private final TaskChain<?> chain;
    private final CommandManager commandManager;
    private StorageMessagingHandler handler;
    private final CommandIssuer issuer;

    public ReloadCommand(Plugin plugin, TaskChain<?> chain, CommandManager commandManager, StorageMessagingHandler handler, CommandIssuer issuer) {
        this.plugin = plugin;
        this.chain = chain;
        this.commandManager = commandManager;
        this.handler = handler;
        this.issuer = issuer;
    }

    public void run() {
        issuer.sendInfo(Message.RELOAD__BEGIN);

        chain
                .async(() -> ConfigurationFileUtil.reloadConfig(plugin, commandManager, handler, handler))
                .sync(() -> issuer.sendInfo(Message.RELOAD__END))
                .execute();
    }
}
