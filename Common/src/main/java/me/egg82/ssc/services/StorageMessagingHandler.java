package me.egg82.ssc.services;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.utils.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageMessagingHandler implements StorageHandler {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public StorageMessagingHandler() { }

    public void playerIDCreationCallback(UUID playerID, long longPlayerID, Storage callingStorage) {
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
            if (storage != callingStorage) {
                try {
                    storage.setPlayerRaw(longPlayerID, playerID);
                } catch (SQLException ex) {
                    logger.error("Could not set raw player data for " + storage.getClass().getSimpleName() + ".", ex);
                }
            }
        }

        for (Messaging messaging : cachedConfig.get().getMessaging()) {

        }
    }
}
