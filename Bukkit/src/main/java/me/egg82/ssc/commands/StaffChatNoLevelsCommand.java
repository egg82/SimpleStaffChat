package me.egg82.ssc.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.annotation.*;
import co.aikar.taskchain.TaskChainFactory;
import java.util.UUID;
import me.egg82.ssc.APIException;
import me.egg82.ssc.StaffChatAPI;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.services.StorageMessagingHandler;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CommandAlias("chat|sc|ac|ao|admin|post")
public class StaffChatNoLevelsCommand extends BaseCommand {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final TaskChainFactory taskFactory;
    private StorageMessagingHandler handler = null;

    private final UUID serverID = new UUID(0L, 0L);

    private final StaffChatAPI api = StaffChatAPI.getInstance();

    public StaffChatNoLevelsCommand(TaskChainFactory taskFactory) {
        this.taskFactory = taskFactory;

        try {
            this.handler = ServiceLocator.get(StorageMessagingHandler.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error("Could not get handler service.");
        }
    }

    @Default
    @CommandPermission("ssc.use")
    @Description("{@@description.chat}")
    @Syntax("[chat]")
    @CommandCompletion("@nothing")
    public void onChat(CommandIssuer issuer, @Optional String chat) {
        if (handler == null) {
            logger.error("Could not get handler service.");
            issuer.sendError(Message.ERROR__INTERNAL);
            return;
        }

        taskFactory.newChain()
                .<Boolean>asyncCallback((v, f) -> {
                    try {
                        api.sendChat(issuer.isPlayer() ? issuer.getUniqueId() : serverID, (byte) 1, chat);
                        f.accept(Boolean.TRUE);
                    } catch (APIException ex) {
                        logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                        f.accept(Boolean.FALSE);
                    }
                })
                .syncLast(f -> {
                    if (!f) {
                        issuer.sendError(Message.ERROR__INTERNAL);
                    }
                })
                .execute();
    }

    @CatchUnknown
    @Syntax("[command]")
    public void onHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }
}
