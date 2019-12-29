package me.egg82.ssc.events;

import co.aikar.commands.CommandManager;
import java.util.Optional;
import java.util.UUID;
import me.egg82.ssc.core.PostChatResult;
import me.egg82.ssc.enums.Message;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
import me.egg82.ssc.services.CollectionProvider;
import me.egg82.ssc.services.StorageMessagingHandler;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import ninja.egg82.events.BukkitEventFilters;
import ninja.egg82.events.BukkitEvents;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

public class PlayerChatEvents extends EventHolder {
    private StorageMessagingHandler handler = null;
    private final CommandManager commandManager;

    public PlayerChatEvents(Plugin plugin, CommandManager commandManager) {
        this.commandManager = commandManager;

        try {
            this.handler = ServiceLocator.get(StorageMessagingHandler.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error("Could not get handler service.");
            return;
        }

        events.add(
                BukkitEvents.subscribe(plugin, AsyncPlayerChatEvent.class, EventPriority.HIGH)
                        .filter(BukkitEventFilters.ignoreCancelled())
                        .handler(this::sendChat)
        );
    }

    private void sendChat(AsyncPlayerChatEvent event) {
        byte level = CollectionProvider.getToggled().getOrDefault(event.getPlayer().getUniqueId(), (byte) -1);
        if (level == -1) {
            return;
        }

        event.setCancelled(true);

        if (!event.getPlayer().hasPermission("ssc.level." + level)) {
            CollectionProvider.getToggled().remove(event.getPlayer().getUniqueId());
            commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__NO_PERMS);
            return;
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__INTERNAL);
            return;
        }

        PostChatResult postResult = null;
        Storage postedStorage = null;
        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                postResult = storage.post(event.getPlayer().getUniqueId(), level, event.getMessage());
                postedStorage = storage;
                break;
            } catch (StorageException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        if (postResult == null) {
            commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__INTERNAL);
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                if (storage == postedStorage) {
                    continue;
                }
                storage.postRaw(
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

        if (cachedConfig.get().getMessaging().size() > 0) {
            boolean handled = false;
            UUID messageID = UUID.randomUUID();
            handler.cacheMessage(messageID);
            for (Messaging messaging : cachedConfig.get().getMessaging()) {
                try {
                    messaging.sendPost(
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

            if (!handled) {
                commandManager.getCommandIssuer(event.getPlayer()).sendError(Message.ERROR__INTERNAL);
                return;
            }
        }

        handler.postMessage(postResult.toChatResult());
    }
}
