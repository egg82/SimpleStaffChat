package me.egg82.ssc.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.annotation.*;
import co.aikar.taskchain.TaskChainFactory;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.UUID;
import me.egg82.ssc.APIException;
import me.egg82.ssc.StaffChatAPI;
import me.egg82.ssc.core.LevelResult;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.services.StorageMessagingHandler;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CommandAlias("chat|sc|ac|ao|admin|post")
public class StaffChatCommand extends BaseCommand {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final TaskChainFactory taskFactory;
    private StorageMessagingHandler handler = null;

    private final UUID serverID = new UUID(0L, 0L);

    private final StaffChatAPI api = StaffChatAPI.getInstance();

    public StaffChatCommand(TaskChainFactory taskFactory) {
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
    @Syntax("<level> [chat]")
    @CommandCompletion("@level")
    public void onChat(CommandIssuer issuer, String level, @Optional String chat) {
        if (handler == null) {
            logger.error("Could not get handler service.");
            issuer.sendError(Message.ERROR__INTERNAL);
            return;
        }

        taskFactory.newChain()
                .<Boolean>asyncCallback((v, f) -> {
                    java.util.Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
                    if (!cachedConfig.isPresent()) {
                        logger.error("Cached config could not be fetched.");
                        f.accept(Boolean.FALSE);
                        return;
                    }

                    byte l = getLevel(level, cachedConfig.get().getStorage());
                    if (l == -1) {
                        issuer.sendError(Message.ERROR__LEVEL_NOT_FOUND);
                        f.accept(Boolean.TRUE);
                        return;
                    }

                    if (chat == null || chat.isEmpty()) {
                        if (!issuer.isPlayer()) {
                            issuer.sendError(Message.ERROR__NO_CONSOLE);
                            f.accept(Boolean.TRUE);
                            return;
                        }

                        try {
                            api.toggleChat(issuer.getUniqueId(), l);
                            f.accept(Boolean.TRUE);
                        } catch (APIException ex) {
                            logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                            f.accept(Boolean.FALSE);
                        }
                        return;
                    }

                    try {
                        api.sendChat(issuer.isPlayer() ? issuer.getUniqueId() : serverID, l, chat);
                        f.accept(Boolean.TRUE);
                    } catch (APIException ex) {
                        logger.error("[Hard: " + ex.isHard() + "] " + ex.getMessage(), ex);
                        f.accept(Boolean.FALSE);
                    }
                })
                .syncLast(f -> {
                    if (!f.booleanValue()) {
                        issuer.sendError(Message.ERROR__INTERNAL);
                    }
                })
                .execute();
    }

    private byte getLevel(String l, List<Storage> storage) {
        ImmutableList<LevelResult> levels = null;
        for (Storage s : storage) {
            try {
                levels = s.getLevels();
                break;
            } catch (StorageException ex) {
                logger.error("Could not get levels from " + s.getClass().getSimpleName() + ".", ex);
            }
        }
        if (levels == null) {
            return -1;
        }

        for (LevelResult level : levels) {
            if (String.valueOf(level.getLevel()).equalsIgnoreCase(l) || level.getName().toLowerCase().equalsIgnoreCase(l)) {
                return level.getLevel();
            }
        }
        return -1;
    }

    @CatchUnknown
    @Syntax("[command]")
    public void onHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }
}
