package me.egg82.ssc.services;

import java.util.Optional;
import java.util.UUID;
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

    public StorageMessagingHandler() { }

    public void playerIDCreationCallback(UUID playerID, long longPlayerID, Storage callingStorage) { playerIDCreation(playerID, longPlayerID, callingStorage); }

    public void playerIDCreationCallback(UUID playerID, long longPlayerID, Messaging callingMessaging) { playerIDCreation(playerID, longPlayerID, callingMessaging); }

    private void playerIDCreation(UUID playerID, long longPlayerID, Object callingObject) {
        if (ConfigUtil.getDebugOrFalse()) {
            logger.info("Player ID created: " + playerID.toString() + " = " + longPlayerID);
            logger.info("Propagating to storage & messaging");
        }

        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        for (Storage storage : cachedConfig.get().getStorage()) {
            if (storage != callingObject) {
                try {
                    storage.setPlayerRaw(longPlayerID, playerID);
                } catch (StorageException ex) {
                    logger.error("Could not set raw player data for " + storage.getClass().getSimpleName() + ".", ex);
                }
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {
            if (messaging != callingObject) {
                try {
                    messaging.sendPlayerRaw(longPlayerID, playerID);
                } catch (MessagingException ex) {
                    logger.error("Could not send raw player data for " + messaging.getClass().getSimpleName() + ".", ex);
                }
            }
        }
    }
}
