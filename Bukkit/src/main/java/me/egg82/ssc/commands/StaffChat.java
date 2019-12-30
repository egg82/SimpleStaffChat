package me.egg82.ssc.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.annotation.*;
import co.aikar.taskchain.TaskChainFactory;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CommandAlias("chat|sc|ac|ao|admin|post")
public class StaffChat extends BaseCommand {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final TaskChainFactory taskFactory;

    public StaffChat(TaskChainFactory taskFactory) { this.taskFactory = taskFactory; }

    @Default
    @CommandPermission("ssc.use")
    @Description("{@@description.chat}")
    @Syntax("<level> [chat]")
    @CommandCompletion("@level")
    public void onChat(CommandIssuer issuer, String level, @Optional String chat) {

    }

    @CatchUnknown
    @Syntax("[command]")
    public void onHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }
}
