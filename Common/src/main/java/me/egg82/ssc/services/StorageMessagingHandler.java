package me.egg82.ssc.services;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageMessagingHandler implements StorageHandler, MessagingHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private LoadingCache<UUID, Boolean> cachedMessages = Caffeine.newBuilder().expireAfterAccess(5L, TimeUnit.MINUTES).expireAfterWrite(10L, TimeUnit.MINUTES).build(k -> Boolean.FALSE);

    public StorageMessagingHandler() { }

    public void playerIDCreationCallback(UUID playerID, long longPlayerID, Storage callingStorage) {
        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Player created: " + playerID.toString() + " = " + longPlayerID);
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            if (storage != callingStorage) {
                try {
                    storage.setPlayerRaw(longPlayerID, playerID);
                } catch (StorageException ex) {
                    logger.error("Could not set raw player data for " + storage.getClass().getSimpleName() + ".", ex);
                }
            }
        }

        UUID messageID = UUID.randomUUID();
        cachedMessages.put(messageID, Boolean.TRUE);

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            try {
                messaging.sendPlayer(messageID, longPlayerID, playerID);
            } catch (MessagingException ex) {
                logger.error("Could not send raw player data for " + messaging.getClass().getSimpleName() + ".", ex);
            }
        }
    }

    public void levelCallback(UUID messageID, byte level, String name, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Level created/updated: " + level + " = \"" + name + "\"");
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setLevelRaw(level, name);
            } catch (StorageException ex) {
                logger.error("Could not set raw level data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendLevel(messageID, level, name);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw level data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void serverCallback(UUID messageID, long longServerID, UUID serverID, String name, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Server created/updated: " + serverID.toString() + " = \"" + name + "\"");
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setServerRaw(longServerID, serverID, name);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendServer(messageID, longServerID, serverID, name);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void playerCallback(UUID messageID, UUID playerID, long longPlayerID, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Player created: " + playerID.toString() + " = " + longPlayerID);
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.setPlayerRaw(longPlayerID, playerID);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendPlayer(messageID, longPlayerID, playerID);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }

    public void postCallback(UUID messageID, long postID, long longServerID, long longPlayerID, byte level, String message, long date, Messaging callingMessaging) {
        if (cachedMessages.get(messageID)) {
            return;
        }

        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Post created: " + postID + " - \"" + message + "\"");
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            try {
                storage.postRaw(postID, longServerID, longPlayerID, level, message, date);
            } catch (StorageException ex) {
                logger.error("Could not set raw server data for " + storage.getClass().getSimpleName() + ".", ex);
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingMessaging) {
                try {
                    messaging.sendPost(messageID, postID, longServerID, longPlayerID, level, message, date);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw server data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }
}
