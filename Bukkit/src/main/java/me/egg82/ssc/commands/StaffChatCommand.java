package me.egg82.ssc.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.annotation.*;
import co.aikar.taskchain.TaskChainFactory;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.UUID;
import me.egg82.ssc.core.LevelResult;
import me.egg82.ssc.core.PostChatResult;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
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
                        f.accept(setToggle(cachedConfig.get().getMessaging(), issuer.getUniqueId(), l));
                        return;
                    }

                    f.accept(sendMessage(cachedConfig.get().getStorage(), cachedConfig.get().getMessaging(), issuer.getUniqueId(), l, chat));
                })
                .syncLast(f -> {
                    if (!f.booleanValue()) {
                        issuer.sendError(Message.ERROR__INTERNAL);
                    }
                })
                .execute();
    }

    private boolean setToggle(List<Messaging> messaging, UUID playerID, byte level) {
        if (messaging.size() > 0) {
            boolean handled = false;
            UUID messageID = UUID.randomUUID();
            handler.cacheMessage(messageID);
            for (Messaging m : messaging) {
                try {
                    m.sendToggle(
                            messageID,
                            playerID,
                            level
                    );
                    handled = true;
                } catch (MessagingException ex) {
                    logger.error(ex.getMessage(), ex);
                }
            }

            return handled;
        }

        return true;
    }

    private boolean sendMessage(List<Storage> storage, List<Messaging> messaging, UUID playerID, byte level, String message) {
        PostChatResult postResult = null;
        Storage postedStorage = null;
        for (Storage s : storage) {
            try {
                postResult = s.post(playerID, level, message);
                postedStorage = s;
                break;
            } catch (StorageException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        if (postResult == null) {
            return false;
        }

        for (Storage s : storage) {
            try {
                if (s == postedStorage) {
                    continue;
                }
                s.postRaw(
                        postResult.getID(),
                        postResult.getLongServerID(),
                        postResult.getLongPlayerID(),
                        postResult.getLevel(),
                        postResult.getMessage(),
                        postResult.getDate()
                );
            } catch (StorageException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }

        if (messaging.size() > 0) {
            boolean handled = false;
            UUID messageID = UUID.randomUUID();
            handler.cacheMessage(messageID);
            for (Messaging m : messaging) {
                try {
                    m.sendPost(
                            messageID,
                            postResult.getID(),
                            postResult.getLongServerID(),
                            postResult.getServerID(),
                            postResult.getServerName(),
                            postResult.getLongPlayerID(),
                            postResult.getPlayerID(),
                            postResult.getLevel(),
                            postResult.getLevelName(),
                            postResult.getMessage(),
                            postResult.getDate()
                    );
                    handled = true;
                } catch (MessagingException ex) {
                    logger.error(ex.getMessage(), ex);
                }
            }

            return handled;
        }

        handler.postMessage(postResult.toChatResult());
        return true;
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
