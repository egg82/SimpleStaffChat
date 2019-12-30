package me.egg82.ssc;

import java.util.Optional;
import java.util.UUID;
import me.egg82.ssc.core.PostChatResult;
import me.egg82.ssc.extended.CachedConfigValues;
import me.egg82.ssc.messaging.Messaging;
import me.egg82.ssc.messaging.MessagingException;
import me.egg82.ssc.services.StorageMessagingHandler;
import me.egg82.ssc.storage.Storage;
import me.egg82.ssc.storage.StorageException;
import me.egg82.ssc.utils.ConfigUtil;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaffChatAPI {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final StaffChatAPI api = new StaffChatAPI();

    private StaffChatAPI() { }

    public static StaffChatAPI getInstance() { return api; }

    public void toggleChat(UUID playerID, byte level) throws APIException {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            throw new APIException(false, "Could not get cached config.");
        }

        StorageMessagingHandler handler;
        try {
            handler = ServiceLocator.get(StorageMessagingHandler.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            throw new APIException(false, "Could not get handler service.");
        }

        if (cachedConfig.get().getMessaging().size() > 0) {
            boolean handled = false;
            boolean canRecover = false;
            UUID messageID = UUID.randomUUID();
            handler.cacheMessage(messageID);
            for (Messaging m : cachedConfig.get().getMessaging()) {
                try {
                    m.sendToggle(
                            messageID,
                            playerID,
                            level
                    );
                    handled = true;
                } catch (MessagingException ex) {
                    if (ex.isAutomaticallyRecoverable()) {
                        canRecover = true;
                    }
                }
            }

            if (!handled) {
                throw new APIException(!canRecover, "Could not send toggle through messaging.");
            }
        }

        handler.doToggle(playerID, level);
    }

    public void sendChat(UUID playerID, byte level, String message) throws APIException {
        Optional<CachedConfigValues> cachedConfig = ConfigUtil.getCachedConfig();
        if (!cachedConfig.isPresent()) {
            throw new APIException(false, "Could not get cached config.");
        }

        StorageMessagingHandler handler;
        try {
            handler = ServiceLocator.get(StorageMessagingHandler.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            throw new APIException(false, "Could not get handler service.");
        }

        PostChatResult postResult = null;
        Storage postedStorage = null;
        boolean canRecover = false;
        for (Storage s : cachedConfig.get().getStorage()) {
            try {
                postResult = s.post(playerID, level, message);
                postedStorage = s;
                break;
            } catch (StorageException ex) {
                if (ex.isAutomaticallyRecoverable()) {
                    canRecover = true;
                }
            }
        }
        if (postResult == null) {
            throw new APIException(!canRecover, "Could not put chat in storage.");
        }

        handler.cachePost(postResult.getID());
        for (Storage s : cachedConfig.get().getStorage()) {
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

        canRecover = false;
        if (cachedConfig.get().getMessaging().size() > 0) {
            boolean handled = false;
            UUID messageID = UUID.randomUUID();
            handler.cacheMessage(messageID);
            for (Messaging m : cachedConfig.get().getMessaging()) {
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
                    if (ex.isAutomaticallyRecoverable()) {
                        canRecover = true;
                    }
                }
            }

            if (!handled) {
                throw new APIException(!canRecover, "Could not send chat through messaging.");
            }
        }

        handler.postMessage(postResult.toChatResult());
    }
}
